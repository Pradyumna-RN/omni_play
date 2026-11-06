import test from 'node:test';
import assert from 'node:assert/strict';
import { createLibraryActions, playlistOptions, searchSuggestions } from '../src/library-actions.mjs';

const choice = (id, contains = 'NONE') => ({ playlistAddToOptionRenderer: { playlistId: id, title: { simpleText: id }, containsSelectedVideos: contains, privacy: 'PRIVATE' } });
function fixture() {
  const requests = [];
  const api = {
    session: { logged_in: true },
    actions: { execute: async (path, body) => {
      requests.push({ path, body });
      return path.endsWith('get_add_to_playlist') ? { success: true, data: { contents: [{ addToPlaylistRenderer: { playlists: [choice('PLown'), choice('PLalready', 'ALL')] } }] } }
        : { success: true, data: { status: 'STATUS_SUCCEEDED' } };
    } },
    playlist: { create: async (...args) => { requests.push({ create: args }); return { success: true, playlist_id: 'PLnew' }; },
      delete: async id => { requests.push({ delete: id }); return { success: true }; } },
    music: { getPlaylist: async () => ({ contents: [], has_continuation: false }) },
  };
  return { api, requests, call: createLibraryActions() };
}
test('playlist picker preserves provider order and membership, excluding duplicate and system lists', () => {
  assert.deepEqual(playlistOptions({ contents: [choice('PLown'), choice('PLalready', 'ALL'), choice('PLown'), choice('WL'), choice('LL')] }).map(p => [p.id, p.contains]), [['PLown', false], ['PLalready', true]]);
});
test('listing the library works when YouTube refuses an empty song list', async () => {
  const { api, requests, call } = fixture(); const ok = api.actions.execute;
  api.actions.execute = async (path, body) => { if (path.endsWith('get_add_to_playlist') && !body.videoIds.length) throw new Error('Request failed with status code 400'); return ok(path, body); };
  const { playlists } = await call(api, 'playlists');
  assert.deepEqual(playlists.map(p => [p.id, p.contains]), [['PLown', false], ['PLalready', false]]);
  assert.equal(requests.length, 1);
  assert.equal(requests[0].body.videoIds.length, 1);
});
test('account operations require sign-in before making any request', async () => {
  const { api, requests, call } = fixture(); api.session.logged_in = false;
  for (const method of ['playlists', 'playlist-create', 'playlist-add', 'playlist-remove', 'playlist-rename', 'playlist-delete', 'rate-song'])
    await assert.rejects(call(api, method, { title: 'Private' }), e => e.code === 'AUTH_REQUIRED');
  assert.equal(requests.length, 0);
});
test('create is explicitly private and preserves a queue including duplicate song occurrences', async () => {
  const { api, requests, call } = fixture();
  const result = await call(api, 'playlist-create', { title: ' My songs ', videoIds: ['aaaaaaaaaaa', 'bbbbbbbbbbb', 'aaaaaaaaaaa'] });
  assert.deepEqual(requests[0].create, ['My songs', ['aaaaaaaaaaa', 'bbbbbbbbbbb', 'aaaaaaaaaaa'], 'PRIVATE']);
  assert.equal(result.playlist.id, 'PLnew');
  await assert.rejects(call(api, 'playlist-create', { title: '   ' }), e => e.code === 'PLAYLIST');
  await assert.rejects(call(api, 'playlist-create', { title: 'Local', videoIds: ['content://private'] }), e => e.code === 'LINK');
});
test('edits verify ownership and remove the exact server occurrence, not all copies of a recording', async () => {
  const { api, requests, call } = fixture();
  await call(api, 'playlist-add', { id: 'VLPLown', videoId: 'aaaaaaaaaaa' });
  await call(api, 'playlist-remove', { id: 'PLown', itemId: 'SECOND_OCCURRENCE' });
  await call(api, 'playlist-rename', { id: 'PLown', title: 'Renamed' });
  const edits = requests.filter(r => r.path === '/browse/edit_playlist');
  assert.deepEqual(edits.map(r => r.body.actions[0]), [
    { action: 'ACTION_ADD_VIDEO', addedVideoId: 'aaaaaaaaaaa' },
    { action: 'ACTION_REMOVE_VIDEO', setVideoId: 'SECOND_OCCURRENCE' },
    { action: 'ACTION_SET_PLAYLIST_NAME', playlistName: 'Renamed' },
  ]);
  assert(edits.every(r => r.body.client === 'YTMUSIC' && r.body.playlistId === 'PLown'));
  const before = edits.length;
  await assert.rejects(call(api, 'playlist-delete', { id: 'PLsomeoneelse' }), e => e.code === 'PLAYLIST_READ_ONLY');
  assert.equal(requests.filter(r => r.path === '/browse/edit_playlist').length, before);
  assert(!requests.some(r => r.delete === 'PLsomeoneelse'));
});
test('failed edit acknowledgments never become a success, and invalid ratings make no request', async () => {
  const { api, requests, call } = fixture();
  await call(api, 'playlists');
  api.actions.execute = async () => ({ success: true, data: { status: 'STATUS_FAILED' } });
  await assert.rejects(call(api, 'playlist-add', { id: 'PLown', videoId: 'aaaaaaaaaaa' }), e => e.code === 'PLAYLIST_UNCONFIRMED');
  await assert.rejects(call(api, 'rate-song', { videoId: 'aaaaaaaaaaa', rating: 'anything' }), e => e.code === 'OPERATION');
  assert(!requests.some(r => r.path?.startsWith('/like/')));
});
test('ratings use the Music client and a validated video target', async () => {
  const { api, requests, call } = fixture();
  for (const rating of ['LIKE', 'DISLIKE', 'INDIFFERENT']) await call(api, 'rate-song', { videoId: 'aaaaaaaaaaa', rating });
  assert.deepEqual(requests.map(r => r.path), ['/like/like', '/like/dislike', '/like/removelike']);
  assert(requests.every(r => r.body.client === 'YTMUSIC' && r.body.target.videoId === 'aaaaaaaaaaa'));
});
test('suggestions preserve server order, include playable songs and ignore links, duplicates and non-suggestions', async () => {
  const calls = [];
  const api = { music: { getSearchSuggestions: async query => { calls.push(query); return [{ contents: [
    { suggestion: { simpleText: 'the weeknd' } }, { suggestion: { simpleText: 'the weeknd' } },
    { title: 'Starboy', id: 'aaaaaaaaaaa', artists: [{ name: 'The Weeknd' }] }, { title: 'Unrelated non-song' },
  ] }]; } } };
  const result = await searchSuggestions(api, ' the wee ');
  assert.deepEqual(result.suggestions.map(s => s.text), ['the weeknd', 'Starboy']);
  assert.equal(result.suggestions[1].track.artist, 'The Weeknd');
  assert.equal(result.suggestions[1].track.url, 'https://www.youtube.com/watch?v=aaaaaaaaaaa');
  await searchSuggestions(api, ''); await searchSuggestions(api, 'https://youtu.be/aaaaaaaaaaa');
  assert.deepEqual(calls, ['the wee']);
});
test('public playlist import reads do not require sign-in; writes remain authenticated', async () => {
  const {api, call} = fixture(); api.session.logged_in = false;
  assert.equal((await call(api, 'playlist-tracks', {id: 'PLpublic'})).hasMore, false);
  await assert.rejects(call(api, 'playlist-add', {id: 'PLpublic', videoId: 'aaaaaaaaaaa'}), e => e.code === 'AUTH_REQUIRED');
});
test('import sessions do not consume the playlist browser cursor and exhausted reads do not reload', async () => {
  const {api, call} = fixture(); let initial = 0;
  api.music.getPlaylist = async () => {
    const title = `Page ${++initial}`;
    return {title, contents: [], has_continuation: true, getContinuation: async () => ({title: title + ' continued', contents: [], has_continuation: false})};
  };
  await call(api, 'playlist-tracks', {id: 'PLpublic'});
  await call(api, 'playlist-tracks', {id: 'PLpublic', importSession: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'});
  assert.equal((await call(api, 'playlist-tracks', {id: 'PLpublic', more: true})).title, 'Page 1 continued');
  assert.equal((await call(api, 'playlist-tracks', {id: 'PLpublic', more: true, importSession: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'})).title, 'Page 2 continued');
  assert.equal((await call(api, 'playlist-tracks', {id: 'PLpublic', more: true})).hasMore, false);
  assert.equal(initial, 2);
  await assert.rejects(call(api, 'playlist-tracks', {id: 'PLmissing', more: true}), e => e.code === 'PLAYLIST_CURSOR');
});

test('sign-out invalidates private playlist continuation state before allowing public reads', async () => {
  const {api, call} = fixture(); let continued = false;
  api.music.getPlaylist = async () => ({contents: [], has_continuation: true, getContinuation: async () => { continued = true; return {contents: [], has_continuation: false}; }});
  await call(api, 'playlist-tracks', {id: 'PLprivate'});
  api.session.logged_in = false;
  await assert.rejects(call(api, 'playlist-tracks', {id: 'PLprivate', more: true}), e => e.code === 'PLAYLIST_CURSOR');
  assert.equal(continued, false);
});
