import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
import {randomUUID} from 'node:crypto';

const script = fs.readFileSync(new URL('../../app/src/main/assets/spotify/catalogue.js', import.meta.url), 'utf8');
const seed = 'a'.repeat(22), next = 'b'.repeat(22), playlist = 'c'.repeat(22);
const music = id => ({uri: `spotify:track:${id}`, name: 'Afterimage', artists: {items: [{profile: {name: 'Mira'}}]},
  duration: {totalMilliseconds: 180000}, albumOfTrack: {coverArt: {sources: [
    {url: 'https://i.scdn.co/image/small', width: 64}, {url: 'https://i.scdn.co/image/large', width: 640}]}}});
function page({anonymous = false, failure = false, origin = 'https://open.spotify.com', topFrame = true, assetFailureOnce = false} = {}) {
  const events = new Map(), calls = [], messages = [], fetches = [];
  const catalogue = {
    home: {data: {home: {sectionContainer: {sections: {items: [
      {data: {title: {text: 'Made for you'}, items: [{data: {uri: `spotify:playlist:${playlist}`, name: 'Daily mix'}}]}},
      {data: {__typename: 'HomePromotionShelf', title: {text: 'Promotion'}, items: [music(next)]}}
    ]}}}}},
    fetchPlaylistContents: {data: {playlistV2: {content: {totalCount: 3, items: [{itemV2: {data: music(seed)}}, {itemV2: {data: music(next)}}]},
      unrelated: music('d'.repeat(22))}}},
    getAlbum: {data: {albumUnion: {tracksV2: {totalCount: 1, items: [{track: music(next)}]}}}},
    getTrack: {data: {trackUnion: music(next)}},
    searchTracks: {data: {searchV2: {tracksV2: {items: [{item: {data: music(seed)}}]}}}}
  };
  const registry = new Map();
  registry.set(Symbol('TokenProvider'), {getToken: async () => ({accessToken: 'PRIVATE_BEARER', isAnonymous: anonymous})});
  registry.set(Symbol('UserAPI'), {getUser: async () => ({displayName: 'Listener', username: 'private-user'})});
  const platform = {getRegistry: () => ({_map: registry, resolve: key => registry.get(key)}),
    getGraphQLLoader: () => async (definition, variables) => {
      calls.push({definition, variables});
      if (failure) throw Error('PRIVATE_BEARER cookie=SECRET');
      return catalogue[definition.name];
    }};
  const root = {__reactContainer$test: {memoizedProps: {value: platform}}, querySelectorAll: () => []};
  const bundle = 'https://open.spotifycdn.com/cdn/build/web-player/web-player.test.js';
  const searchBundle = 'https://open.spotifycdn.com/cdn/build/web-player/xpui-routes-search.test.js';
  const declarations = Buffer.from(JSON.stringify({search: searchBundle})).toString('base64');
  const window = {addEventListener: (name, handler) => events.set(name, handler)}; window.top = topFrame ? window : {};
  const document = {body: root, scripts: [{src: bundle}], addEventListener: () => {}, querySelectorAll: () => [],
    getElementById: id => id === 'main' ? root : id === '__CDN_FILE_URLS__' ? {textContent: declarations} : null};
  const fetch = async (url, options) => {
    fetches.push({url, options});
    if (url.startsWith('https://spclient.wg.spotify.com/inspiredby-mix/')) return {ok: true, json: async () => ({mediaItems: [{uri: `spotify:playlist:${playlist}`}]})};
    assert(url === bundle || url === searchBundle, 'Only published Spotify build assets may be inspected');
    assert(options.signal instanceof AbortSignal, 'Asset fetches have a bounded abort deadline');
    if (assetFailureOnce && url === bundle) { assetFailureOnce = false; throw Error('temporary unavailable build asset'); }
    const names = url === searchBundle ? ['searchTracks'] : ['home', 'fetchPlaylistContents', 'getAlbum', 'getTrack'];
    return {ok: true, headers: {get: () => null}, text: async () => names.map(name => `new Q("${name}","query","${'a'.repeat(64)}")`).join(';')};
  };
  vm.runInNewContext(script, {window, document, location: {origin}, performance: {getEntriesByType: () => []},
    fetch, URL, setTimeout, clearTimeout, AbortController, Date, atob: value => Buffer.from(value, 'base64').toString('binary')});
  const port = {postMessage: value => messages.push(JSON.parse(value)), start() {}};
  const bind = (overrides = {}) => events.get('message')?.({origin: '', source: null, isTrusted: true,
    data: 'rtensor-spotify-catalogue', ports: [port], ...overrides});
  bind();
  const request = async (operation, args = {}) => {
    const id = randomUUID(); await port.onmessage({data: JSON.stringify({id, operation, args})});
    const result = messages.find(message => message.id === id); assert(result); return result;
  };
  return {port, bind, events, request, calls, fetches, messages};
}

