// Real Chromium + the exact bundled engine, with synthetic provider HTTP responses.
// No live Google account, tokens, or YouTube connection is used by this test.
const { chromium } = require('playwright-core');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../app/src/main/assets/engine');
const watch = id => ({ watchEndpoint: { videoId: id, watchEndpointMusicSupportedConfigs: { watchEndpointMusicConfig: { musicVideoType: 'MUSIC_VIDEO_TYPE_ATV' } } } });
function song(id, title) {
  return { musicResponsiveListItemRenderer: { navigationEndpoint: watch(id), playlistItemData: { videoId: id },
    flexColumns: [ { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: title, navigationEndpoint: watch(id) }] } } },
      { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: 'Test artist', navigationEndpoint: { browseEndpoint: { browseId: 'UCsynthetic' } } }, { text: ' • ' }, { text: '3:00' }] } } } ] } };
}
const shelf = { musicShelfRenderer: { title: { simpleText: 'Songs' }, contents: [song('aaaaaaaaaaa', 'Zebra'), song('bbbbbbbbbbb', 'Apple')] } };
const search = { contents: { tabbedSearchResultsRenderer: { tabs: [{ tabRenderer: { selected: true, content: { sectionListRenderer: { contents: [shelf] } } } }] } } };
const card = (id, title) => ({ musicTwoRowItemRenderer: { title: { simpleText: title }, subtitle: { simpleText: 'Song' }, navigationEndpoint: watch(id), thumbnailRenderer: { musicThumbnailRenderer: { thumbnail: { thumbnails: [] } } } } });
const carousel = (title, contents) => ({ musicCarouselShelfRenderer: { header: { musicCarouselShelfBasicHeaderRenderer: { title: { simpleText: title } } }, contents } });
const home = { contents: { singleColumnBrowseResultsRenderer: { tabs: [{ tabRenderer: { selected: true, content: { sectionListRenderer: { contents: [carousel('Provider second alphabetically', [card('aaaaaaaaaaa', 'Zebra'), card('bbbbbbbbbbb', 'Apple'), card('aaaaaaaaaaa', 'Zebra')]), carousel('Provider first alphabetically', [card('bbbbbbbbbbb', 'Apple')])] } } } }] } } };
const player = { playabilityStatus: { status: 'OK' }, videoDetails: { videoId: 'jNQXAC9IVRw', title: 'Synthetic recording', author: 'Test artist', lengthSeconds: '19', channelId: 'UCsynthetic', thumbnail: { thumbnails: [] }, isLiveContent: false, viewCount: '0' },
  streamingData: { adaptiveFormats: [{ itag: 140, mimeType: 'audio/mp4; codecs="mp4a.40.2"', bitrate: 128000, audioQuality: 'AUDIO_QUALITY_MEDIUM', audioSampleRate: '44100', audioChannels: 2, approxDurationMs: '19000', contentLength: '1024', lastModified: '12345', url: 'https://r1-synthetic.googlevideo.com/videoplayback?synthetic=1' }] } };

