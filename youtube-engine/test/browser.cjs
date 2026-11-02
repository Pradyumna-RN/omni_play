// Exact bundled client in real Chromium. Provider HTTP and proofs are synthetic fixtures.
// No live Google session, attestation service, or successful live playback is implied.
const { chromium } = require('playwright-core');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../app/src/main/assets/engine');
const watch = id => ({ watchEndpoint: { videoId: id, watchEndpointMusicSupportedConfigs: { watchEndpointMusicConfig: { musicVideoType: 'MUSIC_VIDEO_TYPE_ATV' } } } });
function song(id, title) {
  return { musicResponsiveListItemRenderer: { navigationEndpoint: watch(id), playlistItemData: { videoId: id },
    thumbnail: { musicThumbnailRenderer: { thumbnail: { thumbnails: [{ url: 'https://i.ytimg.com/test-large.jpg', width: 600, height: 600 }, { url: 'https://i.ytimg.com/test-small.jpg', width: 60, height: 60 }] } } },
    flexColumns: [{ musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: title, navigationEndpoint: watch(id) }] } } },
      { musicResponsiveListItemFlexColumnRenderer: { text: { runs: [{ text: 'Test artist', navigationEndpoint: { browseEndpoint: { browseId: 'UCsynthetic' } } }, { text: ' • ' }, { text: '3:00' }] } } }] } };
}
const shelf = { musicShelfRenderer: { title: { simpleText: 'Songs' }, contents: [song('aaaaaaaaaaa', 'Zebra'), song('bbbbbbbbbbb', 'Apple')] } };
const search = { contents: { tabbedSearchResultsRenderer: { tabs: [{ tabRenderer: { selected: true, content: { sectionListRenderer: { contents: [shelf] } } } }] } } };
const card = (id, title) => ({ musicTwoRowItemRenderer: { title: { simpleText: title }, subtitle: { simpleText: 'Test artist' }, navigationEndpoint: watch(id), thumbnailRenderer: { musicThumbnailRenderer: { thumbnail: { thumbnails: [] } } } } });
const carousel = (title, contents) => ({ musicCarouselShelfRenderer: { header: { musicCarouselShelfBasicHeaderRenderer: { title: { simpleText: title } } }, contents } });
const feed = shelves => ({ contents: { singleColumnBrowseResultsRenderer: { tabs: [{ tabRenderer: { selected: true, content: { sectionListRenderer: { contents: shelves } } } }] } } });
const home = feed([carousel('Provider second alphabetically', [card('aaaaaaaaaaa', 'Zebra'), card('bbbbbbbbbbb', 'Apple'), card('aaaaaaaaaaa', 'Zebra')]), carousel('Provider first alphabetically', [card('bbbbbbbbbbb', 'Apple')])]);
const explore = feed([carousel('New releases from provider', [card('bbbbbbbbbbb', 'Apple')])]);
const accounts = { actions: [{ getMultiPageMenuAction: { menu: { multiPageMenuRenderer: { sections: [{ accountSectionListRenderer: { contents: [{ accountItemSectionRenderer: { contents: [{ accountItem: { accountName: { simpleText: 'Fixture channel' }, isSelected: true, hasChannel: true, serviceEndpoint: { selectActiveIdentityEndpoint: {} } } }] } }] } }] } } } }] };
const playerScript = `(function(g){var signatureTimestampFn=function(){return {signatureTimestamp:12345}};var transform=function(a,b="signature",c=""){a=new globalThis.URL(a);a.searchParams.set("alr","yes");if(c)a.searchParams.set(b,c.split("").reverse().join(""));if(a.searchParams.has("n"))a.searchParams.set("n",a.searchParams.get("n").split("").reverse().join(""));return Object.create({get:function(k){return a.searchParams.get(k)}})};})(globalThis);`;
const basePlayer = { playabilityStatus: { status: 'OK' }, videoDetails: { videoId: 'jNQXAC9IVRw', title: 'Synthetic recording', author: 'Test artist', lengthSeconds: '19', channelId: 'UCsynthetic', thumbnail: { thumbnails: [] }, isLiveContent: false, viewCount: '0' },
  streamingData: { adaptiveFormats: [{ itag: 140, mimeType: 'audio/mp4; codecs="mp4a.40.2"', bitrate: 128000, audioQuality: 'AUDIO_QUALITY_MEDIUM', audioSampleRate: '44100', audioChannels: 2, approxDurationMs: '19000', contentLength: '1024', lastModified: '12345', url: 'https://r1-synthetic.googlevideo.com/videoplayback?fixture=1' }] } };
