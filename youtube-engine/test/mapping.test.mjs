import test from 'node:test';
import assert from 'node:assert/strict';
import { entry, track, sections, safeError, isMusic, audioVersion } from '../src/mapping.mjs';
test('provider section, entry and duplicate order survives mapping', () => {
  const a = { id: 'aaaaaaaaaaa', title: 'Zebra', artists: [{ name: 'One' }], item_type: 'song' };
  const b = { id: 'bbbbbbbbbbb', title: 'Apple', artists: [{ name: 'Two' }], item_type: 'song' };
  const feed = sections({ sections: [ { header: { title: 'Second alphabetically' }, contents: [a, b, a] }, { header: { title: 'First alphabetically' }, contents: [b] } ] });
  assert.deepEqual(feed.map(s => s.title), ['Second alphabetically', 'First alphabetically']);
  assert.deepEqual(feed[0].items.map(i => i.id), ['yt:aaaaaaaaaaa', 'yt:bbbbbbbbbbb', 'yt:aaaaaaaaaaa']);
});
test('same-title sections from one page merge into a single header', () => {
  const a = { id: 'aaaaaaaaaaa', title: 'Zebra', artists: [{ name: 'One' }], item_type: 'song' };
  const b = { id: 'bbbbbbbbbbb', title: 'Apple', artists: [{ name: 'Two' }], item_type: 'song' };
  const feed = sections({ sections: [ { header: { title: 'From YouTube' }, contents: [a] }, { header: { title: 'From YouTube' }, contents: [b, a] } ] });
  assert.deepEqual(feed.map(s => s.title), ['From YouTube']);
  assert.deepEqual(feed[0].items.map(i => i.id), ['yt:aaaaaaaaaaa', 'yt:bbbbbbbbbbb', 'yt:aaaaaaaaaaa']);
});
test('album and playlist recommendations retain their browse identity', () => {
  assert.equal(entry({ id: 'MPREb_test', title: 'Album', item_type: 'album' }).kind, 'album');
  const p = entry({ title: 'Mix', item_type: 'playlist', endpoint: { payload: { browseId: 'VLPL1234567890' } } });
  assert.equal(p.browseId, 'VLPL1234567890');
  assert.equal(track({ id: 'MPREb_test', title: 'Album' }), null);
});
test('an MPR album id is an album even when its home card reports browse', () => {
  const browse = entry({ title: 'Album', item_type: 'browse', endpoint: { payload: { browseId: 'MPREb_homecard' } } });
  assert.equal(browse.kind, 'album');
  assert.equal(browse.browseId, 'MPREb_homecard');
  assert.equal(entry({ title: 'Album', item_type: 'endpoint', endpoint: { payload: { browseId: 'MPREb_endpoint' } } }).kind, 'album');
  assert.equal(entry({ title: 'Mix', item_type: 'browse', endpoint: { payload: { browseId: 'VLPL1234567890' } } }).kind, 'browse');
});
test('playback mapping keeps the original provider video id', () => {
  const song = track({ id: 'jNQXAC9IVRw', title: 'Recording', artists: [{ name: 'Artist' }], duration: { seconds: 19 } });
  assert.equal(song.url, 'https://www.youtube.com/watch?v=jNQXAC9IVRw');
  assert.equal(song.durationMs, 19000);
});
test('diagnostics expose a useful code without session tokens or signed URLs', () => {
  const data = safeError(new Error('HTTP 403 https://secret.googlevideo.com/?token=private'));
  assert.equal(data.code, 'HTTP_403');
  assert(!JSON.stringify(data).includes('token=private'));
  assert(!JSON.stringify(data).includes('googlevideo'));
});
test('largest artwork survives provider descending thumbnail order', async () => {
  const { largestArtwork } = await import('../src/mapping.mjs');
  assert.equal(largestArtwork([{ url: 'https://test/large', width: 600, height: 600 }, { url: 'https://test/small', width: 40, height: 40 }]), 'https://test/large');
});
test('missing artist is not mislabeled as the provider', () => {
  assert.equal(track({ id: 'jNQXAC9IVRw', title: 'Title' }).artist, '');
});
test('bootstrap JSON handles braces and escaped quotes without running scripts', async () => {
  const { readYtConfig } = await import('../src/web-config.mjs');
  const data = { VISITOR_DATA: 'quoted"}value', INNERTUBE_CONTEXT: { client: { clientVersion: 'current' } } };
  const parsed = readYtConfig(`ytcfg.set(${JSON.stringify(data)});maliciousScript();ytcfg.set({"OTHER":1});`);
  assert.equal(parsed.VISITOR_DATA, data.VISITOR_DATA); assert.equal(parsed.OTHER, 1);
});
test('actual unsupported-client reason is never translated into a region restriction', async () => {
  const { playerFailure } = await import('../src/web-config.mjs');
  const error = safeError(playerFailure('WEB', { playability_status: { status: 'ERROR', reason: 'This client is unsupported.' } }));
  assert.equal(error.message, 'This client is unsupported.'); assert.equal(error.code, 'PLAYER_REJECTED');
  assert(!JSON.stringify(error).includes('region'));
});
test('diagnostic redaction strips bearer credentials as well as signed URLs', async () => {
  const { sanitizeReason } = await import('../src/web-config.mjs');
  assert(!sanitizeReason('Authorization: Bearer synthetic-secret').includes('synthetic-secret'));
  assert(!sanitizeReason('Cookie: SAPISID=synthetic-cookie; SID=synthetic-id').includes('synthetic-'));
});
test('WEB_REMIX and WEB use different player bindings with an intact authenticated DataSyncID', async () => {
  const { proofBindings } = await import('../src/proof-bindings.mjs');
  const account = { videoId: 'jNQXAC9IVRw', visitorData: 'visitor', dataSyncId: 'delegated||primary', authenticated: true };
  assert.deepEqual(proofBindings({ ...account, client: 'YTMUSIC' }), { playerBinding: 'delegated||primary', streamBinding: 'delegated||primary' });
  assert.deepEqual(proofBindings({ ...account, client: 'WEB' }), { playerBinding: account.videoId, streamBinding: 'delegated||primary' });
  assert.deepEqual(proofBindings({ ...account, authenticated: false, client: 'YTMUSIC' }), { playerBinding: 'visitor', streamBinding: 'visitor' });
  assert.deepEqual(proofBindings({ ...account, client: 'WEB', contentBoundGvs: true }), { playerBinding: account.videoId, streamBinding: account.videoId });
  assert.throws(() => proofBindings({ ...account, dataSyncId: '', client: 'WEB' }), /Reconnect/);
});
test('account header delegation does not mistake a primary profile for a delegated one', async () => {
  const { delegatedAccount } = await import('../src/proof-bindings.mjs');
  assert.equal(delegatedAccount('123||456'), '123');
  assert.equal(delegatedAccount('123||'), undefined);
  assert.equal(delegatedAccount('123||456', '789'), '789');
});
test('GVS experiment reads serialized player flags and never treats string false as enabled', async () => {
  const { contentBoundGvs } = await import('../src/proof-bindings.mjs');
  const config = { WEB_PLAYER_CONTEXT_CONFIGS: { WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH: { serializedExperimentFlags: 'other=x&html5_generate_content_po_token=true' } }, EXPERIMENT_FLAGS: { html5_generate_content_po_token: false } };
  assert.equal(contentBoundGvs(config, 'WEB'), true);
  config.WEB_PLAYER_CONTEXT_CONFIGS.WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH.serializedExperimentFlags += '&html5_generate_content_po_token=false';
  assert.equal(contentBoundGvs(config, 'WEB'), false);
  assert.equal(contentBoundGvs({ EXPERIMENT_FLAGS: { html5_generate_content_po_token: 'false' } }, 'WEB'), false);
  assert.equal(contentBoundGvs({ EXPERIMENT_FLAGS: { html5_generate_content_po_token: true } }, 'YTMUSIC'), true);
});

