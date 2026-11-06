import { track, isMusic } from './mapping.mjs';

const video = /^[A-Za-z0-9_-]{11}$/;
const playlist = /^[A-Za-z0-9_-]{2,160}$/;
const fail = (code, message) => Object.assign(new Error(message), { code });
const text = value => typeof value === 'string' ? value : value?.simpleText || value?.runs?.map(r => r.text || '').join('') || '';
export const libraryMethods = new Set(['playlists', 'playlist-create', 'playlist-add', 'playlist-remove', 'playlist-rename', 'playlist-delete', 'playlist-tracks', 'rate-song']);

function playlistId(value) {
  const id = String(value || '').replace(/^VL/, '');
  if (!playlist.test(id)) throw fail('PLAYLIST', 'Choose a valid YouTube playlist.');
  return id;
}
function videoId(value) {
  if (!video.test(value || '')) throw fail('LINK', 'Choose a YouTube song to sync. Local files stay on this device.');
  return value;
}
function title(value) {
  const name = String(value || '').trim();
  if (!name || name.length > 150) throw fail('PLAYLIST', 'Enter a playlist name of 1–150 characters.');
  return name;
}

/** Read the account's actual editable choices, including membership for a selected song. */
export function playlistOptions(data) {
  const result = [], seen = new Set();
  function visit(node, depth = 0) {
    if (!node || typeof node !== 'object' || depth > 30) return;
    const option = node.playlistAddToOptionRenderer;
    if (option && option.disabled !== true && option.playlistId !== 'LL' && playlist.test(option.playlistId || '') && !seen.has(option.playlistId)) {
      const name = text(option.title);
      if (name && option.playlistId !== 'WL') {
        seen.add(option.playlistId);
        result.push({ id: option.playlistId, title: name, remote: true, editable: true,
          contains: option.containsSelectedVideos === 'ALL', privacy: option.privacy || 'PRIVATE' });
      }
    }
    for (const value of Object.values(node)) {
      if (Array.isArray(value)) value.forEach(item => visit(item, depth + 1));
      else if (value && typeof value === 'object') visit(value, depth + 1);
    }
  }
  visit(data);
  return result;
}

