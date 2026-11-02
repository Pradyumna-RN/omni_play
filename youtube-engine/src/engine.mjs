import { Innertube, Player, Platform, Log, Constants, ClientType } from 'youtubei.js/web';
import { track, sections, safeError, largestArtwork } from './mapping.mjs';
import { evaluatePlayer } from './evaluator.mjs';
import { nativeTransport } from './transport.mjs';
import { readYtConfig, playerFailure } from './web-config.mjs';
import { proofBindings, delegatedAccount, contentBoundGvs as readsContentBinding } from './proof-bindings.mjs';

Log.setLevel(Log.Level.NONE);
Platform.shim.server = true;
Platform.shim.eval = evaluatePlayer;
for (const method of ['log', 'info', 'debug', 'warn', 'error']) console[method] = () => {};
const native = globalThis.MusicNative;
const nativeFetch = nativeTransport(native, 'musicHttpResult');
let tubePromise, homeFeed, feedRequest = 0, playerPromise, playerScript = '', challengePromise, challengeExpires = 0, dataSyncId = '';
let contentBoundGvs = { YTMUSIC: false, WEB: false };
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
async function playbackProof(api, videoId, client) {
  const bindings = proofBindings({ client, videoId, visitorData: api.session.context.client.visitorData,
    dataSyncId, authenticated: api.session.logged_in, contentBoundGvs: contentBoundGvs[client] });
  // Use the live WEB attestation endpoint. The old WAA /Create discovery endpoint
  // and WEB_REMIX att/get are not used. Only this challenge reaches the sandbox.
  if (!challengePromise || Date.now() >= challengeExpires) challengePromise = api.actions.execute('/att/get', { engagementType: 'ENGAGEMENT_TYPE_UNBOUND', client: 'WEB' }).then(response => {
    const c = response.data?.bgChallenge;
    if (!c?.program || !c?.globalName) throw failure('VERIFICATION_REQUIRED', 'YouTube did not provide a playback verification challenge.', 'WEB att/get: missing challenge');
    challengeExpires = Date.now() + 240000;
    return { program: c.program, globalName: c.globalName, interpreterHash: c.interpreterHash,
      interpreterUrl: c.interpreterUrl, interpreterJavascript: c.interpreterJavascript };
  }).catch(error => { challengePromise = undefined; throw error; });
  const challenge = await challengePromise;

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
async function bootstrap(url) {
  const response = await nativeFetch(url);
  if (!response.ok) throw failure('BOOTSTRAP_HTTP', `YouTube could not open its web session (HTTP ${response.status}).`, `web session: HTTP ${response.status}`);
  return readYtConfig(await response.text());
}
async function accountProfile(api) {
  if (!api.session.logged_in) throw failure('AUTH_REQUIRED', 'Sign in to YouTube in Settings.');
  // getInfo() without true uses the TV client in YouTube.js. Always request WEB.
  const accounts = await api.account.getInfo(true);
  const selected = accounts.find(a => a.is_selected && !a.is_disabled) || (accounts.length === 1 && !accounts[0].is_disabled ? accounts[0] : null);
  const name = selected?.account_name?.toString();
  if (!name) throw failure('AUTH_REQUIRED', 'YouTube did not confirm this account. Finish sign-in and retry.', 'WEB account/accounts_list: no usable account');
  return { name };
}
async function tube() {
  if (!tubePromise) tubePromise = (async () => {
    const locale = JSON.parse(native.locale?.() || '{}');
    const saved = savedSession();
    const accountQuery = saved ? `?authuser=${saved.authUser || 0}` : '';
    const config = await bootstrap('https://music.youtube.com/' + accountQuery);
    const musicContext = config.INNERTUBE_CONTEXT;
    const visitorData = musicContext?.client?.visitorData || config.VISITOR_DATA || saved?.visitorData;
    if (!visitorData) throw failure('WEB_SESSION', 'YouTube did not return a web session. Open sign-in in Settings, or retry.', 'WEB_REMIX bootstrap: missing visitor data');
    const version = config.INNERTUBE_CLIENT_VERSION || musicContext?.client?.clientVersion || saved?.clientVersion;
    if (version) Constants.CLIENTS.YTMUSIC.VERSION = version;
    const webConfig = await bootstrap('https://www.youtube.com/' + accountQuery).catch(() => ({}));
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
    api.session.context.client.visitorData = visitorData;
    // YouTube.js assumes WEB is the base context: its explicit WEB option does
    // not reset a MUSIC base context. Music calls select YTMUSIC themselves.
    api.session.context.client.clientName = Constants.CLIENTS.WEB.NAME;
    api.session.context.client.clientVersion = webVersion || Constants.CLIENTS.WEB.VERSION;
    api.session.context.client.userAgent = locale.userAgent;
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
async function ensurePlayer(api) {
  if (!playerPromise) playerPromise = Player.create(cache, async (input, init) => {
    const url = String(input instanceof Request ? input.url : input);
    const target = playerScript && /\/s\/player\/[^/]+\/player_es6\.vflset\/.*\/base\.js/.test(url) ? playerScript : input;
    return nativeFetch(target, init);
  }, undefined, playerScript.match(/\/s\/player\/([^/]+)\//)?.[1]).then(player => (api.session.player = player)).catch(error => { playerPromise = undefined; throw error; });
  return playerPromise;
}
function normalizePlayerError(client, error) {
  if (error.info?.status) return playerFailure(client, { playability_status: error.info });
  return error;
}
async function streamFromInfo(api, id, client, info, tokens) {
  if (info.playability_status?.status !== 'OK') throw playerFailure(client, info);
  if (info.basic_info?.is_live) throw failure('LIVE_UNSUPPORTED', 'Live recordings are not supported in this build.', `${client}: live stream`);
  const formats = (info.streaming_data?.adaptive_formats || []).filter(f => f.has_audio && !f.has_video && !f.is_drm && !f.drm_families?.length && (f.url || f.signature_cipher || f.cipher));
  if (!formats.length) {
    const sabr = !!info.streaming_data?.server_abr_streaming_url;
    throw failure(sabr ? 'SABR_UNSUPPORTED' : 'NO_AUDIO', sabr ? 'YouTube returned SABR-only audio. This build cannot play that transport yet.' : 'YouTube did not provide an audio stream for this recording.', `${client}: ${sabr ? 'SABR_ONLY' : 'NO_AUDIO_FORMATS'}`);
  }
  let lastError;
  for (const format of [...formats].sort((a, b) => b.bitrate - a.bitrate).slice(0, 2)) {
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
      const headers = { 'User-Agent': api.session.context.client.userAgent, 'Origin': 'https://www.youtube.com', 'Referer': 'https://www.youtube.com/' };
      const probe = await nativeFetch(target, { headers: { ...headers, Range: 'bytes=0-1' } });
      if (!probe.ok) throw failure(`MEDIA_HTTP_${probe.status}`, `YouTube rejected the audio stream (HTTP ${probe.status}). Retry to request a fresh stream.`, `${client}: media probe HTTP ${probe.status}`);
      const type = (probe.headers.get('content-type') || '').split(';')[0];
      if (!['audio/mp4', 'audio/webm', 'audio/mpeg', 'video/mp4', 'video/webm', 'application/octet-stream'].includes(type) || !(await probe.arrayBuffer()).byteLength) throw failure('MEDIA_RESPONSE', 'YouTube returned a page instead of playable audio.', `${client}: media probe invalid content`);
      return { url: target.href, mimeType: format.mime_type?.split(';')[0] || null, headers };
    } catch (error) { lastError = error; }
  }
  throw lastError;
}
async function resolve(api, id) {
  if (!/^[A-Za-z0-9_-]{11}$/.test(id)) throw failure('LINK', 'Choose a valid YouTube recording.');
  // STS must accompany the player request. Direct formats can still work if the player script is unavailable.
  await ensurePlayer(api).catch(() => {});
  const errors = []; let tokens;
  for (const verified of [false, true]) {
    if (verified) {
      const requiresAccount = errors.some(e => e.code === 'AUTH_REQUIRED');
      const restricted = errors.some(e => /private|copyright|not available in your country|age.restrict|premium members|members.only/i.test(e.message));
      if (requiresAccount || restricted || errors.every(e => e.code === 'SABR_UNSUPPORTED')) break;
    }
    // Ordinary web clients only. Same recording, same session and same account.
    for (const client of ['YTMUSIC', 'WEB']) {
      try {
        tokens = verified ? await playbackProof(api, id, client) : undefined;
        const info = await api.getBasicInfo(id, { client, ...(tokens ? { po_token: tokens.playerToken } : {}) });
        return await streamFromInfo(api, id, client, info, tokens);
      } catch (error) { errors.push(normalizePlayerError(client, error)); }
    }
  }
  const useful = errors.find(e => e.code === 'AUTH_REQUIRED') || errors.find(e => e.code?.startsWith('MEDIA_HTTP')) || errors.find(e => e.code === 'PLAYER_REJECTED') || errors.find(e => e.code === 'SABR_UNSUPPORTED') || errors.at(-1);
  const result = safeError(useful);
  throw failure(result.code, result.message, errors.map(e => safeError(e).diagnostic || safeError(e).code).join(' | ').slice(0, 800));
}

globalThis.musicCall = async (id, method, args) => {
  try {
    const api = await tube(); let result;
    switch (method) {
      case 'search': result = { tracks: await search(api, args.query) }; break;
      case 'resolve': result = await resolve(api, args.videoId); break;
      case 'verify-session': result = await accountProfile(api); break;
      case 'home': {
        const request = ++feedRequest;
        const page = args.more && homeFeed?.has_continuation ? await homeFeed.getContinuation() : await api.music.getHomeFeed();
        if (request === feedRequest) homeFeed = page;
        result = { sections: sections(page), hasMore: !!page.has_continuation }; break;
      }
      case 'explore': result = { sections: sections(await api.music.getExplore()), hasMore: false }; break;
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
