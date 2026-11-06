import test from 'node:test';
import assert from 'node:assert/strict';
import { homeCursor, advanceHomeCursor } from '../src/feed-cursor.mjs';
const first = cursor => ({ page: { contents: [{ tabs: [{ selected: true, content: { continuation: cursor } }] }] } });
const next = cursor => ({ page: { continuation_contents: { continuation: cursor } } });
test('home cursors advance through duplicate-content pages and end only when the provider ends', () => {
  const seen = new Set();
  advanceHomeCursor(seen, first('first'), false);
  for (let i = 0; i < 6; i++) advanceHomeCursor(seen, next('page-' + i), true);
  assert.equal(seen.size, 7); assert.equal(homeCursor(next(null)), null);
  advanceHomeCursor(seen, next(null), true);
});
test('a repeated provider cursor stops automatic request loops and a refresh resets the guard', () => {
  const seen = new Set(); advanceHomeCursor(seen, first('first'), false);
  advanceHomeCursor(seen, next('second'), true);
  assert.throws(() => advanceHomeCursor(seen, next('first'), true), e => e.code === 'FEED_CONTINUATION');
  advanceHomeCursor(seen, first('first'), false);
  assert.deepEqual([...seen], ['first']);
});

test('the parser-wrapped initial Home cursor participates in the same repeat guard', () => {
  const page = { page: { contents: { item: () => ({ tabs: [{ selected: true, content: { continuation: 'first' } }] }) } } };
  assert.equal(homeCursor(page), 'first');
  const seen = new Set(); advanceHomeCursor(seen, page, false);
  assert.throws(() => advanceHomeCursor(seen, next('first'), true), e => e.code === 'FEED_CONTINUATION');
});