(async () => {
  const server = http.createServer((req, res) => {
    const name = req.url === '/evaluator.mjs' ? path.resolve(__dirname, '../src/evaluator.mjs') : path.join(root, req.url === '/' ? 'index.html' : req.url.slice(1));
    if (!fs.existsSync(name) || !fs.statSync(name).isFile()) { res.writeHead(404); res.end(); return; }
    res.setHeader('Content-Type', name.endsWith('.html') ? 'text/html' : 'application/javascript'); res.end(fs.readFileSync(name));
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const browser = await chromium.launch({ executablePath: process.env.BROWSER_EXECUTABLE || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage(); let rejectSearch = false, tokenPolls = 0, refreshed = false; const calls = [];
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.exposeFunction('httpNative', async (id, url, options) => {
      const o = JSON.parse(options), u = new URL(url); calls.push({ path: u.pathname, headers: o.headers, method: o.method });
      let body, status = 200;
      if (u.pathname.endsWith('/search')) { body = search; if (rejectSearch) status = 403; }
      else if (u.pathname.endsWith('/browse')) body = home;
      else if (u.pathname.endsWith('/player')) body = player;
      else if (u.pathname === '/tv') body = '<script id="base-js" src="/s/tv/synthetic/base.js"></script>';
      else if (u.pathname === '/s/tv/synthetic/base.js') body = 'clientId:"synthetic-client",clientSecret:"synthetic-secret"';
      else if (u.pathname === '/o/oauth2/device/code') body = { device_code: 'SYNTHETIC_DEVICE', user_code: 'TEST-CODE', verification_url: 'https://www.google.com/device', expires_in: 60, interval: 0.1 };
      else if (u.pathname === '/o/oauth2/token') {
        if (JSON.parse(o.body).grant_type === 'refresh_token') { refreshed = true; body = { access_token: 'synthetic-refreshed', expires_in: 3600 }; }
        else body = ++tokenPolls < 2 ? { error: 'authorization_pending' } : { access_token: 'synthetic-token', refresh_token: 'synthetic-refresh', expires_in: 3600, token_type: 'Bearer' };
      }
      else if (u.pathname === '/o/oauth2/revoke') body = {};
      else throw new Error('Unexpected provider path: ' + u.pathname);
      const encoded = Buffer.from(typeof body === 'string' ? body : JSON.stringify(body)).toString('base64');
      await page.evaluate(({ id, payload }) => globalThis.musicHttpResult(id, payload), { id, payload: { status, headers: { 'content-type': 'application/json' }, body: encoded } });
    });
    await page.addInitScript(() => {
      window.__results = {}; window.__events = []; window.__saved = '';
      window.MusicNative = { http: (...args) => window.httpNative(...args), cancelHttp() {}, loadCredentials: () => window.__saved,
        locale: () => JSON.stringify({ language: 'en', country: 'IN', userAgent: 'synthetic-native-agent', androidVersion: '13' }),
        saveCredentials: v => { window.__saved = v; }, clearCredentials: () => { window.__saved = ''; },
        result: (id, json) => { window.__results[id] = JSON.parse(json); }, event: (type, json) => { window.__events.push({ type, ...JSON.parse(json) }); } };
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(() => window.__events.some(e => e.type === 'ready'));
    let serial = 0;
    async function rpc(method, args = {}) {
      const id = String(++serial); await page.evaluate(({ id, method, args }) => { window.musicCall(id, method, args); }, { id, method, args });
      try { await page.waitForFunction(id => Object.hasOwn(window.__results, id), id, { timeout: 15000 }); }
      catch (error) { console.error(JSON.stringify({ paths: calls.map(c => c.path), errors })); throw error; }
      return page.evaluate(id => window.__results[id], id);
    }
    const found = await rpc('search', { query: 'test artist' });
    assert.deepEqual(found.tracks?.map(t => t.id), ['yt:aaaaaaaaaaa', 'yt:bbbbbbbbbbb'], JSON.stringify(found));
    assert(calls.some(c => c.path.endsWith('/search') && c.headers.origin === 'https://www.youtube.com' && c.headers['user-agent'] === 'synthetic-native-agent'));
    const audio = await rpc('resolve', { videoId: 'jNQXAC9IVRw' });
    assert.equal(audio.mimeType, 'audio/mp4', JSON.stringify(audio)); assert(audio.url.startsWith('https://r1-synthetic.googlevideo.com/'));
    const feed = await rpc('home');
    assert.deepEqual(feed.sections?.map(s => s.title), ['Provider second alphabetically', 'Provider first alphabetically'], JSON.stringify(feed));
    assert.deepEqual(feed.sections[0].items.map(i => i.id), ['yt:aaaaaaaaaaa', 'yt:bbbbbbbbbbb', 'yt:aaaaaaaaaaa']);
    await rpc('sign-in'); await page.waitForFunction(() => window.__events.some(e => e.type === 'device-code'));
    assert.equal(await page.evaluate(() => window.__events.find(e => e.type === 'device-code').code), 'TEST-CODE');
    await page.waitForFunction(() => window.__events.some(e => e.type === 'connected'));
    assert(await page.evaluate(() => !!JSON.parse(window.__saved).refresh_token && !!JSON.parse(window.__saved).client));
    await rpc('home'); assert(calls.some(c => c.path.endsWith('/browse') && c.headers.authorization?.startsWith('Bearer ')));
    rejectSearch = true; assert.equal((await rpc('search', { query: 'blocked' })).error?.code, 'HTTP_403');
    const sandbox = await page.evaluate(async () => (await import('/evaluator.mjs')).evaluatePlayer({ output: 'return {signature:"decoded",bridge:typeof MusicNative,dom:typeof document};' }));
    assert.deepEqual(sandbox, { signature: 'decoded', bridge: 'undefined', dom: 'undefined' });
    const saved = await page.evaluate(() => { const t = JSON.parse(window.__saved); t.expiry_date = new Date(0).toISOString(); return JSON.stringify(t); });
    await page.reload(); await page.waitForFunction(() => window.__events.some(e => e.type === 'ready'));
    await page.evaluate(value => { window.__saved = value; }, saved);
    await rpc('home'); assert(refreshed);
    assert(await page.evaluate(() => new Date(JSON.parse(window.__saved).expiry_date).getTime() > Date.now()));
    await rpc('sign-out'); assert.equal(await page.evaluate(() => window.__saved), '');
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ passed: ['bundled engine startup', 'actual YouTube.js search parser', 'native audio URL resolution', 'provider feed and duplicate order', 'device-code sign-in events', 'token persistence callback', 'authenticated feed headers', 'safe HTTP diagnostics', 'isolated player worker', 'restored session refreshes expired tokens', 'sign-out clears session'], requests: calls.length, liveProvider: false }, null, 2));
  } finally { await browser.close(); await new Promise(r => server.close(r)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