test('profile returns no cookie, token or player-control surface', async () => {
  const p = page(); const result = await p.request('profile');
  assert.deepEqual(result.result, {name: 'Listener'});
  assert(!JSON.stringify(result).includes('PRIVATE_BEARER'));
  assert.equal(p.fetches.length, 0);
});
test('anonymous accounts cannot read personalized metadata', async () => {
  const p = page({anonymous: true});
  assert.equal((await p.request('home')).error, 'AUTH_REQUIRED');
  assert.equal(p.calls.length, 0);
});
test('home preserves provider rows, filters promotions and shares operation discovery', async () => {
  const p = page(); const [home, search] = await Promise.all([p.request('home'), p.request('search', {query: 'Afterimage Mira'})]);
  assert.equal(home.result.sections.length, 1); assert.equal(home.result.sections[0].title, 'Made for you');
  assert.equal(home.result.sections[0].items[0].id, playlist);
  assert.equal(search.result.items[0].id, seed);
  assert.equal(p.fetches.length, 2, 'Concurrent queries inspect each current build asset once');
  assert(p.calls.every(call => call.definition.operation === 'query'));
});
test('collection reads the requested track list only, preserves order and advances raw paging', async () => {
  const p = page(); const result = await p.request('collection', {uri: `spotify:playlist:${playlist}`});
  assert.deepEqual(result.result.items.map(t => t.id), [seed, next]);
  assert.equal(result.result.nextOffset, 2); assert.equal(result.result.items[0].artwork, 'https://i.scdn.co/image/large');
});
test('radio uses Spotify radio metadata and never relays its bearer credential', async () => {
  const p = page(); const result = await p.request('radio', {id: seed});
  const radio = p.fetches.find(f => f.url.includes('/seed_to_playlist/'));
  assert(radio.url.includes(`spotify:track:${seed}`));
  assert.equal(radio.options.headers.Authorization, 'Bearer PRIVATE_BEARER');
  assert.equal(result.result.items.length, 2); assert(!JSON.stringify(result).includes('PRIVATE_BEARER'));
});
test('invalid identifiers and mutation/player commands cannot reach provider APIs', async () => {
  const p = page();
  for (const [operation, args] of [['radio', {id: '../user?secret'}], ['collection', {uri: 'https://attacker.example'}], ['player.play', {}], ['library.remove', {}]]) {
    assert.equal((await p.request(operation, args)).error, 'BAD_REQUEST');
  }
  assert.equal(p.calls.length, 0); assert.equal(p.fetches.length, 0);
});
test('provider exceptions are reduced to safe error codes', async () => {
  const p = page({failure: true}); const result = await p.request('home');
  assert.equal(result.error, 'CATALOGUE_UNAVAILABLE'); assert(!JSON.stringify(result).includes('SECRET'));
});
test('bridge is absent off-origin/in subframes and rejects forged message channels', () => {
  assert.equal(page({origin: 'https://attacker.example'}).events.size, 0);
  assert.equal(page({topFrame: false}).events.size, 0);
  for (const event of [{origin: 'https://attacker.example'}, {source: {}}, {isTrusted: false}]) {
    const p = page(); const other = {}; p.bind({...event, ports: [other]}); assert.equal(other.onmessage, undefined);
  }
});

test('a transient build-asset failure can be retried without losing the account', async () => {
  const p = page({assetFailureOnce: true});
  assert.equal((await p.request('home')).error, 'PAGE_CHANGED');
  assert.equal((await p.request('home')).result.sections[0].title, 'Made for you');
});
