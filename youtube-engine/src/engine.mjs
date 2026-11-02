import { Innertube, Player, Platform, Log, Constants } from 'youtubei.js/web';
import { entry, track, sections, safeError } from './mapping.mjs';
import { evaluatePlayer } from './evaluator.mjs';

Log.setLevel(Log.Level.NONE);
// HTTP is executed by Android's native transport, so the client must supply the
// Origin/User-Agent headers a browser would otherwise add automatically.
Platform.shim.server = true;
for (const method of ['log', 'info', 'debug', 'warn', 'error']) console[method] = () => {};
const native = globalThis.MusicNative;
const requests = new Map();
let serial = 0, tubePromise, homeFeed, feedRequest = 0;

function emit(type, data = {}) { native.event(type, JSON.stringify(data)); }

async function nativeFetch(input, init = {}) {
  const request = input instanceof Request ? input : new Request(input, init);
  const options = input instanceof Request ? { ...init } : init;
  const headers = Object.fromEntries(new Headers(options.headers || request.headers));
  const body = options.body == null ? (request.method === 'GET' || request.method === 'HEAD' ? '' : await request.clone().text()) : String(options.body);
  return new Promise((resolve, reject) => {
    const id = String(++serial);
    const timeout = setTimeout(() => { requests.delete(id); native.cancelHttp(id); reject(new Error('Network timeout')); }, 25000);
    requests.set(id, { resolve, reject, timeout });
    native.http(id, request.url, JSON.stringify({ method: options.method || request.method, headers, body }));
  });
}

globalThis.musicHttpResult = (id, response) => {
  const pending = requests.get(id); if (!pending) return;
  requests.delete(id); clearTimeout(pending.timeout);
  if (response.error) { pending.reject(new Error('Network request failed')); return; }
  const bytes = Uint8Array.from(atob(response.body || ''), c => c.charCodeAt(0));
  const noBody = [204, 205, 304].includes(response.status);
  pending.resolve(new Response(noBody ? null : bytes, { status: response.status, headers: response.headers || {} }));
};

// Player expressions run in a Worker without the Android bridge or account tokens.
// Chromium supplies the modern JavaScript interpreter the old adapter was missing.
Platform.shim.eval = evaluatePlayer;

const memory = new Map();
const cache = { get: async k => memory.get(k), set: async (k, v) => { memory.set(k, v); }, remove: async k => { memory.delete(k); } };
async function tube() {
  if (!tubePromise) tubePromise = (async () => {
    const locale = JSON.parse(native.locale?.() || '{}');
    const api = await Innertube.create({ fetch: nativeFetch, cache, generate_session_locally: true,
      enable_session_cache: false, retrieve_player: false, retrieve_innertube_config: false,
      lang: locale.language || 'en', location: locale.country || 'US', user_agent: locale.userAgent, device_category: 'mobile' });
    api.session.context.client.osName = 'Android';
    api.session.context.client.osVersion = locale.androidVersion || '13';
    api.session.context.client.platform = 'MOBILE';
    api.session.context.client.clientFormFactor = 'SMALL_FORM_FACTOR';
    const save = ({ credentials }) => { if (credentials) native.saveCredentials(JSON.stringify({ ...credentials, client: api.session.oauth.client_id })); };
    api.session.on('update-credentials', save);
    api.session.on('auth', data => { save(data); homeFeed = undefined; feedRequest++; emit('connected'); });
    api.session.on('auth-pending', data => emit('device-code', { code: data.user_code, url: data.verification_url, expiresIn: data.expires_in }));
    api.session.on('auth-error', error => emit('auth-error', safeError(error)));
    const saved = native.loadCredentials();
    if (saved) {
      try { await api.session.signIn(JSON.parse(saved)); }
      catch { native.clearCredentials(); emit('auth-error', { code: 'AUTH_REQUIRED', message: 'Reconnect your YouTube account.' }); }
    }
    return api;
  })().catch(error => { tubePromise = undefined; throw error; });
  return tubePromise;
}

