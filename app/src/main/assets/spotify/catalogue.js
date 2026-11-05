/* Music's read-only Spotify catalogue bridge. No player commands or audio access. */
(() => {
  'use strict';
  if (location.origin !== 'https://open.spotify.com' || window.top !== window) return;
  if (window.__rtensorCatalogue) return;
  window.__rtensorCatalogue = true;
  const idPattern = /^[A-Za-z0-9]{22}$/;
  const uriPattern = /^spotify:(track|playlist|album):[A-Za-z0-9]{22}$/;
  const operations = new Map();
  let provider;
  let definitionWork;
  const pause = () => document.querySelectorAll('audio,video').forEach(media => { try { media.pause(); } catch {} });
  document.addEventListener('playing', pause, true);
  pause();

  function discover() {
    const visited = new Set(), todo = [];
    for (const el of [document.getElementById('main'), document.getElementById('root'), document.body].filter(Boolean)) {
      for (const node of [el, ...el.querySelectorAll('*')].slice(0, 1200)) {
        for (const key of Object.keys(node)) if (key.startsWith('__reactContainer$')) todo.push(node[key]);
      }
    }
    let cursor = 0;
    while (cursor < todo.length && visited.size < 20000) {
      const fiber = todo[cursor++];
      if (!fiber || visited.has(fiber)) continue;
      visited.add(fiber);
      const value = fiber.memoizedProps?.value;
      if (value && typeof value.getRegistry === 'function' && typeof value.getGraphQLLoader === 'function') return value;
      if (fiber.child) todo.push(fiber.child);
      if (fiber.sibling) todo.push(fiber.sibling);
    }
    return null;
  }
  async function platform() {
    if (provider) return provider;
    const until = Date.now() + 22000;
    do {
      const found = discover();
      if (found) { provider = found; return found; }
      await new Promise(resolve => setTimeout(resolve, 300));
    } while (Date.now() < until);
    throw Error('PAGE_CHANGED');
  }
  async function service(name) {
    const registry = (await platform()).getRegistry();
    for (const key of registry._map.keys()) if (key?.description === name) return registry.resolve(key);
    throw Error('PAGE_CHANGED');
  }
  async function auth() {
    const token = await (await service('TokenProvider')).getToken();
    if (!token?.accessToken || token.isAnonymous !== false) throw Error('AUTH_REQUIRED');
    return token;
  }
  const path = (object, keys) => keys.split('.').reduce((value, key) => value?.[key], object);
  function art(object) {
    const sources = [path(object, 'coverArt.sources'), path(object, 'albumOfTrack.coverArt.sources'),
      path(object, 'images.items.0.sources'), path(object, 'images.sources'), object?.images].flat().filter(Boolean);
    return sources.filter(image => {
      try { const url = new URL(image.url); return url.protocol === 'https:' && !url.username && !url.password &&
        (!url.port || url.port === '443') && /\.(?:scdn\.co|spotifycdn\.com)$/.test(url.hostname); } catch { return false; }
    })
      .sort((a, b) => (b.width || 0) - (a.width || 0))[0]?.url || '';
  }
  function item(object) {
    if (!object || typeof object !== 'object' || !uriPattern.test(object.uri || '')) return null;
    const [, kind, id] = object.uri.split(':');
    const title = typeof object.name === 'string' ? object.name : object.name?.text || object.title?.text || object.title;
    if (typeof title !== 'string' || !title.trim()) return null;
    const artists = object.artists?.items || object.artists || [];
    const artist = Array.isArray(artists) ? artists.map(a => a.profile?.name || a.name || '').filter(Boolean).join(', ') : '';
    return {id, kind, title: title.slice(0, 512), artist: artist.slice(0, 512), artwork: art(object),
      durationMs: object.duration?.totalMilliseconds || object.duration_ms || object.trackDuration?.totalMilliseconds || 0};
  }
  function collect(root, wanted) {
    const out = [], seen = new Set(), todo = [root];
    let cursor = 0;
    while (cursor < todo.length && cursor < 12000 && out.length < 80) {
      const node = todo[cursor++];
      if (!node || typeof node !== 'object') continue;
      const parsed = item(node);
      if (parsed && wanted.includes(parsed.kind) && !seen.has(parsed.id)) {
        seen.add(parsed.id); out.push(parsed);
        // An album card's nested artist/preview must not become another feed item.
        continue;
      }
      for (const value of Object.values(node)) if (value && typeof value === 'object') {
        if (Array.isArray(value)) todo.push(...value); else todo.push(value);
      }
    }
    return out;
  }
  async function loadDefinitions() {
    let declared = [];
    try {
      const text = document.getElementById('__CDN_FILE_URLS__')?.textContent || '';
      declared = Object.values(JSON.parse(text.trim().startsWith('{') ? text : atob(text)));
    } catch {}
    const urls = [...new Set([...Array.from(document.scripts, s => s.src),
      ...performance.getEntriesByType('resource').map(r => r.name),
      ...declared.filter(url => typeof url === 'string' && /xpui-routes-search\./.test(url))])]
      .filter(url => /^https:\/\/open\.spotifycdn\.com\/cdn\/build\/web-player\/[^?#]+\.js(?:\?.*)?$/.test(url)).slice(0, 24);
    let cursor = 0, total = 0;
    const abort = new AbortController(), deadline = setTimeout(() => abort.abort(), 12000);
    try { await Promise.all([0, 1, 2].map(async () => {
      while (cursor < urls.length && !abort.signal.aborted) {
        const url = urls[cursor++];
        let text;
        try {
          const response = await fetch(url, {credentials: 'omit', signal: abort.signal});
          if (!response.ok || Number(response.headers.get('content-length') || 0) > 5000000) continue;
          text = await response.text();
        } catch { continue; }
        total += text.length;
        if (text.length > 5000000 || total > 18000000) break;
        // Current public web bundles declare persisted operations as constructor arguments.
        for (const match of text.matchAll(/\(\s*["']([A-Za-z][A-Za-z0-9_]*)["']\s*,\s*["']query["']\s*,\s*["']([a-f0-9]{64})["']/g)) {
          operations.set(match[1], match[2]);
        }
      }
    })); } finally { clearTimeout(deadline); }
    if (!operations.size) throw Error('PAGE_CHANGED');
  }
  async function definitions() {
    if (!definitionWork) definitionWork = loadDefinitions().catch(error => { definitionWork = null; throw error; });
    return definitionWork;
  }
  async function query(name, variables) {
    await auth();
    if (!operations.has(name)) await definitions();
    const hash = operations.get(name);
    if (!hash) { definitionWork = null; throw Error('PAGE_CHANGED'); }
    const loader = (await platform()).getGraphQLLoader();
    const response = await loader({name, operation: 'query', sha256Hash: hash, value: null}, variables);
    if (response?.errors?.length) throw Error('CATALOGUE_UNAVAILABLE');
    return response?.data || response;
  }
  async function collection(uri, offset = 0) {
    if (!uriPattern.test(uri)) throw Error('BAD_REQUEST');
    const kind = uri.split(':')[1];
    const name = kind === 'playlist' ? 'fetchPlaylistContents' : kind === 'album' ? 'getAlbum' : 'getTrack';
    const data = await query(name, {uri, offset, limit: 40, locale: '', includeEpisodeContentRatingsV2: true,
      enableWatchFeedEntrypoint: false});
    const source = kind === 'playlist' ? data?.playlistV2?.content : kind === 'album' ? data?.albumUnion?.tracksV2 : null;
    const items = collect(kind === 'track' ? data?.trackUnion : source?.items, ['track']).slice(0, 40);
    if (!items.length && !source) throw Error('CATALOGUE_UNAVAILABLE');
    const consumed = Array.isArray(source?.items) ? source.items.length : 0;
    return {items, nextOffset: consumed > 0 && Number(source?.totalCount || 0) > offset + consumed ? offset + consumed : -1};
  }
  async function request(operation, args) {
    if (operation === 'profile') {
      await auth();
      const user = await (await service('UserAPI')).getUser();
      const name = user?.displayName || user?.display_name || user?.username;
      if (typeof name !== 'string' || !name) throw Error('AUTH_REQUIRED');
      return {name: name.slice(0, 120)};
    }
    if (operation === 'home') {
      const data = await query('home', {timeZone: args.timeZone || 'UTC', sp_t: '', facet: null,
        sectionItemsLimit: 12, homeEndUserIntegration: 'INTEGRATION_WEB_PLAYER',
        includeEpisodeContentRatingsV2: true, includeHomeChapterVideoCards: false});
      const rows = data?.home?.sectionContainer?.sections?.items || [];
      const sections = rows.filter(row => !/promotion|sponsored|advertisement/i.test(row.data?.__typename || row.__typename || '')).slice(0, 20).map(row => ({title: row.data?.title?.text || row.data?.title?.transformedLabel || row.title?.text || 'Spotify picks',
        items: collect(row, ['playlist', 'album', 'track']).slice(0, 12)})).filter(row => row.items.length);
      if (!sections.length) throw Error('CATALOGUE_UNAVAILABLE');
      return {sections};
    }
    if (operation === 'collection') return collection(args.uri, args.offset || 0);
    if (operation === 'search') {
      if (typeof args.query !== 'string' || !args.query.trim() || args.query.length > 400) throw Error('BAD_REQUEST');
      const data = await query('searchTracks', {searchTerm: args.query, offset: 0, limit: 10,
        numberOfTopResults: 5, includeAudiobooks: false, includeArtistHasConcertsField: false, includePreReleases: false});
      return {items: collect(data, ['track']).slice(0, 10)};
    }
    if (operation === 'radio') {
      if (!idPattern.test(args.id || '')) throw Error('BAD_REQUEST');
      const token = await auth();
      const response = await fetch(`https://spclient.wg.spotify.com/inspiredby-mix/v2/seed_to_playlist/spotify:track:${args.id}?response-format=json`,
        {headers: {Authorization: `Bearer ${token.accessToken}`}});
      if (!response.ok) throw Error(response.status === 401 ? 'AUTH_REQUIRED' : 'CATALOGUE_UNAVAILABLE');
      const data = await response.json();
      const uri = data?.mediaItems?.find(value => /^spotify:playlist:[A-Za-z0-9]{22}$/.test(value.uri || ''))?.uri;
      if (!uri) throw Error('NO_RADIO');
      return collection(uri);
    }
    throw Error('BAD_REQUEST');
  }
  window.addEventListener('message', event => {
    // Native postWebMessage targets this origin but can have an empty sender origin.
    // Other frames cannot provide a null/window source and a trusted native event.
    if (event.isTrusted !== true || !['', 'null', location.origin].includes(event.origin) ||
        (event.source !== null && event.source !== window) || event.data !== 'rtensor-spotify-catalogue' || !event.ports?.[0]) return;
    const port = event.ports[0];
    port.onmessage = async event => {
      let input;
      try {
        if (typeof event.data !== 'string' || event.data.length > 16384) return;
        input = JSON.parse(event.data);
        if (!/^[a-f0-9-]{36}$/.test(input.id || '')) return;
        const result = await request(input.operation, input.args || {});
        port.postMessage(JSON.stringify({id: input.id, ok: true, result}));
      } catch (error) {
        // Never relay provider exception text, URLs, cookies or bearer tokens.
        const code = ['AUTH_REQUIRED', 'PAGE_CHANGED', 'BAD_REQUEST', 'NO_RADIO'].includes(error.message) ? error.message : 'CATALOGUE_UNAVAILABLE';
        if (input?.id) port.postMessage(JSON.stringify({id: input.id, ok: false, error: code}));
      }
    };
    port.start?.();
  });
})();
