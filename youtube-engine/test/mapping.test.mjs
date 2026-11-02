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