async function search(api, query) {
  const url = (() => { try { return new URL(query); } catch { return null; } })();
  if (url) {
    if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || !['youtube.com', 'www.youtube.com', 'm.youtube.com', 'music.youtube.com', 'youtu.be', 'www.youtu.be', 'www.youtube-nocookie.com'].includes(url.hostname)) throw new Error('Unsupported YouTube link');
    const id = url.hostname.endsWith('youtu.be') ? url.pathname.slice(1) : url.searchParams.get('v') || (/^\/(shorts|embed|live)\//.test(url.pathname) ? url.pathname.split('/')[2] : null);
    if (id && /^[A-Za-z0-9_-]{11}$/.test(id)) {
      const info = await api.getBasicInfo(id);
      const b = info.basic_info;
      return [{ id: `yt:${id}`, title: b.title || 'YouTube recording', artist: b.author || b.channel?.name || 'YouTube', url: `https://www.youtube.com/watch?v=${id}`, artwork: b.thumbnail?.at(-1)?.url || '', durationMs: (b.duration || 0) * 1000, source: 'YOUTUBE' }];
    }
    const list = url.searchParams.get('list');
    if (list && /^[A-Za-z0-9_-]{10,100}$/.test(list)) return (await api.music.getPlaylist(list)).contents?.map(track).filter(Boolean) || [];
    throw new Error('Unsupported YouTube link');
  }
  const result = await api.music.search(query, { type: 'song' });
  return (result.contents || []).flatMap(s => s.contents || []).map(track).filter(Boolean);
}

async function resolve(api, id) {
  let lastError;
  // Use the same account for all requests. Fall back only between supported
  // client formats; do not substitute another recording or another audio source.
  for (const client of [...(api.session.logged_in ? ['TV'] : []), 'ANDROID', 'YTMUSIC_ANDROID', 'WEB']) {
    try {
      const info = await api.getBasicInfo(id, { client });
      if (info.playability_status?.status !== 'OK') throw new Error(info.playability_status?.reason || 'Not playable');
      if (info.basic_info?.is_live) throw new Error('Live streams unavailable');
      const formats = (info.streaming_data?.adaptive_formats || []).filter(f => f.has_audio && !f.has_video && !f.is_drm && !f.drm_families?.length);
      const candidates = [...formats].sort((a, b) => b.bitrate - a.bitrate);
      if (!candidates.length) throw new Error('No audio format available');
      for (const f of candidates.slice(0, 3)) {
        try {
          if (!api.session.player && (f.signature_cipher || f.cipher || /[?&]n=/.test(f.url || ''))) api.session.player = await Player.create(cache, nativeFetch);
          const stream = await f.decipher(api.session.player);
          const target = new URL(stream);
          if (target.protocol !== 'https:' || !target.hostname.endsWith('.googlevideo.com')) throw new Error('Invalid audio source');
          const userAgent = ['ANDROID', 'YTMUSIC_ANDROID'].includes(client) ? Constants.CLIENTS.ANDROID.USER_AGENT : api.session.context.client.userAgent;
          return { url: stream, mimeType: f.mime_type?.split(';')[0] || null, headers: { 'User-Agent': userAgent, 'Origin': 'https://www.youtube.com', 'Referer': 'https://www.youtube.com/' } };
        } catch (error) { lastError = error; }
      }
    } catch (error) { lastError = error; }
  }
  throw lastError || new Error('No playable audio format');
}

globalThis.musicCall = async (id, method, args) => {
  try {
    const api = await tube();
    let result;
    switch (method) {
      case 'search': result = { tracks: await search(api, args.query) }; break;
      case 'resolve': result = await resolve(api, args.videoId); break;
      case 'home': {
        const request = ++feedRequest;
        const page = args.more && homeFeed?.has_continuation ? await homeFeed.getContinuation() : await api.music.getHomeFeed();
        if (request === feedRequest) homeFeed = page;
        result = { sections: sections(page), hasMore: !!page.has_continuation }; break;
      }
      case 'collection': {
        const page = args.kind === 'album' ? await api.music.getAlbum(args.id) : await api.music.getPlaylist(args.id);
        result = { tracks: (page.contents || []).map(track).filter(Boolean) }; break;
      }
      case 'sign-in':
        if (api.session.logged_in) emit('connected');
        else api.session.signIn().catch(error => emit('auth-error', safeError(error)));
        result = {}; break;
      case 'sign-out':
        try { if (api.session.logged_in) await api.session.signOut(); } finally {
          api.session.logged_in = false; api.session.oauth.oauth2_tokens = undefined;
          homeFeed = undefined; feedRequest++; native.clearCredentials(); emit('signed-out');
        }
        result = {}; break;
      default: throw new Error('Unsupported operation');
    }
    native.result(id, JSON.stringify(result));
  } catch (error) { native.result(id, JSON.stringify({ error: safeError(error) })); }
};
emit('ready');
