import { Innertube, Player, Platform, Log, Constants, ClientType } from 'youtubei.js/web';
import { track, sections, safeError, largestArtwork } from './mapping.mjs';
import { evaluatePlayer } from './evaluator.mjs';
import { nativeTransport } from './transport.mjs';
import { readYtConfig, playerFailure } from './web-config.mjs';
import { sidAuthorization } from './music-auth.mjs';
import { proofBindings, delegatedAccount, contentBoundGvs as readsContentBinding } from './proof-bindings.mjs';

Log.setLevel(Log.Level.NONE);
Platform.shim.server = true;
Platform.shim.eval = evaluatePlayer;
for (const method of ['log', 'info', 'debug', 'warn', 'error']) console[method] = () => {};
const native = globalThis.MusicNative;
const transportFetch = nativeTransport(native, 'musicHttpResult');
// What YouTube said about the last music browse: a saved session that comes back
// "loggedOut" means the feed is generic, not the account's own.
let lastMusicLoggedOut = null;
const MUSIC_ORIGIN = 'https://music.youtube.com';
const MUSIC_PATHS = /^\/youtubei\/v1\/(browse|next|search|music\/get_search_suggestions)$/;
/**
 * YouTube Music's own web client talks to music.youtube.com. Sending its requests to
 * www.youtube.com with a www-signed SAPISIDHASH can come back as a signed-out,
 * generic feed, so music requests go to the music origin, signed for that origin.
 */
const nativeFetch = async (input, init = {}) => {
  const req = input instanceof Request ? input : new Request(input, init);
  const url = new URL(req.url);
  const body = typeof init.body === 'string' ? init.body : null;
  if (url.origin !== 'https://www.youtube.com' || !MUSIC_PATHS.test(url.pathname) || !body || !body.includes('"clientName":"WEB_REMIX"'))
    return transportFetch(input, init);
  const headers = new Headers(init.headers || req.headers);
  headers.set('Origin', MUSIC_ORIGIN); headers.set('X-Origin', MUSIC_ORIGIN); headers.set('Referer', MUSIC_ORIGIN + '/');
  const cookie = savedSession()?.cookie;
  if (cookie && headers.has('Authorization')) {
    const auth = await sidAuthorization(cookie, MUSIC_ORIGIN);
    if (auth) headers.set('Authorization', auth);
  }
  url.host = 'music.youtube.com';
  const response = await transportFetch(url.toString(), { method: req.method, headers, body });
  if (url.pathname.endsWith('/browse') && response.ok) {
    try { const data = await response.clone().json(); const flag = data?.responseContext?.mainAppWebResponseContext?.loggedOut; if (typeof flag === 'boolean') lastMusicLoggedOut = flag; }
    catch { /* The provider parser reports unreadable bodies. */ }
  }
  return response;
};
let tubePromise, homeFeed, feedRequest = 0, playerPromise, playerScript = '', webConfigPromise, challengePromise, challengeExpires = 0, dataSyncId = '', accountProfilePromise;
let contentBoundGvs = { YTMUSIC: false, WEB: false };
// The last path confirmed by native playback, never a speculative/preloaded URL.
let preferredPath = null;
const proofs = new Map(); let proofSerial = 0;
function emit(type, data = {}) { native.event(type, JSON.stringify(data)); }
function failure(code, message, diagnostic = '') { return Object.assign(new Error(message), { code, diagnostic }); }
const memory = new Map();
const cache = { get: async k => memory.get(k), set: async (k, v) => { memory.set(k, v); }, remove: async k => { memory.delete(k); } };

globalThis.musicProofResult = (id, result) => {
  const pending = proofs.get(id); if (!pending) return;
  proofs.delete(id); clearTimeout(pending.timer);
  if (result.error || !result.playerToken || !result.streamToken) pending.reject(failure('VERIFICATION_REQUIRED', 'YouTube could not verify playback on this device. Retry or sign in to YouTube.', 'runtime verification: rejected'));
  else pending.resolve(result);
};
function attestationChallenge(api) {
  if (!challengePromise || Date.now() >= challengeExpires) challengePromise = api.actions.execute('/att/get', { engagementType: 'ENGAGEMENT_TYPE_UNBOUND', client: 'WEB' }).then(response => {
    const c = response.data?.bgChallenge;
    if (!c?.program || !c?.globalName) throw failure('VERIFICATION_REQUIRED', 'YouTube did not provide a playback verification challenge.', 'WEB att/get: missing challenge');
    challengeExpires = Date.now() + 240000;
    return { program: c.program, globalName: c.globalName, interpreterHash: c.interpreterHash,
      interpreterUrl: c.interpreterUrl, interpreterJavascript: c.interpreterJavascript };
  }).catch(error => { challengePromise = undefined; throw error; });
  return challengePromise;
}
async function playbackProof(api, videoId, client) {
  const bindings = proofBindings({ client, videoId, visitorData: api.session.context.client.visitorData,
    dataSyncId, authenticated: api.session.logged_in, contentBoundGvs: contentBoundGvs[client] });
  // Use the live WEB attestation endpoint. The old WAA /Create discovery endpoint
  // and WEB_REMIX att/get are not used. Only this challenge reaches the sandbox.
  const challenge = await attestationChallenge(api);

  return new Promise((resolve, reject) => {
    const id = String(++proofSerial);
    const timer = setTimeout(() => { proofs.delete(id); reject(failure('VERIFICATION_REQUIRED', 'YouTube playback verification timed out.', 'runtime verification: TIMEOUT')); }, 60000);
    proofs.set(id, { resolve, reject, timer });
    native.proof(id, JSON.stringify({ videoId, ...bindings, challenge }));
  });
}

