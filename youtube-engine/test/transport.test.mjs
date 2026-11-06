import test from 'node:test';
import assert from 'node:assert/strict';
import { nativeTransport } from '../src/transport.mjs';

test('cancelling an old suggestion request releases its HTTP call without touching a newer request', async () => {
  const calls = [], cancelled = [];
  const transport = nativeTransport({ http: (...args) => calls.push(args), cancelHttp: id => cancelled.push(id) }, 'transportFixtureResult');
  const old = transport('https://music.youtube.com/youtubei/v1/music/get_search_suggestions', { method: 'POST', body: '{"input":"old"}', cancelKey: 'old' });
  const fresh = transport('https://music.youtube.com/youtubei/v1/music/get_search_suggestions', { method: 'POST', body: '{"input":"new"}', cancelKey: 'new' });
  const rejected = assert.rejects(old, { name: 'AbortError' });
  transport.cancel('old'); await rejected;
  assert.deepEqual(cancelled, [calls[0][0]]);
  globalThis.transportFixtureResult(calls[0][0], { status: 200, body: Buffer.from('old').toString('base64') });
  globalThis.transportFixtureResult(calls[1][0], { status: 200, body: Buffer.from('new').toString('base64') });
  assert.equal(await (await fresh).text(), 'new');
});
test('an already aborted request is never sent and an active abort ignores a late reply', async () => {
  const calls = [], cancelled = [];
  const transport = nativeTransport({ http: (...args) => calls.push(args), cancelHttp: id => cancelled.push(id) }, 'abortFixtureResult');
  const done = new AbortController(); done.abort();
  await assert.rejects(transport('https://music.youtube.com/', { signal: done.signal }), { name: 'AbortError' });
  assert.equal(calls.length, 0);
  const controller = new AbortController();
  const result = transport('https://music.youtube.com/', { signal: controller.signal });
  const rejected = assert.rejects(result, { name: 'AbortError' });
  controller.abort(); await rejected;
  assert.deepEqual(cancelled, [calls[0][0]]);
  globalThis.abortFixtureResult(calls[0][0], { status: 200, body: '' });
});
