import { sanitizeReason } from './web-config.mjs';
const videoPattern = /^[A-Za-z0-9_-]{11}$/;
const text = value => value == null ? '' : String(value);
export function entry(node) {
  if (!node) return null;
  const payload = node.endpoint?.payload || {};
  if (node.type === 'MusicNavigationButton' && !payload.browseId) return null;
  const candidate = payload.videoId || node.id;
  const videoId = videoPattern.test(candidate || '') ? candidate : null;
  const title = text(node.title || node.name || node.button_text);
  if (!title) return null;
  let thumbs = node.thumbnails || node.thumbnail?.contents || node.thumbnail || [];
  if (!Array.isArray(thumbs)) thumbs = [];
  const artwork = largestArtwork(thumbs);
  const linked = value => (value?.runs || []).filter(r => r.endpoint?.payload?.browseId?.startsWith('UC')).map(r => r.text);
  const names = [...(node.artists || []), ...(node.authors || [])].map(a => a.name).filter(Boolean);
  const artist = [...new Set([...names, node.author?.name, ...linked(node.subtitle), ...(node.flex_columns || []).flatMap(c => linked(c.title))].filter(Boolean))].join(', ');
  // Some shelves (Quick picks) carry the artist only as plain text in the second column: "Artist • Album".
  const column = (node.flex_columns?.[1]?.title?.runs || []).map(r => r.text).filter(t => t && !/^\s*[•·]\s*$/.test(t));
  const plain = column.filter(t => !/^(Song|Video|Single|Album|EP|Playlist)$/i.test(t.trim())).slice(0, 1).join('');
  const kind = videoId ? 'song' : node.type === 'MusicNavigationButton' ? 'browse' : (node.item_type || 'browse');
  const browseId = payload.browseId || payload.playlistId || node.id || '';
  return { id: videoId ? `yt:${videoId}` : kind === 'browse' && payload.params ? `${browseId}:${payload.params}` : browseId, title,
    subtitle: artist || text(node.subtitle) || plain, artist: artist || (videoId ? plain : ''),
    artwork, kind, browseId, ...(kind === 'browse' && payload.params ? { params: payload.params } : {}), videoId, durationMs: (node.duration?.seconds || 0) * 1000 };
}
export function track(node) {
  const item = entry(node);
  return item?.videoId ? { id: item.id, title: item.title, artist: item.artist,
    artwork: item.artwork, durationMs: item.durationMs, source: 'YOUTUBE',
    url: `https://www.youtube.com/watch?v=${item.videoId}` } : null;
}
const musicConfig = payload => payload?.watchEndpointMusicSupportedConfigs?.watchEndpointMusicConfig?.musicVideoType;
/** YouTube Music's own label for a playable item: ATV (audio track), OMV/UGC (video), PODCAST_EPISODE. */
export function videoType(node) {
  return musicConfig(node?.endpoint?.payload) || musicConfig(node?.flex_columns?.[0]?.title?.runs?.[0]?.endpoint?.payload)
    || musicConfig(node?.overlay?.content?.endpoint?.payload) || musicConfig(node?.thumbnail_overlay?.content?.endpoint?.payload) || '';
}
/** Songs, albums, playlists and artists only: no music videos, podcasts, episodes or spoken audio. */
export function isMusic(node) {
  if (!node || node.type === 'MusicMultiRowListItem') return false; // podcast episodes
  const page = node.endpoint?.payload?.browseEndpointContextSupportedConfigs?.browseEndpointContextMusicConfig?.pageType || '';
  if (/PODCAST|EPISODE|NON_MUSIC/.test(page)) return false;
  const type = videoType(node);
  // The video type is language independent; item_type is only a fallback (it reads the subtitle text).
  if (type) return !/UGC|OMV|PODCAST|EPISODE|SHORTS|LIVE/.test(type);
  return !['video', 'podcast_show', 'non_music_track', 'episode'].includes(node.item_type);
}
/** Radio rows that come as a video with an audio counterpart (or the other way round) play the audio one. */
export function audioVersion(node) {
  if (node?.type !== 'PlaylistPanelVideoWrapper') return node;
  return [node.primary, ...(node.counterpart || [])].find(v => v && videoType(v) === 'MUSIC_VIDEO_TYPE_ATV') || node.primary;
}
const timeToSeconds = text => String(text || '').trim().split(':').reduce((total, part) => total * 60 + (parseInt(part.replace(/\D/g, ''), 10) || 0), 0);
const lockupArtwork = image => {
  if (!image) return '';
  const target = image.primary_thumbnail ?? image;
  return largestArtwork(target.image ?? target.thumbnails ?? []);
};
/** A regular YouTube home-feed item: full videos and movies only; no Shorts, playlists or channels. */
export function videoEntry(node) {
  if (!node) return null;
  const type = node.type || '';
  let id = '', title = '', channel = '', artwork = '', durationSec = 0;
  if (type === 'LockupView') {
    if (node.content_type !== 'VIDEO' && node.content_type !== 'MOVIE' && node.content_type !== 'PODCAST') return null;
    id = node.content_id || '';
    title = text(node.metadata?.title?.toString?.());
    const rows = node.metadata?.metadata?.metadata_rows || [];
    const firstRow = (rows[0]?.metadata_parts || []).map(part => part.text?.toString()).filter(Boolean).join(' ');
    channel = firstRow;
    artwork = lockupArtwork(node.content_image);
    durationSec = node.duration?.seconds || 0;
  } else if (type === 'Video' || type === 'GridVideo' || type === 'CompactVideo' || type === 'PlaylistVideo') {
    id = node.video_id || '';
    title = text(node.title?.toString?.());
    channel = text(node.author?.name || node.byline_text?.toString?.());
    artwork = largestArtwork(node.thumbnails || []);
    durationSec = node.duration?.seconds ?? (typeof node.duration === 'object' && node.duration?.text ? timeToSeconds(node.duration.text) : 0);
  } else return null;
  if (!videoPattern.test(id) || !title) return null;
  return { id: `yt:${id}`, title, subtitle: channel, artist: channel, artwork,
    kind: 'song', browseId: '', videoId: id, durationMs: Math.max(0, durationSec) * 1000 };
}
/** The YouTube (videos) home feed, kept in the provider's order; videos only. */
export function videoSections(feed) {
  const shelves = feed.shelves || [];
  const inShelves = new Set();
  const sections = [];
  for (const shelf of shelves) {
    const items = (shelf.contents || []).map(node => { inShelves.add(node); return videoEntry(node); }).filter(Boolean);
    const title = text(shelf.title?.toString?.()) || 'More on YouTube';
    if (items.length) sections.push({ title, items });
  }
  const loose = (feed.videos || []).filter(node => !inShelves.has(node)).map(videoEntry).filter(Boolean);
  if (loose.length) sections.unshift({ title: 'From YouTube', items: loose });
  return sections;
}
/** Track-shaped mapping for WEB search results (same shape as [track]). */
export function videoTrack(node) {
  const item = videoEntry(node);
  return item ? { id: item.id, title: item.title, artist: item.artist, artwork: item.artwork,
    durationMs: item.durationMs, source: 'YOUTUBE', url: `https://www.youtube.com/watch?v=${item.videoId}` } : null;
}
export function sections(feed) {
  // Keep the provider's section, item and duplicate order. No app ranking; music only.
  return (feed.sections || []).map(section => ({ title: text(section.header?.title || section.title) || 'From YouTube Music',
    items: (section.contents || []).filter(isMusic).map(entry).filter(Boolean) })).filter(s => s.items.length);
}
export function largestArtwork(thumbs = []) {
  return [...thumbs].filter(t => t.url?.startsWith('https://')).sort((a, b) => (b.width || 0) * (b.height || 0) - (a.width || 0) * (a.height || 0))[0]?.url || '';
}
export function safeError(error) {
  if (error?.code && /^[A-Z_0-9]{2,48}$/.test(error.code)) return { code: error.code, message: sanitizeReason(error.message), diagnostic: sanitizeReason(error.diagnostic || error.code) };
  if (error?.info?.status) return { code: 'PLAYER_REJECTED', message: sanitizeReason(error.info.reason || error.message), diagnostic: sanitizeReason(error.info.status) };
  const m = String(error?.message || error || '').toLowerCase();
  if (/403/.test(m)) return { code: 'HTTP_403', message: 'YouTube refused this request (HTTP 403). Retry or sign in to YouTube.', diagnostic: 'provider HTTP 403' };
  if (/401|invalid_grant|expired_token/.test(m)) return { code: 'AUTH_REQUIRED', message: 'Your YouTube session needs to be connected again.', diagnostic: 'provider authentication rejected' };
  if (/network|timeout|failed to fetch/.test(m)) return { code: 'NETWORK', message: 'Could not reach YouTube. Check your connection and retry.', diagnostic: 'provider network failed' };
  if (/sign in|bot|verification/.test(m)) return { code: 'VERIFICATION_REQUIRED', message: 'YouTube requires verification. Sign in to YouTube in Settings and retry.', diagnostic: 'provider verification required' };
  if (/player|decipher|signature|nsig/.test(m)) return { code: 'PLAYER_SCRIPT', message: 'Could not read YouTube’s current player script. Check for an app update.', diagnostic: 'player script extraction failed' };
  return { code: 'PROVIDER_ERROR', message: 'YouTube could not complete the request. Retry or check for an app update.', diagnostic: 'provider response could not be read' };
}