const config = { VISITOR_DATA: 'fixture-visitor', INNERTUBE_CLIENT_VERSION: '1.20261102.01.00', PLAYER_JS_URL: '/s/player/fixture/player_ias.vflset/en_US/base.js', INNERTUBE_CONTEXT: { client: { visitorData: 'fixture-visitor', clientName: 'WEB_REMIX', clientVersion: '1.20261102.01.00', osName: 'Android', osVersion: '15', platform: 'MOBILE' } } };
(async () => {
  const server = http.createServer((req, res) => {
    const name = req.url === '/evaluator.mjs' ? path.resolve(__dirname, '../src/evaluator.mjs') : path.join(root, req.url === '/' ? 'index.html' : req.url.slice(1));
    if (!fs.existsSync(name) || !fs.statSync(name).isFile()) { res.writeHead(404); res.end(); return; }
    res.setHeader('Content-Type', name.endsWith('.html') ? 'text/html' : 'application/javascript'); res.end(fs.readFileSync(name));
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const browser = await chromium.launch({ executablePath: process.env.BROWSER_EXECUTABLE || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage(); let rejectSearch = false, playerMode = 'direct', mediaStatus = 206, proofFailure = false; const calls = [], proofCalls = [], errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.exposeFunction('httpNative', async (id, url, options) => {
      const o = JSON.parse(options), u = new URL(url), bodyIn = o.body ? JSON.parse(o.body) : null;
      calls.push({ path: u.pathname, host: u.hostname, query: u.searchParams.toString(), headers: o.headers, method: o.method, body: bodyIn });
      let body, status = 200, type = 'application/json';
      if (u.pathname === '/') { body = `<script>ytcfg.set(${JSON.stringify(u.hostname === 'music.youtube.com' ? config : { INNERTUBE_CLIENT_VERSION: '2.20261102.00.00' })});</script>`; type = 'text/html'; }
      else if (u.pathname.endsWith('/search')) { body = search; if (rejectSearch) status = 403; }
      else if (u.pathname.endsWith('/browse')) body = bodyIn?.browseId === 'FEmusic_explore' ? explore : home;
      else if (u.pathname.endsWith('/player')) {
        body = structuredClone(basePlayer);
        if (playerMode === 'rejected') body = { playabilityStatus: { status: 'ERROR', reason: 'This client is unsupported.' } };
        if (playerMode === 'sabr') body.streamingData = { serverAbrStreamingUrl: 'https://r1-synthetic.googlevideo.com/videoplayback?sabr=1' };
        if (playerMode === 'cipher') { const f = body.streamingData.adaptiveFormats[0]; f.signatureCipher = new URLSearchParams({ url: f.url + '&n=abc', sp: 'sig', s: 'xyz' }).toString(); delete f.url; }
      }
      else if (u.pathname.endsWith('/account/accounts_list')) body = accounts;
      else if (u.pathname.endsWith('/att/get')) body = { bgChallenge: { program: 'fixture-program', globalName: 'fixtureVM', interpreterJavascript: { privateDoNotAccessOrElseSafeScriptWrappedValue: 'fixture-only' } } };
      else if (u.pathname.endsWith('/base.js')) { body = playerScript; type = 'application/javascript'; }
      else if (u.pathname === '/videoplayback') { status = mediaStatus; body = Buffer.from([0, 1]); type = 'audio/mp4'; }
      else throw new Error('Unexpected provider path: ' + u.pathname);
      const encoded = Buffer.isBuffer(body) ? body.toString('base64') : Buffer.from(typeof body === 'string' ? body : JSON.stringify(body)).toString('base64');
      await page.evaluate(({ id, payload }) => globalThis.musicHttpResult(id, payload), { id, payload: { status, headers: { 'content-type': type }, body: encoded } });
    });
    await page.exposeFunction('proofNative', async (id, args) => {
      proofCalls.push(JSON.parse(args));
      await page.evaluate(({ id, payload }) => globalThis.musicProofResult(id, payload), { id, payload: proofFailure ? { error: 'fixture rejected attestation' } : { playerToken: 'fixture-player-proof', streamToken: 'fixture-stream-proof' } });
    });
    await page.addInitScript(() => {
      window.__results = {}; window.__events = []; window.__saved = '';
      window.MusicNative = { http: (...args) => window.httpNative(...args), cancelHttp() {}, loadSession: () => window.__saved,
        locale: () => JSON.stringify({ language: 'en', country: 'IN', userAgent: 'synthetic-native-agent', androidVersion: '15' }),
        proof: (...args) => window.proofNative(...args),
        result: (id, json) => { window.__results[id] = JSON.parse(json); }, event: (type, json) => { window.__events.push({ type, ...JSON.parse(json) }); } };
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/`);
    await page.waitForFunction(() => window.__events.some(e => e.type === 'ready'));
    let serial = 0;
    async function rpc(method, args = {}) {
      const id = String(++serial); await page.evaluate(({ id, method, args }) => { window.musicCall(id, method, args); }, { id, method, args });
      try { await page.waitForFunction(id => Object.hasOwn(window.__results, id), id, { timeout: 20000 }); }
      catch (error) { console.error(JSON.stringify({ paths: calls.map(c => c.path), errors })); throw error; }
      return page.evaluate(id => window.__results[id], id);
    }
    const found = await rpc('search', { query: 'test artist' });
    assert.deepEqual(found.tracks?.map(t => t.id), ['yt:aaaaaaaaaaa', 'yt:bbbbbbbbbbb'], JSON.stringify(found));
    assert.equal(found.tracks[0].artwork, 'https://i.ytimg.com/test-large.jpg'); assert.equal(found.tracks[0].artist, 'Test artist');
    assert(calls.some(c => c.path.endsWith('/search') && c.body.context.client.clientName === 'WEB_REMIX' && c.body.context.client.clientVersion === config.INNERTUBE_CLIENT_VERSION));
    const audio = await rpc('resolve', { videoId: 'jNQXAC9IVRw' });
    assert.equal(audio.mimeType, 'audio/mp4', JSON.stringify(audio)); assert(audio.url.startsWith('https://r1-synthetic.googlevideo.com/'));
    assert(calls.some(c => c.path === '/videoplayback' && c.headers.range === 'bytes=0-1'));
    assert(calls.some(c => c.path.endsWith('/player') && c.body.playbackContext.contentPlaybackContext.signatureTimestamp === 12345));
    playerMode = 'cipher';
    const ciphered = await rpc('resolve', { videoId: 'jNQXAC9IVRw' });
    assert.equal(new URL(ciphered.url).searchParams.get('sig'), 'zyx', JSON.stringify(ciphered));
    assert.equal(new URL(ciphered.url).searchParams.get('n'), 'cba');
    playerMode = 'direct';
    mediaStatus = 403; const guestDenied = await rpc('resolve', { videoId: 'jNQXAC9IVRw' }); assert.equal(guestDenied.error.code, 'MEDIA_HTTP_403');
    assert.deepEqual(proofCalls.slice(0, 2).map(c => [c.playerBinding, c.streamBinding]), [['fixture-visitor', 'fixture-visitor'], ['jNQXAC9IVRw', 'fixture-visitor']]);
    mediaStatus = 206;
    const homeResult = await rpc('home');
    assert.deepEqual(homeResult.sections?.map(s => s.title), ['Provider second alphabetically', 'Provider first alphabetically']);
    assert.deepEqual(homeResult.sections[0].items.map(i => i.id), ['yt:aaaaaaaaaaa', 'yt:bbbbbbbbbbb', 'yt:aaaaaaaaaaa']);
    assert.deepEqual((await rpc('explore')).sections?.map(s => s.title), ['New releases from provider']);
    await page.reload(); await page.waitForFunction(() => window.__events.some(e => e.type === 'ready'));
    await page.evaluate(() => { window.__saved = JSON.stringify({ kind: 'youtube-web', cookie: 'SAPISID=fixture-cookie; SID=fixture-sid', visitorData: 'fixture-visitor', authUser: 1, dataSyncId: 'fixture-account||', clientVersion: 'fixture-version' }); });
    assert.equal((await rpc('verify-session')).name, 'Fixture channel');
    assert(await page.evaluate(() => window.__events.some(e => e.type === 'connected' && e.name === 'Fixture channel')));
    assert(calls.some(c => c.path.endsWith('/account/accounts_list') && c.headers.authorization?.startsWith('SAPISIDHASH ') && c.headers['x-goog-authuser'] === '1' && c.body.context.client.clientName === 'WEB'), 'WEB account check must use the selected authenticated web session');
    await rpc('home'); assert(calls.some(c => c.path.endsWith('/browse') && c.headers.authorization?.startsWith('SAPISIDHASH ')));
    rejectSearch = true; assert.equal((await rpc('search', { query: 'blocked' })).error?.code, 'HTTP_403'); rejectSearch = false;
    playerMode = 'rejected'; proofFailure = true;
    const rejected = await rpc('resolve', { videoId: 'jNQXAC9IVRw' });
    assert.equal(rejected.error.code, 'PLAYER_REJECTED', JSON.stringify(rejected)); assert.equal(rejected.error.message, 'This client is unsupported.'); assert(!JSON.stringify(rejected).includes('region'));
    playerMode = 'sabr'; const sabr = await rpc('resolve', { videoId: 'jNQXAC9IVRw' }); assert.equal(sabr.error.code, 'SABR_UNSUPPORTED');
    playerMode = 'direct'; proofFailure = false; mediaStatus = 403;
    const denied = await rpc('resolve', { videoId: 'jNQXAC9IVRw' }); assert.equal(denied.error.code, 'MEDIA_HTTP_403'); assert(!denied.url);
    assert.equal(proofCalls.at(-1).videoId, 'jNQXAC9IVRw');
    assert.equal(proofCalls.at(-1).playerBinding, 'jNQXAC9IVRw'); assert.equal(proofCalls.at(-1).streamBinding, 'fixture-account||');
    assert.equal(proofCalls.at(-2).playerBinding, 'fixture-account||');
    assert(calls.some(c => c.path.endsWith('/att/get') && c.body.context.client.clientName === 'WEB'));
    assert(!calls.some(c => c.path.includes('/Create')));
    const verifiedCall = calls.findLast(c => c.path.endsWith('/player') && c.body.serviceIntegrityDimensions?.poToken);
    assert.equal(verifiedCall.body.serviceIntegrityDimensions.poToken, 'fixture-player-proof');
    assert(calls.some(c => c.path === '/videoplayback' && c.query.includes('pot=fixture-stream-proof')));
    assert(!calls.some(c => /\/tv|oauth|device\/code/.test(c.path) || ['TVHTML5', 'ANDROID', 'ANDROID_MUSIC'].includes(c.body?.context?.client?.clientName)));
    const sandbox = await page.evaluate(async () => (await import('/evaluator.mjs')).evaluatePlayer({ output: 'return {signature:"decoded",bridge:typeof MusicNative,dom:typeof document};' }));
    assert.deepEqual(sandbox, { signature: 'decoded', bridge: 'undefined', dom: 'undefined' });
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ passed: ['bundled engine startup', 'upstream search parser and real artist', 'largest artwork selection', 'current WEB_REMIX bootstrap version', 'range-probed audio resolution', 'signature timestamp on player request', 'actual upstream signature/n extraction in isolated Worker', 'provider feed and duplicate order', 'Explore provider browse', 'normal WEB cookie account verification', 'SAPISIDHASH authenticated feed', 'safe HTTP diagnostics', 'real provider rejection preserved', 'SABR-only transport diagnosed', 'CDN 403 never becomes playback success', 'client-specific guest proof binding', 'full authenticated DataSyncID proof binding', 'current WEB att/get challenge without old Create discovery', 'no TV or OAuth calls', 'worker has no account bridge'], requests: calls.length, liveProvider: false }, null, 2));
  } finally { await browser.close(); await new Promise(r => server.close(r)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