test('empty artist arrays do not hide YouTube authors or linked subtitle artists', () => {
  assert.equal(track({ id: 'jNQXAC9IVRw', title: 'Track', artists: [], authors: [{name: 'YouTube artist'}] }).artist, 'YouTube artist');
  assert.equal(track({ id: 'jNQXAC9IVRw', title: 'Track', subtitle: { runs: [{ text: 'Provider artist', endpoint: { payload: {browseId: 'UCartist'} } }, {text: ' • Album • 2026'}] } }).artist, 'Provider artist');
});
test('a subtitle containing album and view labels is never guessed to be an artist', () => {
  assert.equal(track({ id: 'jNQXAC9IVRw', title: 'Track', subtitle: 'Album • 2026 • 1M views' }).artist, '');
});

import { sidAuthorization } from '../src/music-auth.mjs';
import { createHash } from 'node:crypto';
test('music requests are signed for the music origin like the web client', async () => {
  const sha = v => createHash('sha1').update(v).digest('hex');
  const auth = await sidAuthorization('SID=x; SAPISID=abc; __Secure-3PAPISID=def', 'https://music.youtube.com', 1700000000000);
  assert.equal(auth, `SAPISIDHASH 1700000000_${sha('1700000000 abc https://music.youtube.com')} SAPISID3PHASH 1700000000_${sha('1700000000 def https://music.youtube.com')}`);
  assert.equal(await sidAuthorization('SID=x', 'https://music.youtube.com'), '');
});

