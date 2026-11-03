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
const related = { contents: { sectionListRenderer: { contents: [carousel('You might also like', [card('ccccccccccc', 'Similar song')])] } } };
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
    const page = await browser.newPage(); let rejectSearch = false, rejectAccounts = false, bootstrapMode = 'normal', playerMode = 'direct', mediaStatus = 206, proofFailure = false, guestPlayable = false; const calls = [], proofCalls = [], errors = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.exposeFunction('httpNative', async (id, url, options) => {
      const o = JSON.parse(options), u = new URL(url), bodyIn = o.body ? JSON.parse(o.body) : null;
      calls.push({ path: u.pathname, host: u.hostname, query: u.searchParams.toString(), headers: o.headers, method: o.method, body: bodyIn });
      let body, status = 200, type = 'application/json';
      if (u.pathname === '/') { body = `<script>ytcfg.set(${JSON.stringify(u.hostname === 'music.youtube.com' ? config : { INNERTUBE_CLIENT_VERSION: '2.20261102.00.00' })});</script>`; type = 'text/html'; if (bootstrapMode === 'redirect') { status = 302; body = ''; } }
      else if (u.pathname.endsWith('/search')) { body = search; if (rejectSearch) status = 403; }
      else if (u.pathname.endsWith('/browse')) body = bodyIn?.browseId === 'FEmusic_explore' ? explore : bodyIn?.browseId === 'MPTRt_fixture' ? related : home;
      else if (u.pathname.endsWith('/next')) body = { contents: { singleColumnMusicWatchNextResultsRenderer: { tabbedRenderer: { watchNextTabbedResultsRenderer: { tabs: [
        { tabRenderer: { title: 'Up next', content: { musicQueueRenderer: {} } } },
        { tabRenderer: { title: 'Related', endpoint: { browseEndpoint: { browseId: 'MPTRt_fixture', browseEndpointContextSupportedConfigs: { browseEndpointContextMusicConfig: { pageType: 'MUSIC_PAGE_TYPE_TRACK_RELATED' } } } } } }] } } } } };
      else if (u.pathname.endsWith('/player')) {
        body = structuredClone(basePlayer);
        const clientName = bodyIn?.context?.client?.clientName, guestClient = ['VISIONOS', 'WEB_EMBEDDED_PLAYER'].includes(clientName);
        // The embedded player only plays with the embed page's flags and that page's visitor.
        const visionMusic = clientName === 'VISIONOS' && bodyIn.context.client.clientVersion === '0.1';
        // The current visionOS app only plays with a visitor issued to it.
        const visionReady = clientName !== 'VISIONOS' || visionMusic || bodyIn.context.client.visitorData === 'vision-visitor';
        const embedReady = clientName !== 'WEB_EMBEDDED_PLAYER' || (bodyIn.playbackContext?.contentPlaybackContext?.encryptedHostFlags === 'fixture-host-flags' && bodyIn.context.client.visitorData === 'embed-visitor');
        if (guestClient && (!guestPlayable || (guestPlayable === 'embed' && clientName !== 'WEB_EMBEDDED_PLAYER') || (guestPlayable === 'music' && !visionMusic) || !embedReady || !visionReady)) body = { playabilityStatus: { status: 'UNPLAYABLE', reason: 'Fixture guest client declined.' } };
        else if (!guestClient && playerMode === 'webBlocked') body = { playabilityStatus: { status: 'LOGIN_REQUIRED', reason: 'Sign in to confirm you’re not a bot' } };
        else {
        if (playerMode === 'rejected') body = { playabilityStatus: { status: 'ERROR', reason: 'This client is unsupported.' } };
        if (playerMode === 'sabr') body.streamingData = { serverAbrStreamingUrl: 'https://r1-synthetic.googlevideo.com/videoplayback?sabr=1' };
        if (playerMode === 'noLength') { const f = body.streamingData.adaptiveFormats[0]; delete f.contentLength; f.url += '&clen=1024'; }
        if (playerMode === 'preferAac') { const f = body.streamingData.adaptiveFormats[0]; body.streamingData.adaptiveFormats.unshift({...f, itag:251, mimeType:'audio/webm; codecs="opus"', bitrate:256000}); }
        if (playerMode === 'cipher') { const f = body.streamingData.adaptiveFormats[0]; f.signatureCipher = new URLSearchParams({ url: f.url + '&n=abc', sp: 'sig', s: 'xyz' }).toString(); delete f.url; }
        }
      }
      else if (u.pathname === '/youtubei/v1/visitor_id') body = { responseContext: { visitorData: 'vision-visitor' } };
      else if (u.pathname.startsWith('/embed/')) { body = `<script>ytcfg.set({"VISITOR_DATA":"embed-visitor","PLAYER_VARS":{"embedded_player_response":"{\\"encryptedHostFlags\\":\\"x\\"}"},"WEB_PLAYER_CONTEXT_CONFIGS":{"WEB_PLAYER_CONTEXT_CONFIG_ID_EMBEDDED_PLAYER":{"encryptedHostFlags":"fixture-host-flags"}}});</script>`; type = 'text/html'; }
      else if (u.pathname.endsWith('/account/accounts_list')) { body = accounts; if (rejectAccounts) status = 401; }
      else if (u.pathname.endsWith('/att/get')) body = { bgChallenge: { program: 'fixture-program', globalName: 'fixtureVM', interpreterJavascript: { privateDoNotAccessOrElseSafeScriptWrappedValue: 'fixture-only' } } };
      else if (u.pathname === '/iframe_api') { body = 'player\\/fixture\\/'; type = 'application/javascript'; }
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
    assert(!calls.some(c => c.path === '/videoplayback'), 'Resolution must not download a duplicate audio probe');
    assert.equal(audio.contentLength, 1024);
    assert(await page.evaluate(() => window.__events.some(e => e.type === 'metadata' && e.artist === 'Test artist')));
    assert(calls.some(c => c.path.endsWith('/player') && c.body.playbackContext.contentPlaybackContext.signatureTimestamp === 12345));
    playerMode = 'preferAac'; assert.equal((await rpc('resolve', {videoId: 'jNQXAC9IVRw'})).mimeType, 'audio/mp4');
    playerMode = 'noLength'; assert.equal((await rpc('resolve', {videoId: 'jNQXAC9IVRw'})).contentLength, 1024);
    playerMode = 'cipher';
    const ciphered = await rpc('resolve', { videoId: 'jNQXAC9IVRw' });
    assert.equal(new URL(ciphered.url).searchParams.get('sig'), 'zyx', JSON.stringify(ciphered));
    assert.equal(new URL(ciphered.url).searchParams.get('n'), 'cba');
    playerMode = 'direct';
    const guestVerified = await rpc('resolve', { videoId: 'jNQXAC9IVRw', forceProof: true });
    assert(guestVerified.url); assert.equal(proofCalls.at(-1).playerBinding, 'fixture-visitor');
    assert.equal(proofCalls.at(-1).streamBinding, 'fixture-visitor');
    const homeResult = await rpc('home');
    assert.deepEqual(homeResult.sections?.map(s => s.title), ['Provider second alphabetically', 'Provider first alphabetically']);
    assert.deepEqual(homeResult.sections[0].items.map(i => i.id), ['yt:aaaaaaaaaaa', 'yt:bbbbbbbbbbb', 'yt:aaaaaaaaaaa']);
    assert.deepEqual((await rpc('explore')).sections?.map(s => s.title), ['New releases from provider']);
    const relatedPage = await rpc('related', { videoId: 'aaaaaaaaaaa' });
    assert.deepEqual(relatedPage.sections?.map(s => [s.title, s.items[0].videoId]), [['You might also like', 'ccccccccccc']], JSON.stringify(relatedPage));
    bootstrapMode = 'redirect';
    await page.reload(); await page.waitForFunction(() => window.__events.some(e => e.type === 'ready'));
    const confirmationStart = calls.length;
    await page.evaluate(() => { window.__saved = JSON.stringify({ kind: 'youtube-web', cookie: 'SAPISID=fixture-cookie; SID=fixture-sid', visitorData: 'fixture-visitor', authUser: 1, dataSyncId: 'fixture-account||', clientVersion: 'fixture-version', playerScript: '/s/player/fixture/player_ias.vflset/en_US/base.js', musicContentBoundGvs: false }); });
    assert.equal((await rpc('verify-session')).name, 'Fixture channel');
    assert(!calls.slice(confirmationStart).some(c => c.path === '/'), 'Fresh account verification must not refetch redirected homepages');
    assert(await page.evaluate(() => window.__events.some(e => e.type === 'connected' && e.name === 'Fixture channel')));
    assert(calls.some(c => c.path.endsWith('/account/accounts_list') && c.headers.authorization?.startsWith('SAPISIDHASH ') && c.headers['x-goog-authuser'] === '1' && c.body.context.client.clientName === 'WEB'), 'WEB account check must use the selected authenticated web session');
    const signedHome = await rpc('home'); assert(calls.some(c => c.path.endsWith('/browse') && c.headers.authorization?.startsWith('SAPISIDHASH ')));
    assert(calls.filter(c => c.path.endsWith('/browse')).every(c => c.host === 'music.youtube.com' && c.headers.origin === 'https://music.youtube.com'), 'Music browse must use the music origin like the web client');
    assert.equal(signedHome.signedIn, true); assert.equal(signedHome.personalized, true);
    assert(calls.some(c => c.path.endsWith('/player') && c.host === 'www.youtube.com'), 'Playback requests keep their current origin');
    rejectSearch = true; assert.equal((await rpc('search', { query: 'blocked' })).error?.code, 'HTTP_403'); rejectSearch = false;
    playerMode = 'rejected'; proofFailure = true;
    const rejected = await rpc('resolve', { videoId: 'jNQXAC9IVRw' });
    assert.equal(rejected.error.code, 'PLAYER_REJECTED', JSON.stringify(rejected)); assert.equal(rejected.error.message, 'This client is unsupported.'); assert(!JSON.stringify(rejected).includes('region'));
    playerMode = 'sabr'; const sabr = await rpc('resolve', { videoId: 'jNQXAC9IVRw' }); assert.equal(sabr.error.code, 'SABR_UNSUPPORTED');
    playerMode = 'direct'; proofFailure = false; mediaStatus = 403;
    const denied = await rpc('resolve', { videoId: 'jNQXAC9IVRw', forceProof: true }); assert(denied.url); assert.equal(new URL(denied.url).searchParams.get('pot'), 'fixture-stream-proof');
    assert.equal(proofCalls.at(-1).videoId, 'jNQXAC9IVRw');
    assert.equal(proofCalls.at(-1).playerBinding, 'fixture-account||'); assert.equal(proofCalls.at(-1).streamBinding, 'fixture-account||');
    assert(calls.some(c => c.path.endsWith('/att/get') && c.body.context.client.clientName === 'WEB'));
    assert(!calls.some(c => c.path.includes('/Create')));
    const verifiedCall = calls.findLast(c => c.path.endsWith('/player') && c.body.serviceIntegrityDimensions?.poToken);
    assert.equal(verifiedCall.body.serviceIntegrityDimensions.poToken, 'fixture-player-proof');
    assert(!calls.some(c => c.path === '/videoplayback'));
    assert(!calls.some(c => /\/tv|oauth|device\/code/.test(c.path) || ['ANDROID', 'ANDROID_MUSIC', 'ANDROID_VR', 'TVHTML5', 'TVHTML5_SIMPLY'].includes(c.body?.context?.client?.clientName)), 'No TV, VR or OAuth clients');
    const guestCalls = calls.filter(c => ['VISIONOS', 'WEB_EMBEDDED_PLAYER'].includes(c.body?.context?.client?.clientName));
    assert(guestCalls.length > 0 && guestCalls.every(c => c.headers['x-music-anonymous'] === '1' && !c.headers.authorization && !c.headers['x-goog-authuser'] && !c.headers.cookie), 'Proof-free clients must stay signed out');
    // The reported failure: YouTube wants a proof from the web clients and the device's attestation is rejected.
    playerMode = 'webBlocked'; proofFailure = true; guestPlayable = true;
    const guestStart = calls.length, guestProofs = proofCalls.length;
    const proofFree = await rpc('resolve', { videoId: 'zAiIgYOH4Ys', forceProof: true });
    assert(proofFree.url, JSON.stringify(proofFree)); assert(!new URL(proofFree.url).searchParams.has('pot'));
    // The remembered attested path is retried once; its rejection must not stop playback.
    assert(proofCalls.length - guestProofs <= 1);
    const vision = calls.slice(guestStart).find(c => c.path.endsWith('/player'));
    // NewPipe's profile first: the current visionOS app with its own visitor and native agent.
    const visitorCall = calls.find(c => c.path === '/youtubei/v1/visitor_id');
    assert(visitorCall && visitorCall.headers['x-music-anonymous'] === '1' && !visitorCall.headers.cookie && visitorCall.body.context.client.clientName === 'VISIONOS');
    assert.equal(vision.body.context.client.clientName, 'VISIONOS'); assert.equal(vision.body.context.client.clientVersion, '1.04');
    assert.equal(vision.body.context.client.deviceModel, 'RealityDevice17,1'); assert.equal(vision.body.context.client.visitorData, 'vision-visitor');
    assert.equal(vision.headers['x-goog-visitor-id'], 'vision-visitor'); assert.equal(vision.headers['x-goog-api-format-version'], '2');
    assert.equal(vision.host, 'www.youtube.com'); assert(!vision.headers.origin && !vision.headers.Origin);
    assert.match(vision.headers['x-music-user-agent'], /^com\.google\.visionos\.youtube\/1\.04\(RealityDevice17,1;/); assert.equal(vision.body.playbackContext?.contentPlaybackContext?.signatureTimestamp, undefined);
    assert.match(proofFree.headers['User-Agent'], /^com\.google\.visionos\.youtube\//); assert(!proofFree.headers.Origin);
    const nextSong = calls.length;
    assert((await rpc('resolve', { videoId: 'bbbbbbbbbbb' })).url);
    assert.deepEqual(calls.slice(nextSong).filter(c => c.path.endsWith('/player')).map(c => c.body.context.client.clientName), ['VISIONOS'], 'The working path is remembered');
    // The current app declined: Metrolist's visionOS 0.1 profile on the music player endpoint.
    guestPlayable = 'music';
    const musicStart = calls.length;
    const musicVision = await rpc('resolve', { videoId: 'ddddddddddd' });
    assert(musicVision.url, JSON.stringify(musicVision));
    const musicCall = calls.slice(musicStart).find(c => c.path.endsWith('/player') && c.body.context.client.clientVersion === '0.1');
    assert.equal(musicCall.host, 'music.youtube.com'); assert.equal(musicCall.headers.origin, 'https://music.youtube.com');
    assert.equal(musicCall.headers['x-youtube-client-version'], '0.1'); assert.match(musicCall.headers['x-music-user-agent'], /Version\/17\.5 Safari/);
    assert.match(musicVision.headers['User-Agent'], /Version\/17\.5 Safari/); assert(!musicVision.headers.Origin);
    // visionOS declined: the embedded player plays with the embed page's host flags and visitor.
    guestPlayable = 'embed';
    const embedStart = calls.length;
    const embedded = await rpc('resolve', { videoId: 'ccccccccccc' });
    assert(embedded.url, JSON.stringify(embedded));
    const embedPage = calls.slice(embedStart).find(c => c.path === '/embed/ccccccccccc');
    assert(embedPage && embedPage.headers['x-music-anonymous'] === '1' && embedPage.headers.referer === 'https://www.reddit.com/' && !embedPage.headers.cookie);
    const embedPlayer = calls.slice(embedStart).find(c => c.path.endsWith('/player') && c.body.context.client.clientName === 'WEB_EMBEDDED_PLAYER');
    assert.equal(embedPlayer.headers['x-goog-visitor-id'], 'embed-visitor'); assert.equal(embedPlayer.body.context.thirdParty.embedUrl, 'https://www.reddit.com/');
    assert.equal(embedded.headers.Origin, 'https://www.youtube.com');
    guestPlayable = false;
    const blocked = await rpc('resolve', { videoId: 'aaaaaaaaaaa' });
    assert.equal(blocked.error.code, 'VERIFICATION_REQUIRED', JSON.stringify(blocked)); assert.match(blocked.error.diagnostic, /VISIONOS[A-Z_, ]*: UNPLAYABLE/);
    assert.match(blocked.error.diagnostic, /runtime verification: rejected/);
    playerMode = 'direct'; proofFailure = false;
    const sandbox = await page.evaluate(async () => (await import('/evaluator.mjs')).evaluatePlayer({ output: 'return {signature:"decoded",bridge:typeof MusicNative,dom:typeof document};' }));
    assert.deepEqual(sandbox, { signature: 'decoded', bridge: 'undefined', dom: 'undefined' });
    const loginSource = fs.readFileSync(path.resolve(__dirname, '../../app/src/main/java/labs/rtensor/music/YouTubeLoginActivity.kt'), 'utf8');
    const captureExpression = loginSource.match(/evaluateJavascript\("""([\s\S]*?)"""\)/)[1];
    await page.evaluate(() => { window.ytcfg = { data_: { VISITOR_DATA: 'captured-visitor', SESSION_INDEX: '1', DATASYNC_ID: 'captured-account||', INNERTUBE_CLIENT_VERSION: 'captured-version', PLAYER_JS_URL: 'https://attacker.test/invalid.js', WEB_PLAYER_CONTEXT_CONFIGS: { WEB_PLAYER_CONTEXT_CONFIG_ID_MUSIC_PLAYER: { serializedExperimentFlags: 'html5_generate_content_po_token=false' } } } }; });
    const capture = JSON.parse(await page.evaluate(captureExpression));
    assert.equal(capture.visitorData, 'captured-visitor'); assert.equal(capture.authUser, 1);
    assert.equal(capture.dataSyncId, 'captured-account||'); assert.equal(capture.playerScript, ''); assert.equal(capture.musicContentBoundGvs, false);
    await page.evaluate(() => { window.ytcfg.data_.PLAYER_JS_URL = '/s/player/fixture/player_ias.vflset/en_US/base.js'; });
    assert.equal(JSON.parse(await page.evaluate(captureExpression)).playerScript, 'https://www.youtube.com/s/player/fixture/player_ias.vflset/en_US/base.js');
    rejectAccounts = true;
    await page.reload(); await page.waitForFunction(() => window.__events.some(e => e.type === 'ready'));
    await page.evaluate(() => { window.__saved = JSON.stringify({ kind: 'youtube-web', cookie: 'SAPISID=fixture-cookie', visitorData: 'fixture-visitor', authUser: 1, clientVersion: 'fixture-version' }); });
    const invalidAccount = await rpc('verify-session');
    assert.equal(invalidAccount.error?.code, 'AUTH_REQUIRED');
    assert(!await page.evaluate(() => window.__events.some(e => e.type === 'connected')));
    rejectAccounts = false;
    await page.reload(); await page.waitForFunction(() => window.__events.some(e => e.type === 'ready'));
    const redirectedGuest = await rpc('search', { query: 'test' });
    assert.equal(redirectedGuest.error?.code, 'WEB_SIGN_IN_REQUIRED');
    assert(!redirectedGuest.tracks);
    bootstrapMode = 'normal'; rejectAccounts = false; mediaStatus = 206; playerMode = 'direct'; proofFailure = false;
    await page.reload(); await page.waitForFunction(() => window.__events.some(e => e.type === 'ready'));
    const retryStart = calls.length;
    const verifiedRetry = await rpc('resolve', { videoId: 'i1o1p_DD6TU', forceProof: true });
    assert(verifiedRetry.url, JSON.stringify(verifiedRetry));
    assert(calls.slice(retryStart).filter(c => c.path.endsWith('/player') && ['WEB', 'WEB_REMIX'].includes(c.body.context.client.clientName)).every(c => !!c.body.serviceIntegrityDimensions?.poToken), 'Forced retry skips unverified web player attempts');
    const startPlayers = calls.filter(c => c.path.endsWith('/player')).length;
    const both = await Promise.all([rpc('resolve', {videoId: 'aaaaaaaaaaa'}), rpc('resolve', {videoId: 'aaaaaaaaaaa'})]);
    assert(both.every(a => a.url)); assert.equal(calls.filter(c => c.path.endsWith('/player')).length - startPlayers, 1);
    assert.equal((await rpc('metadata', {videoId: 'jNQXAC9IVRw'})).artist, 'Test artist');
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ passed: ['bundled engine startup', 'upstream search parser and real artist', 'largest artwork selection', 'current WEB_REMIX bootstrap version', 'no duplicate pre-playback media download', 'signature timestamp on player request', 'actual upstream signature/n extraction in isolated Worker', 'provider feed and duplicate order', 'Explore provider browse', 'related songs for personal Explore rows', 'normal WEB cookie account verification', 'captured session confirms despite homepage302', 'actual Android login capture keeps selected metadata and ignores optional untrusted scripts', 'invalid account still fails confirmation', 'guest browser redirect is actionable', 'SAPISIDHASH authenticated feed', 'music-origin signed feed requests', 'safe HTTP diagnostics', 'real provider rejection preserved', 'SABR-only transport diagnosed', 'forced verification supplies signed media URL for native validation', 'guest music-client proof binding', 'full authenticated DataSyncID proof binding', 'current WEB att/get challenge without old Create discovery', 'forced retry skips unverified web player attempts', 'no OAuth, TV or VR calls; proof-free visionOS/embedded clients stay signed out', 'proof-rejected playback uses the current visionOS app with its own visitor', 'visionOS 0.1 on the music player endpoint as backup', 'embedded player uses embed-page host flags and visitor', 'working proof-free path remembered', 'worker has no account bridge', 'concurrent resolution is shared', 'missing artist refresh uses YouTube metadata', 'AAC preference for progressive playback', 'signed provider content length fallback'], requests: calls.length, liveProvider: false }, null, 2));
  } finally { await browser.close(); await new Promise(r => server.close(r)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