export async function searchSuggestions(api, query) {
  const input = String(query || '').trim().slice(0, 400);
  if (!input || /^https?:\/\//i.test(input)) return { suggestions: [] };
  const page = await api.music.getSearchSuggestions(input), seen = new Set(), suggestions = [];
  for (const section of page || []) for (const node of section.contents || []) {
    const song = isMusic(node) ? track(node) : null;
    const query = song ? song.title : text(node.suggestion);
    const key = song?.id || query.toLocaleLowerCase();
    if (!query || seen.has(key)) continue;
    seen.add(key); suggestions.push({ text: query, ...(song ? { track: song } : {}) });
    if (suggestions.length === 8) return { suggestions };
  }
  return { suggestions };
}

/** One instance per web session: account changes discard all playlist cursors and permissions. */
export function createLibraryActions() {
  const pages = new Map(), owned = new Set();
  let pageSession, pageSignedIn;
  async function choices(api, selected) {
    const response = await api.actions.execute('/playlist/get_add_to_playlist', {
      client: 'YTMUSIC', videoIds: selected ? [videoId(selected)] : [], excludeWatchLater: true,
    });
    if (!response.success) throw fail('PLAYLIST', 'YouTube could not load your playlists. Reconnect or retry.');
    const options = playlistOptions(response.data);
    owned.clear(); options.forEach(option => owned.add(option.id));
    return options;
  }
  async function editable(api, id) {
    if (!owned.has(id)) await choices(api);
    if (!owned.has(id)) throw fail('PLAYLIST_READ_ONLY', 'This playlist is not editable in the connected YouTube account.');
  }
  async function edit(api, id, action) {
    await editable(api, id);
    const response = await api.actions.execute('/browse/edit_playlist', { client: 'YTMUSIC', playlistId: id, actions: [action] });
    if (!response.success || response.data?.status !== 'STATUS_SUCCEEDED')
      throw fail('PLAYLIST_UNCONFIRMED', 'YouTube did not confirm the playlist change. Refresh the playlist before retrying.');
    pages.delete(id);
    return { success: true };
  }
  return async (api, method, args = {}) => {
    const signedIn = !!api.session.logged_in;
    if (pageSession !== api.session || pageSignedIn !== signedIn) {
      pages.clear(); owned.clear(); pageSession = api.session; pageSignedIn = signedIn;
    }
    if (!api.session.logged_in && method !== 'playlist-tracks') throw fail('AUTH_REQUIRED', 'Sign in to YouTube Music to sync playlists and likes.');
    if (method === 'playlists') return { playlists: await choices(api, args.videoId) };
    if (method === 'rate-song') {
      const id = videoId(args.videoId);
      const rate = { LIKE: 'like', DISLIKE: 'dislike', INDIFFERENT: 'removelike' }[args.rating];
      if (!rate) throw fail('OPERATION', 'Choose a valid rating.');
      const response = await api.actions.execute('/like/' + rate, { client: 'YTMUSIC', target: { videoId: id } });
      if (!response.success) throw fail('RATING', 'YouTube did not confirm this rating.');
      return { success: true };
    }
    if (method === 'playlist-create') {
      const ids = Array.isArray(args.videoIds) ? args.videoIds.map(videoId) : args.videoId ? [videoId(args.videoId)] : [];
      if (ids.length > 100) throw fail('PLAYLIST', 'Choose at most 100 songs for one playlist creation.');
      const response = await api.playlist.create(title(args.title), ids, 'PRIVATE');
      if (!response.success || !playlist.test(response.playlist_id || ''))
        throw fail('PLAYLIST_UNCONFIRMED', 'YouTube did not confirm creation. Refresh your playlists before retrying.');
      owned.add(response.playlist_id);
      return { playlist: { id: response.playlist_id, title: title(args.title), remote: true, editable: true, contains: !!ids.length, privacy: 'PRIVATE' } };
    }
    const id = playlistId(args.id);
    if (method === 'playlist-tracks') {
      const session = args.importSession || '';
      if (typeof session !== 'string' || session.length > 36 || session && !/^[a-f0-9-]+$/.test(session)) throw fail('PLAYLIST', 'Choose a valid import session.');
      const key = session ? `${id}:${session}` : id;
      const previous = pages.get(key);
      if (args.more && !previous) throw fail('PLAYLIST_CURSOR', 'Reopen the playlist before loading more.');
      if (args.more && !previous.has_continuation) return {tracks: [], hasMore: false, skipped: 0};
      const page = args.more ? await previous.getContinuation() : await api.music.getPlaylist(id);
      pages.set(key, page);
      if (pages.size > 24) pages.delete(pages.keys().next().value);
      const tracks = (page.contents || []).map(node => {
        const song = track(node);
        if (!song) return null;
        const remove = node.menu?.items?.find(item => item.endpoint?.payload?.actions?.some(a => a.setVideoId));
        const setId = remove?.endpoint?.payload?.actions?.find(a => a.setVideoId)?.setVideoId || node.set_video_id || '';
        return { ...song, playlistItemId: setId };
      }).filter(Boolean);
      return {tracks, skipped: Math.max(0, (page.contents || []).length - tracks.length), title: String(page.title || page.header?.title || ''), hasMore: !!page.has_continuation, editable: owned.has(id)};
    }
    if (method === 'playlist-add') return edit(api, id, { action: 'ACTION_ADD_VIDEO', addedVideoId: videoId(args.videoId) });
    if (method === 'playlist-remove') {
      if (!/^[A-Za-z0-9_=-]{1,300}$/.test(args.itemId || '')) throw fail('PLAYLIST', 'Refresh this playlist to identify the song occurrence to remove.');
      return edit(api, id, { action: 'ACTION_REMOVE_VIDEO', setVideoId: args.itemId });
    }
    if (method === 'playlist-rename') return edit(api, id, { action: 'ACTION_SET_PLAYLIST_NAME', playlistName: title(args.title) });
    if (method === 'playlist-delete') {
      await editable(api, id);
      const response = await api.actions.execute('/playlist/delete', { client: 'YTMUSIC', playlistId: id });
      if (!response.success) throw fail('PLAYLIST_UNCONFIRMED', 'YouTube did not confirm deletion. Refresh your playlists before retrying.');
      owned.delete(id); pages.delete(id); return { success: true };
    }
    throw fail('OPERATION', 'Unsupported library operation.');
  };
}
