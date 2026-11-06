import test from 'node:test';
import assert from 'node:assert/strict';
import { readBrowsePage, fetchBrowsePage, fetchBrowseContinuation } from '../src/browse-pages.mjs';
import { sections } from '../src/mapping.mjs';
const watch = id => ({ watchEndpoint: { videoId: id, watchEndpointMusicSupportedConfigs: { watchEndpointMusicConfig: { musicVideoType: 'MUSIC_VIDEO_TYPE_ATV' } } } });
const song = (id, title) => ({ musicTwoRowItemRenderer: { title: { simpleText: title }, navigationEndpoint: watch(id), subtitle: { simpleText: 'Artist' }, thumbnailRenderer: { musicThumbnailRenderer: { thumbnail: { thumbnails: [] } } } } });
const shelf = { musicCarouselShelfRenderer: { header: { musicCarouselShelfBasicHeaderRenderer: { title: { simpleText: 'Next page' } } }, contents: [song('aaaaaaaaaaa', 'First'), song('bbbbbbbbbbb', 'Second')] } };
const sparse = { continuationContents: { sectionListContinuation: { contents: [shelf], continuations: [{ nextContinuationData: { continuation: 'NEXT' } }] } } };
const full = contents => ({ contents: { singleColumnBrowseResultsRenderer: { tabs: [{ tabRenderer: { selected: true, content: { sectionListRenderer: { contents } } } }] } } });
const button = (name, params) => ({ musicNavigationButtonRenderer: { buttonText: { simpleText: name }, clickCommand: { browseEndpoint: { browseId: 'FEmusic_category', params } } } });
test('a continuation-only response needs no fabricated Home tab and preserves source order', () => {
  const page = readBrowsePage(sparse);
  assert.equal(page.has_continuation, true);
  assert.equal(page.page.continuation_contents.continuation, 'NEXT');
  assert.deepEqual(sections(page)[0].items.map(item => item.title), ['First', 'Second']);
});
test('append actions and endpoint commands expose only their top-level continuation token', () => {
  for (const key of ['onResponseReceivedActions', 'onResponseReceivedEndpoints', 'onResponseReceivedCommands']) {
    const page = readBrowsePage({ [key]: [{ appendContinuationItemsAction: { continuationItems: [shelf, { continuationItemRenderer: { continuationEndpoint: { continuationCommand: { token: 'APPEND' } } } }] } }] });
    assert.equal(page.page.continuation_contents.continuation, 'APPEND');
    assert.equal(sections(page)[0].items.length, 2);
  }
});
test('ordinary music shelves and navigation grids are not discarded by a carousel-only filter', () => {
  const page = readBrowsePage(full([{ gridRenderer: { items: [button('Rock', 'ROCK'), button('Pop', 'POP')] } }, { musicShelfRenderer: { title: { simpleText: 'New music' }, contents: [song('aaaaaaaaaaa', 'First')] } }]));
  const mapped = sections(page);
  assert.deepEqual(mapped.map(item => item.title), ['Browse', 'New music']);
  assert.deepEqual(mapped[0].items.map(item => [item.title, item.kind, item.browseId, item.params]), [['Rock', 'browse', 'FEmusic_category', 'ROCK'], ['Pop', 'browse', 'FEmusic_category', 'POP']]);
  assert.notEqual(mapped[0].items[0].id, mapped[0].items[1].id);
});
test('new music shelf wrappers retain playable items without depending on a vendor shelf class', () => {
  const page = readBrowsePage(full([{ musicImmersiveCarouselShelfRenderer: { title: { simpleText: 'From provider' }, contents: [song('aaaaaaaaaaa', 'First')] } }]));
  assert.equal(sections(page)[0].title, 'From provider');
  assert.equal(sections(page)[0].items[0].videoId, 'aaaaaaaaaaa');
});
test('an explicit empty continuation ends the feed; an unknown response reports a format error', () => {
  assert.equal(readBrowsePage({ continuationContents: { sectionListContinuation: { contents: [] } } }).has_continuation, false);
  assert.throws(() => readBrowsePage({ responseContext: {} }), e => e.code === 'BROWSE_RESPONSE' && !e.message.includes('responseContext'));
});
test('a follow-on page with no content ends the feed instead of failing, and shelf continuations are read', async () => {
  assert.deepEqual(readBrowsePage({ responseContext: {} }, { continuation: true }), { sections: [], has_continuation: false, page: { continuation_contents: { continuation: null } } });
  const api = { actions: { execute: async () => ({ success: true, data: { responseContext: {} } }) } };
  assert.equal((await fetchBrowseContinuation(api, { page: { continuation_contents: { continuation: 'CURRENT' } } })).has_continuation, false);
  const more = readBrowsePage({ continuationContents: { musicShelfContinuation: { contents: [song('bbbbbbbbbbb', 'Second')] } } }, { continuation: true });
  assert.equal(sections(more)[0].items[0].videoId, 'bbbbbbbbbbb');
});
test('continuation fetches use the Music client and the existing token, without calling the initial browse', async () => {
  const calls = []; const api = { actions: { execute: async (...args) => { calls.push(args); return { success: true, data: sparse }; } } };
  await fetchBrowseContinuation(api, { page: { continuation_contents: { continuation: 'CURRENT' } } });
  assert.deepEqual(calls, [['/browse', { client: 'YTMUSIC', continuation: 'CURRENT' }]]);
  calls.length = 0;
  assert.equal((await fetchBrowseContinuation(api, { page: {} })).has_continuation, false);
  assert.equal(calls.length, 0);
});
test('category requests preserve browse parameters and reject malformed targets before HTTP', async () => {
  const calls = []; const api = { actions: { execute: async (...args) => { calls.push(args); return { success: true, data: full([shelf]) }; } } };
  await fetchBrowsePage(api, 'FEmusic_category', 'ROCK');
  assert.deepEqual(calls[0], ['/browse', { client: 'YTMUSIC', browseId: 'FEmusic_category', params: 'ROCK' }]);
  await assert.rejects(fetchBrowsePage(api, 'https://untrusted.test'), e => e.code === 'BROWSE_TARGET');
  assert.equal(calls.length, 1);
});
test('failed page acknowledgements keep the previous cursor available for a retry', async () => {
  const previous = { page: { continuation_contents: { continuation: 'CURRENT' } } };
  await assert.rejects(fetchBrowseContinuation({ actions: { execute: async () => ({ success: false }) } }, previous), e => e.code === 'FEED_REQUEST');
  assert.equal(previous.page.continuation_contents.continuation, 'CURRENT');
});
