import test from 'node:test';
import assert from 'node:assert/strict';
import { entry, track, sections, safeError } from '../src/mapping.mjs';
test('provider section, entry and duplicate order survives mapping', () => {
  const a = { id: 'aaaaaaaaaaa', title: 'Zebra', artists: [{ name: 'One' }], item_type: 'song' };
  const b = { id: 'bbbbbbbbbbb', title: 'Apple', artists: [{ name: 'Two' }], item_type: 'song' };
  const feed = sections({ sections: [ { header: { title: 'Second alphabetically' }, contents: [a, b, a] }, { header: { title: 'First alphabetically' }, contents: [b] } ] });
  assert.deepEqual(feed.map(s => s.title), ['Second alphabetically', 'First alphabetically']);
  assert.deepEqual(feed[0].items.map(i => i.id), ['yt:aaaaaaaaaaa', 'yt:bbbbbbbbbbb', 'yt:aaaaaaaaaaa']);
});
test('album and playlist recommendations retain their browse identity', () => {
  assert.equal(entry({ id: 'MPREb_test', title: 'Album', item_type: 'album' }).kind, 'album');
  const p = entry({ title: 'Mix', item_type: 'playlist', endpoint: { payload: { browseId: 'VLPL1234567890' } } });
  assert.equal(p.browseId, 'VLPL1234567890');
  assert.equal(track({ id: 'MPREb_test', title: 'Album' }), null);
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
  assert.equal(track({ id: 'jNQXAC9IVRw', title: 'Title' }).artist, 'Unknown artist');
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