test('music videos, podcasts and episodes never reach feeds or radio', () => {
  const watch = (id, type) => ({ title: id, endpoint: { payload: { videoId: id, watchEndpointMusicSupportedConfigs: { watchEndpointMusicConfig: { musicVideoType: type } } } } });
  const song = watch('aaaaaaaaaaa', 'MUSIC_VIDEO_TYPE_ATV');
  // A non-English subtitle makes youtubei.js guess "video"; the video type still says it is a song.
  song.item_type = 'video';
  const omv = watch('bbbbbbbbbbb', 'MUSIC_VIDEO_TYPE_OMV'), ugc = watch('ccccccccccc', 'MUSIC_VIDEO_TYPE_UGC'), episode = watch('ddddddddddd', 'MUSIC_VIDEO_TYPE_PODCAST_EPISODE');
  const show = { title: 'Show', item_type: 'podcast_show', endpoint: { payload: { browseId: 'MPSPx', browseEndpointContextSupportedConfigs: { browseEndpointContextMusicConfig: { pageType: 'MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE' } } } } };
  const multi = { type: 'MusicMultiRowListItem', title: 'News today', endpoint: { payload: { videoId: 'eeeeeeeeeee' } } };
  const album = { title: 'Album', item_type: 'album', endpoint: { payload: { browseId: 'MPREb' } } };
  const feed = sections({ sections: [{ title: 'Mixed', contents: [song, omv, ugc, episode, show, multi, album] }, { title: 'New episodes', contents: [episode, multi] }] });
  assert.deepEqual(feed.map(s => [s.title, s.items.map(i => i.id)]), [['Mixed', ['yt:aaaaaaaaaaa', 'MPREb']]]);
  assert.equal(isMusic(omv), false);
  assert.equal(audioVersion({ type: 'PlaylistPanelVideoWrapper', primary: omv, counterpart: [song] }), song);
  assert.equal(isMusic({ title: 'Unlabelled', endpoint: { payload: { videoId: 'fffffffffff' } } }), true);
});
test('WEB search mapping keeps video identity and drops non-videos', async () => {
  const { videoTrack } = await import('../src/mapping.mjs');
  const trackLike = videoTrack({ type: 'Video', video_id: 'jNQXAC9IVRw', title: { toString: () => 'Video' },
    author: { name: 'Channel' }, thumbnails: [{ url: 'https://i/x', width: 640, height: 360 }], duration: { seconds: 19 } });
  assert.equal(trackLike.id, 'yt:jNQXAC9IVRw');
  assert.equal(trackLike.url, 'https://www.youtube.com/watch?v=jNQXAC9IVRw');
  assert.equal(trackLike.durationMs, 19000);
  assert.equal(videoTrack({ type: 'ShortsLockupView' }), null);
});
test('youtube videos feed keeps provider order and drops shorts and lockups', async () => {
  const { videoSections } = await import('../src/mapping.mjs');
  const video = (id, title, extra = {}) => ({ type: 'Video', video_id: id, title: { toString: () => title },
    author: { name: 'Channel' }, thumbnails: [{ url: 'https://i/' + id, width: 640, height: 360 }], duration: { seconds: 120 }, ...extra });
  const shelf = { type: 'Shelf', title: { toString: () => 'Shelf title' }, contents: [video('bbbbbbbbbbb', 'In shelf')] };
  const fakeFeed = {
    shelves: [shelf],
    videos: [
      video('aaaaaaaaaaa', 'First'),
      ...shelf.contents,
      { type: 'LockupView', content_type: 'SHORT', content_id: 'ccccccccccc', metadata: { title: { toString: () => 'Short' } } },
      { type: 'LockupView', content_type: 'VIDEO', content_id: 'ddddddddddd', content_image: { image: [{ url: 'https://i/ddddddddddd', width: 640, height: 360 }] },
        metadata: { title: { toString: () => 'Lockup' }, metadata: { metadata_rows: [{ metadata_parts: [{ text: { toString: () => 'Lockup channel' } }] }] } }, duration: { seconds: 200 } },
    ],
  };
  const out = videoSections(fakeFeed);
  assert.deepEqual(out.map(s => s.title), ['From YouTube', 'Shelf title']);
  assert.deepEqual(out[0].items.map(i => i.id), ['yt:aaaaaaaaaaa', 'yt:ddddddddddd']);
  assert.equal(out[0].items[1].artist, 'Lockup channel');
  assert.equal(out[0].items[1].durationMs, 200000);
  assert.equal(out[1].items[0].id, 'yt:bbbbbbbbbbb');
});