function savedSession() {
  try { const value = JSON.parse(native.loadSession() || 'null'); return value?.kind === 'youtube-web' ? value : null; }
  catch { return null; }
}
async function bootstrap(url, clientName) {
  const response = await nativeFetch(url);
  if ([301, 302, 303, 307, 308].includes(response.status)) throw failure('WEB_SIGN_IN_REQUIRED', 'YouTube needs a browser step. Open Sign in to YouTube in Settings to continue.', `web session: browser redirect HTTP ${response.status}`);
  if (!response.ok) throw failure('BOOTSTRAP_HTTP', `YouTube could not open its web session (HTTP ${response.status}).`, `web session: HTTP ${response.status}`);
  const config = readYtConfig(await response.text());
  const declared = config.INNERTUBE_CONTEXT?.client?.clientName;
  if (declared && declared !== clientName) throw failure('BOOTSTRAP_CLIENT', 'YouTube opened a different web client. Open YouTube sign-in in Settings and retry.', 'web session: redirected client mismatch');
  return config;
}
async function accountProfile(api) {
  if (!api.session.logged_in) throw failure('AUTH_REQUIRED', 'Sign in to YouTube in Settings.');
  // Verify once per app-owned session. Initialisation and confirmation share it.
  if (!accountProfilePromise) accountProfilePromise = api.account.getInfo(true).then(accounts => {
    const selected = accounts.find(a => a.is_selected && !a.is_disabled) || (accounts.length === 1 && !accounts[0].is_disabled ? accounts[0] : null);
    const name = selected?.account_name?.toString();
    if (!name) throw failure('AUTH_REQUIRED', 'YouTube did not confirm this account. Finish sign-in and retry.', 'WEB account/accounts_list: no usable account');
    return { name };
  }).catch(error => { accountProfilePromise = undefined; throw error; });
  return accountProfilePromise;
}
async function tube() {
  if (!tubePromise) tubePromise = (async () => {
    const locale = JSON.parse(native.locale?.() || '{}');
    const saved = savedSession();
    const accountQuery = saved ? `?authuser=${saved.authUser || 0}` : '';
    // A captured sign-in page is already a real web bootstrap. Account confirmation
    // must not depend on refetching a homepage that can redirect or set cookies.
    const config = saved ? { VISITOR_DATA: saved.visitorData, INNERTUBE_CLIENT_VERSION: saved.clientVersion,
      PLAYER_JS_URL: saved.playerScript, EXPERIMENT_FLAGS: { html5_generate_content_po_token: saved.musicContentBoundGvs } }
      : await bootstrap('https://music.youtube.com/' + accountQuery, 'WEB_REMIX');
    const musicContext = config.INNERTUBE_CONTEXT;
    const visitorData = musicContext?.client?.visitorData || config.VISITOR_DATA || saved?.visitorData;
    if (!visitorData) throw failure('WEB_SESSION', 'YouTube did not return a web session. Open sign-in in Settings, or retry.', 'WEB_REMIX bootstrap: missing visitor data');
    const version = config.INNERTUBE_CLIENT_VERSION || musicContext?.client?.clientVersion || saved?.clientVersion;
    if (version) Constants.CLIENTS.YTMUSIC.VERSION = version;
    const webConfig = saved ? {} : await bootstrap('https://www.youtube.com/' + accountQuery, 'WEB').catch(() => ({}));
    dataSyncId = saved?.dataSyncId || config.DATASYNC_ID || '';
    contentBoundGvs = { YTMUSIC: readsContentBinding(config, 'YTMUSIC'), WEB: readsContentBinding(webConfig, 'WEB') };
    const webVersion = webConfig.INNERTUBE_CLIENT_VERSION || webConfig.INNERTUBE_CONTEXT?.client?.clientVersion;
    if (webVersion) Constants.CLIENTS.WEB.VERSION = webVersion;
    const rawScript = config.PLAYER_JS_URL || config.WEB_PLAYER_CONTEXT_CONFIGS?.WEB_PLAYER_CONTEXT_CONFIG_ID_MUSIC_PLAYER?.jsUrl || webConfig.PLAYER_JS_URL;
    if (rawScript) {
      const url = new URL(rawScript, 'https://www.youtube.com');
      if (url.origin === 'https://www.youtube.com' && /^\/s\/player\/[A-Za-z0-9_-]+\//.test(url.pathname)) playerScript = url.href;
    }
    const api = await Innertube.create({ fetch: nativeFetch, cache, cookie: saved?.cookie,
      visitor_data: visitorData, account_index: saved?.authUser || 0, on_behalf_of_user: delegatedAccount(dataSyncId, saved?.delegatedSessionId),
      client_type: ClientType.WEB, generate_session_locally: true, enable_session_cache: false,
      retrieve_player: false, retrieve_innertube_config: false,
      lang: locale.language || 'en', location: locale.country || 'US', user_agent: locale.userAgent, device_category: 'mobile' });
    // Reuse the actual web client's bootstrap context, instead of inventing an Android/TV profile.
    if (musicContext?.client) Object.assign(api.session.context.client, musicContext.client);
    else {
      api.session.context.client.osName = 'Android';
      api.session.context.client.osVersion = locale.androidVersion;
      api.session.context.client.platform = 'MOBILE';
      api.session.context.client.clientFormFactor = 'SMALL_FORM_FACTOR';
      const chromeVersion = locale.userAgent?.match(/Chrome\/([0-9.]+)/)?.[1];
      if (chromeVersion) api.session.context.client.browserVersion = chromeVersion;
    }
    api.session.context.client.visitorData = visitorData;
    // YouTube.js assumes WEB is the base context: its explicit WEB option does
    // not reset a MUSIC base context. Music calls select YTMUSIC themselves.
    api.session.context.client.clientName = Constants.CLIENTS.WEB.NAME;
    api.session.context.client.clientVersion = webVersion || Constants.CLIENTS.WEB.VERSION;
    api.session.context.client.userAgent = locale.userAgent;
    ensurePlayer(api).catch(() => {});
    // Warm what the first song needs while the user is still browsing.
    if (saved) attestationChallenge(api).catch(() => {});
    if (saved) {
      try { emit('connected', await accountProfile(api)); }
      catch (error) { emit('auth-error', safeError(error)); }
    }
    return api;
  })().catch(error => { tubePromise = undefined; throw error; });
  return tubePromise;
}

async function search(api, query) {
  const url = (() => { try { return new URL(query); } catch { return null; } })();
  if (url) {
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || !['youtube.com', 'www.youtube.com', 'm.youtube.com', 'music.youtube.com', 'youtu.be', 'www.youtu.be', 'www.youtube-nocookie.com'].includes(url.hostname)) throw failure('LINK', 'Choose a valid YouTube link.');
    const id = url.hostname.endsWith('youtu.be') ? url.pathname.slice(1) : url.searchParams.get('v') || (/^\/(shorts|embed|live)\//.test(url.pathname) ? url.pathname.split('/')[2] : null);
    if (id && /^[A-Za-z0-9_-]{11}$/.test(id)) {
      const info = await api.getBasicInfo(id, { client: 'YTMUSIC' });
      const b = info.basic_info;
      return [{ id: `yt:${id}`, title: b.title || 'YouTube recording', artist: b.author || b.channel?.name || 'Unknown artist', url: `https://www.youtube.com/watch?v=${id}`, artwork: largestArtwork(b.thumbnail), durationMs: (b.duration || 0) * 1000, source: 'YOUTUBE' }];
    }
    const list = url.searchParams.get('list');
    if (list && /^[A-Za-z0-9_-]{10,100}$/.test(list)) return (await api.music.getPlaylist(list)).contents?.map(track).filter(Boolean) || [];
    throw failure('LINK', 'Choose a YouTube video or playlist link.');
  }
  const result = await api.music.search(query, { type: 'song' });
  return (result.contents || []).flatMap(s => s.contents || [s]).map(track).filter(Boolean);
}
async function prepareWebPlayback(api) {
  if (!savedSession()) return;
  if (!webConfigPromise) webConfigPromise = (async () => {
    const saved = savedSession();
    // Refresh WEB playback metadata separately; it cannot revoke a valid login.
    const config = await bootstrap('https://www.youtube.com/?authuser=' + (saved?.authUser || 0), 'WEB').catch(() => null);
    if (!config) return;
    const version = config.INNERTUBE_CLIENT_VERSION || config.INNERTUBE_CONTEXT?.client?.clientVersion;
    if (version) { Constants.CLIENTS.WEB.VERSION = version; api.session.context.client.clientVersion = version; }
    contentBoundGvs.WEB = readsContentBinding(config, 'WEB');
  })();
  return webConfigPromise;
}
async function ensurePlayer(api) {
  if (!playerPromise) playerPromise = Player.create(cache, async (input, init) => {
    const url = String(input instanceof Request ? input.url : input);
    const target = playerScript && /\/s\/player\/[^/]+\/player_es6\.vflset\/.*\/base\.js/.test(url) ? playerScript : input;
    return nativeFetch(target, init);
  }, undefined, playerScript.match(/\/s\/player\/([^/]+)\//)?.[1]).catch(error => { playerPromise = undefined; throw error; });
  const player = await playerPromise;
  api.session.player = player;
  return player;
}
function normalizePlayerError(client, error) {
  if (error.info?.status) return playerFailure(client, { playability_status: error.info });
  return error;
}
/** Media requests must look like the client that asked for the stream. */
function mediaHeaders(api, client) {
  // visionOS streams are fetched like the app that asked for them: its own agent, no web origin.
  if (client === 'VISIONOS') return { 'User-Agent': Constants.CLIENTS.VISIONOS.USER_AGENT };
  if (client === 'VISIONOS_MUSIC') return { 'User-Agent': VISION_MUSIC.userAgent };
  return { 'User-Agent': api.session.context.client.userAgent, 'Origin': 'https://www.youtube.com', 'Referer': 'https://www.youtube.com/' };
}
async function streamFromInfo(api, id, client, info, tokens, requiredFormat) {
  if (info.playability_status?.status !== 'OK') throw playerFailure(client, info);
  if (info.basic_info?.is_live) throw failure('LIVE_UNSUPPORTED', 'Live recordings are not supported in this build.', `${client}: live stream`);
  const formats = (info.streaming_data?.adaptive_formats || []).filter(f => f.has_audio && !f.has_video && !f.is_drm && !f.drm_families?.length && (f.url || f.signature_cipher || f.cipher));
  if (!formats.length) {
    const sabr = !!info.streaming_data?.server_abr_streaming_url;
    throw failure(sabr ? 'SABR_UNSUPPORTED' : 'NO_AUDIO', sabr ? 'YouTube returned SABR-only audio. This build cannot play that transport yet.' : 'YouTube did not provide an audio stream for this recording.', `${client}: ${sabr ? 'SABR_ONLY' : 'NO_AUDIO_FORMATS'}`);
  }
  const compatible = requiredFormat ? formats.filter(f => Number(f.itag) === requiredFormat.formatId) : formats;
  if (!compatible.length) throw failure('FORMAT_UNAVAILABLE', 'YouTube changed the audio format. Tap Retry to restart this recording.', `${client}: incompatible audio format`);
  let lastError;
  for (const format of [...compatible].sort((a, b) => Number(b.mime_type?.startsWith('audio/mp4')) - Number(a.mime_type?.startsWith('audio/mp4')) || b.bitrate - a.bitrate).slice(0, 2)) {
    try {
      const needsPlayer = format.signature_cipher || format.cipher || /[?&]n=/.test(format.url || '');
      if (needsPlayer) {
        const player = await ensurePlayer(api);
        if (!player.data?.exported?.includes('nsigFunction')) throw failure('PLAYER_SCRIPT', 'Could not read YouTube’s current player script. Check for an app update.', `${client}: signature function missing`);
      }
      const target = new URL(await format.decipher(api.session.player));
      if (target.protocol !== 'https:' || !target.hostname.endsWith('.googlevideo.com') || target.pathname !== '/videoplayback') throw failure('MEDIA_URL', 'YouTube returned an unsupported media address.');
      if (tokens) target.searchParams.set('pot', tokens.streamToken);
      if (info.cpn) target.searchParams.set('cpn', info.cpn);
      const headers = mediaHeaders(api, client);
      // Media3 validates the real request. A separate probe downloaded the same
      // bytes twice and added a CDN round trip before the user could hear anything.
      const contentLength = [format.content_length, target.searchParams.get('clen')].map(Number).find(n => Number.isSafeInteger(n) && n > 0) || 0;
      if (requiredFormat && (contentLength !== requiredFormat.contentLength || format.mime_type?.split(';')[0] !== requiredFormat.mimeType))
        throw failure('FORMAT_UNAVAILABLE', 'YouTube changed the audio format. Tap Retry to restart this recording.', `${client}: incompatible audio bytes`);
      emitMetadata(id, info);
      return { url: target.href, contentLength, formatId: Number(format.itag), mimeType: format.mime_type?.split(';')[0] || null, headers };
    } catch (error) { lastError = error; }
  }
  throw lastError;
}
// Clients whose audio URLs YouTube serves without a BotGuard proof, as the maintained
// open-source YouTube players use them (checked November 2026):
// - NewPipe Extractor streams only from the visionOS app client (1.04, native agent,
//   a visitor fetched by that client); yt-dlp's no-JavaScript default is visionOS too.
// - Metrolist / innertubex benchmark visionOS 0.1 on the YouTube Music player endpoint,
//   and the embedded web player with the embed page's host flags.
// TV clients and Android VR are not used: TV playback is unreliable, and VR audio is
// refused by the media server after the first 1 MiB.
// They run as a separate signed-out session: these clients do not accept web cookies,
// and an account must never be presented under another client's name.
const GUEST_CLIENTS = ['VISIONOS', 'VISIONOS_MUSIC', 'WEB_EMBEDDED'];
const pathId = (client, verified) => client + (verified ? ':proof' : '');
const PLAYBACK_PATHS = [...GUEST_CLIENTS, 'YTMUSIC', 'WEB', 'YTMUSIC:proof', 'WEB:proof'];
const guestClientType = client => client === 'VISIONOS_MUSIC' ? 'VISIONOS' : client;
const VISION = { version: '1.04', model: 'RealityDevice17,1', os: '26.6.0.23O770', agentOs: '26_6_0' };
Object.assign(Constants.CLIENTS.VISIONOS, { VERSION: VISION.version, DEVICE_MODEL: VISION.model, OS_NAME: 'visionOS', OS_VERSION: VISION.os,
  USER_AGENT: `com.google.visionos.youtube/${VISION.version}(${VISION.model}; U; CPU visionOS ${VISION.agentOs} like Mac OS X; US)` });
const VISION_MUSIC = { clientVersion: '0.1', deviceModel: 'RealityDevice14,1', osName: 'VISION_OS', osVersion: '1.3',
  userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15' };
let visionVisitor = null, visionVisitorPromise;
/** A visitor issued to the visionOS client itself; YouTube declines its player requests without one. */
async function visionVisitorData(api) {
  if (visionVisitor && Date.now() - visionVisitor.at < 1_800_000) return visionVisitor.value;
  if (visionVisitorPromise) return visionVisitorPromise;
  visionVisitorPromise = (async () => {
  const c = Constants.CLIENTS.VISIONOS, client = api.session.context.client;
  const response = await transportFetch('https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false', { method: 'POST', headers: {
    [guestMarker]: '1', 'X-Music-User-Agent': c.USER_AGENT, 'Content-Type': 'application/json', 'X-Goog-Api-Format-Version': '2',
    'X-Youtube-Client-Name': Constants.CLIENT_NAME_IDS.VISIONOS, 'X-Youtube-Client-Version': c.VERSION },
  body: JSON.stringify({ context: { client: { clientName: c.NAME, clientVersion: c.VERSION, clientScreen: 'WATCH', platform: 'MOBILE', deviceMake: 'Apple',
    deviceModel: c.DEVICE_MODEL, osName: c.OS_NAME, osVersion: c.OS_VERSION, hl: client.hl || 'en', gl: client.gl || 'US', utcOffsetMinutes: 0 } } }) });
  const value = response.ok ? (await response.json().catch(() => null))?.responseContext?.visitorData : null;
  if (!value) throw failure('PLAYER_REJECTED', 'YouTube did not open the visionOS client.', `VISIONOS: visitor HTTP ${response.status}`);
  visionVisitor = { value, at: Date.now() };
  return value;
  })().finally(() => { visionVisitorPromise = undefined; });
  return visionVisitorPromise;
}
const guestMarker = 'X-Music-Anonymous';
const EMBED_REFERER = 'https://www.reddit.com/';
// Per-recording embed context: encryptedHostFlags are only honoured together with the
// visitor that fetched the embed page.
const embedContexts = new Map();
async function embedContext(api, id) {
  const cached = embedContexts.get(id);
  if (cached && Date.now() - cached.at < 600_000) return cached;
  const response = await transportFetch(`https://www.youtube.com/embed/${id}?html5=1`, { headers: {
    [guestMarker]: '1', 'X-Music-User-Agent': api.session.context.client.userAgent, 'Referer': EMBED_REFERER, 'Accept-Language': `${api.session.context.client.hl || 'en'};q=0.9` } });
  if (!response.ok) throw failure('PLAYER_REJECTED', 'YouTube did not open the embedded player.', `WEB_EMBEDDED_PLAYER: embed page HTTP ${response.status}`);
  const html = await response.text();
  const flags = html.match(/"encryptedHostFlags"\s*:\s*"([^"]+)"/)?.[1];
  const visitor = html.match(/"VISITOR_DATA"\s*:\s*"([^"]+)"/)?.[1];
  if (!flags || !visitor) throw failure('PLAYER_REJECTED', 'YouTube did not open the embedded player.', 'WEB_EMBEDDED_PLAYER: no embed host flags');
  const context = { flags, visitor, at: Date.now() };
  embedContexts.set(id, context);
  if (embedContexts.size > 32) embedContexts.delete(embedContexts.keys().next().value);
  return context;
}
/** Shapes a guest player request the way the named client's own app sends it. */
function guestRequest(input, init, headers, profile) {
  const url = new URL(String(input instanceof Request ? input.url : input));
  if (url.pathname !== '/youtubei/v1/player' || typeof init.body !== 'string') return { url: url.toString(), body: init.body };
  let body; try { body = JSON.parse(init.body); } catch { return { url: url.toString(), body: init.body }; }
  const client = body.context?.client || {};
  if (client.clientName === 'VISIONOS') {
    const variant = { music: profile === 'VISIONOS_MUSIC', visitor: profile === 'VISIONOS' ? visionVisitor?.value : null };
    // The visionOS app sends no signature timestamp.
    if (body.playbackContext?.contentPlaybackContext) delete body.playbackContext.contentPlaybackContext.signatureTimestamp;
    if (variant?.music) {
      // Metrolist's profile: the 0.1 app on YouTube Music's player endpoint.
      Object.assign(client, { clientVersion: VISION_MUSIC.clientVersion, deviceModel: VISION_MUSIC.deviceModel, osName: VISION_MUSIC.osName, osVersion: VISION_MUSIC.osVersion, userAgent: VISION_MUSIC.userAgent });
      url.host = 'music.youtube.com';
      headers.set('Origin', 'https://music.youtube.com'); headers.set('X-Origin', 'https://music.youtube.com'); headers.set('Referer', 'https://music.youtube.com/');
      headers.set('X-Youtube-Client-Version', VISION_MUSIC.clientVersion); headers.set('X-Music-User-Agent', VISION_MUSIC.userAgent);
    } else {
      // NewPipe's profile: the current app, its native agent and its own visitor, no web origin.
      for (const name of ['Origin', 'X-Origin', 'Referer']) headers.delete(name);
      headers.set('X-Goog-Api-Format-Version', '2'); headers.set('X-Music-User-Agent', Constants.CLIENTS.VISIONOS.USER_AGENT);
    }
    if (variant?.visitor) { client.visitorData = variant.visitor; headers.set('X-Goog-Visitor-Id', variant.visitor); }
  } else if (client.clientName === Constants.CLIENTS.WEB_EMBEDDED.NAME) {
    const embed = embedContexts.get(body.videoId);
    if (embed) {
      client.visitorData = embed.visitor; headers.set('X-Goog-Visitor-Id', embed.visitor);
      body.playbackContext = body.playbackContext || {};
      body.playbackContext.contentPlaybackContext = { ...body.playbackContext.contentPlaybackContext, encryptedHostFlags: embed.flags };
    }
    body.context.thirdParty = { embedUrl: EMBED_REFERER };
    headers.set('Referer', EMBED_REFERER);
  }
  return { url: url.toString(), body: JSON.stringify(body) };
}
const guestPromises = new Map();
function guestTube(api, profile) {
  if (!guestPromises.has(profile)) guestPromises.set(profile, (async () => {
    const guestFetch = (input, init = {}) => {
      const headers = new Headers(init.headers || (input instanceof Request ? input.headers : undefined));
      for (const name of ['Authorization', 'X-Goog-Authuser', 'X-Goog-PageId', 'Cookie']) headers.delete(name);
      headers.set(guestMarker, '1');
      const request = guestRequest(input, init, headers, profile);
      return transportFetch(request.url, { ...init, method: init.method || (input instanceof Request ? input.method : 'GET'), body: request.body, headers });
    };
    const guest = await Innertube.create({ fetch: guestFetch, cache: { get: async () => undefined, set: async () => {}, remove: async () => {} },
      visitor_data: api.session.context.client.visitorData, client_type: ClientType.WEB, generate_session_locally: true,
      retrieve_player: false, retrieve_innertube_config: false, lang: api.session.context.client.hl, location: api.session.context.client.gl });
    guest.session.context.client.userAgent = api.session.context.client.userAgent;
    return guest;
  })().catch(error => { guestPromises.delete(profile); throw error; }));
  return guestPromises.get(profile);
}
const restrictedReason = /private|copyright|not available in your country|age.restrict|premium members|members.only/i;
async function resolve(api, id, forceProof = false, rejectedPaths = [], requiredFormat = null) {
  if (!/^[A-Za-z0-9_-]{11}$/.test(id)) throw failure('LINK', 'Choose a valid YouTube recording.');
  const rejected = new Set(rejectedPaths);
  if (preferredPath && rejected.has(pathId(preferredPath.client, preferredPath.verified))) preferredPath = null;
  if (PLAYBACK_PATHS.every(path => rejected.has(path))) throw failure('MEDIA_REJECTED', 'YouTube rejected every available audio path. Tap Retry to try again.', 'media: all playback paths rejected');
  // Web-client errors explain the failure; proof-free client errors only add diagnostics
  // (an embed-only refusal must not hide the real reason a recording is unavailable).
  const errors = [], guestErrors = []; let tokens;
  const record = (client, error) => { const e = normalizePlayerError(client, error); (GUEST_CLIENTS.includes(client) ? guestErrors : errors).push(e); };
  const attempt = async (client, verified) => {
    if (GUEST_CLIENTS.includes(client)) {
      const guest = await guestTube(api, client);
      if (client === 'WEB_EMBEDDED') { await embedContext(api, id); await ensurePlayer(api).catch(() => {}); }
      if (client === 'VISIONOS') await visionVisitorData(api);
      if (api.session.player) guest.session.player = api.session.player;
      const info = await guest.getBasicInfo(id, { client: guestClientType(client) });
      const stream = await streamFromInfo(guest, id, client, info, undefined, requiredFormat);
      return { ...stream, playbackPath: pathId(client, false) };
    }
    await ensurePlayer(api).catch(() => {});
    if (client === 'WEB') await prepareWebPlayback(api);
    tokens = verified ? await playbackProof(api, id, client) : undefined;
    const info = await api.getBasicInfo(id, { client, ...(tokens ? { po_token: tokens.playerToken } : {}) });
    const stream = await streamFromInfo(api, id, client, info, tokens, requiredFormat);
    return { ...stream, playbackPath: pathId(client, verified) };
  };
  const tryAll = async paths => {
    for (const [client, verified] of paths) {
      if (rejected.has(pathId(client, verified))) continue;
      try { return { stream: await attempt(client, verified) }; }
      catch (error) { record(client, error); }
    }
    return null;
  };
  const hopeless = () => errors.some(e => e.code === 'AUTH_REQUIRED' || e.code === 'LIVE_UNSUPPORTED' || restrictedReason.test(e.message));
  const preferred = preferredPath;
  // A forced retry follows a media rejection: skip the path that produced the rejected URL.
  const skip = (client, verified) => rejected.has(pathId(client, verified)) || (preferred && preferred.client === client && preferred.verified === verified);
  if (preferred && !forceProof) {
    try { return await attempt(preferred.client, preferred.verified); }
    catch (error) { preferredPath = null; record(preferred.client, error); /* Fall back to the remaining paths. */ }
  }
  const web = verified => ['YTMUSIC', 'WEB'].filter(c => !skip(c, verified)).map(c => [c, verified]);
  const guests = GUEST_CLIENTS.filter(c => !skip(c, false)).map(c => [c, false]);
  // Proof-free clients first: web-client audio URLs are now often refused at the media
  // server without a proof (a 403 after resolution, then a forced retry). The account's
  // web clients follow, and the slow attested web path comes last.
  const stages = forceProof ? [guests, web(true)] : [guests, web(false), web(true)];
  for (const stage of stages) {
    if (errors.length && hopeless()) break;
    // Attestation only helps when YouTube asked for it (or withheld audio from the plain request).
    if (!forceProof && stage[0]?.[1] && errors.length && !errors.some(e => ['VERIFICATION_REQUIRED', 'NO_AUDIO', 'FORMAT_UNAVAILABLE'].includes(e.code))) break;
    const found = await tryAll(stage);
    if (found) return found.stream;
  }
  const pick = list => list.find(e => e.code === 'AUTH_REQUIRED') || list.find(e => e.code?.startsWith('MEDIA_HTTP')) || list.find(e => e.code === 'PLAYER_REJECTED') || list.find(e => e.code === 'SABR_UNSUPPORTED') || list.at(-1);
  const useful = pick(errors) || pick(guestErrors);
  const result = safeError(useful);
  // Diagnostics are capped at 200 characters: keep the web reasons, only the guest status,
  // and name clients that failed the same way once ("YTMUSIC, WEB: LOGIN_REQUIRED. …").
  const reasons = new Map();
  for (const text of [...errors.map(e => safeError(e).diagnostic || safeError(e).code), ...guestErrors.map(e => (safeError(e).diagnostic || safeError(e).code).split('. ')[0])]) {
    const [, client, rest] = text.match(/^([A-Z0-9_]+): (.*)$/s) || [, '', text];
    reasons.set(rest, [...(reasons.get(rest) || []), client].filter(Boolean));
  }
  throw failure(result.code, result.message, [...reasons].map(([rest, clients]) => clients.length ? `${[...new Set(clients)].join(', ')}: ${rest}` : rest).join(' | ').slice(0, 800));
}

function emitMetadata(id, info) {
  const b = info.basic_info || {};
  if (b.id !== id) throw failure('VIDEO_MISMATCH', 'YouTube returned a different recording. Retry this recording.', 'player: recording identity mismatch');
  const metadata = { videoId: id, title: b.title || '', artist: b.author || b.channel?.name || '',
    artwork: largestArtwork(b.thumbnail), durationMs: (b.duration || 0) * 1000 };
  if (metadata.title || metadata.artist) emit('metadata', metadata);
  return metadata;
}
const resolving = new Map();
function sharedResolve(api, id, forceProof, rejectedPaths, requiredFormat) {
  const key = JSON.stringify([id, forceProof, rejectedPaths, requiredFormat]);
  if (!resolving.has(key)) resolving.set(key, resolve(api, id, forceProof, rejectedPaths, requiredFormat).finally(() => resolving.delete(key)));
  return resolving.get(key);
}
globalThis.musicCall = async (id, method, args) => {
  try {
    const api = await tube(); let result;
    switch (method) {
      case 'search': result = { tracks: await search(api, args.query) }; break;
      case 'resolve': {
        const rejected = Array.isArray(args.rejectedPaths) ? [...new Set(args.rejectedPaths.filter(p => PLAYBACK_PATHS.includes(p)))].sort() : [];
        const required = args.requiredFormat || null;
        result = await sharedResolve(api, args.videoId, !!args.forceProof, rejected, required); break;
      }
      case 'confirm-playback': {
        if (!/^[A-Za-z0-9_-]{11}$/.test(args.videoId) || !PLAYBACK_PATHS.includes(args.playbackPath)) throw failure('OPERATION', 'Invalid playback confirmation.');
        const [client, proof] = args.playbackPath.split(':'); preferredPath = {client, verified: proof === 'proof'};
        result = {}; break;
      }
      case 'metadata': {
        if (!/^[A-Za-z0-9_-]{11}$/.test(args.videoId)) throw failure('LINK', 'Choose a valid YouTube recording.');
        const info = await api.getBasicInfo(args.videoId, { client: 'YTMUSIC' });
        result = emitMetadata(args.videoId, info); break;
      }
      case 'verify-session': result = await accountProfile(api); break;
      case 'home': {
        const request = ++feedRequest;
        const page = args.more && homeFeed?.has_continuation ? await homeFeed.getContinuation() : await api.music.getHomeFeed();
        if (request === feedRequest) homeFeed = page;
        const signedIn = !!api.session.logged_in;
        result = { sections: sections(page), hasMore: !!page.has_continuation, signedIn,
          personalized: signedIn && lastMusicLoggedOut !== true }; break;
      }
      case 'radio': {
        if (!/^[A-Za-z0-9_-]{11}$/.test(args.videoId)) throw failure('LINK', 'Choose a valid YouTube recording.');
        const panel = await api.music.getUpNext(args.videoId, true);
        const seen = new Set([args.videoId]);
        result = { tracks: (panel?.contents || []).map(track).filter(t => t && !seen.has(t.id.slice(3)) && seen.add(t.id.slice(3))).slice(0, 50) }; break;
      }
      case 'explore': result = { sections: sections(await api.music.getExplore()), hasMore: false }; break;
      case 'related': {
        // YouTube Music's "Related" tab for a song: similar songs, artists and playlists.
        if (!/^[A-Za-z0-9_-]{11}$/.test(args.videoId)) throw failure('LINK', 'Choose a valid YouTube recording.');
        const page = await api.music.getRelated(args.videoId);
        result = { sections: sections({ sections: page?.contents || [] }), hasMore: false }; break;
      }
      case 'collection': {
        const page = args.kind === 'album' ? await api.music.getAlbum(args.id) : await api.music.getPlaylist(args.id);
        result = { tracks: (page.contents || []).map(track).filter(Boolean) }; break;
      }
      default: throw failure('OPERATION', 'Unsupported YouTube operation.');
    }
    native.result(id, JSON.stringify(result));
  } catch (error) { native.result(id, JSON.stringify({ error: safeError(error) })); }
};
emit('ready');
