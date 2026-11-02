const videoPattern = /^[A-Za-z0-9_-]{11}$/;
const text = value => value == null ? '' : String(value);
export function entry(node) {
  if (!node) return null;
  const payload = node.endpoint?.payload || {};
  const candidate = payload.videoId || node.id;
  const videoId = videoPattern.test(candidate || '') ? candidate : null;
  const title = text(node.title || node.name);
  if (!title) return null;
  let thumbs = node.thumbnails || node.thumbnail?.contents || node.thumbnail || [];
  if (!Array.isArray(thumbs)) thumbs = [];
  const artwork = [...thumbs].reverse().find(t => t.url?.startsWith('https://'))?.url || '';
  const artists = (node.artists || node.authors || []).map(a => a.name).filter(Boolean).join(', ');
  const kind = videoId ? 'song' : (node.item_type || 'browse');
  const browseId = payload.browseId || payload.playlistId || node.id || '';
  return { id: videoId ? `yt:${videoId}` : browseId, title,
    subtitle: artists || node.author?.name || text(node.subtitle) || 'YouTube Music',
    artwork, kind, browseId, videoId, durationMs: (node.duration?.seconds || 0) * 1000 };
}
export function track(node) {
  const item = entry(node);
  return item?.videoId ? { id: item.id, title: item.title, artist: item.subtitle,
    artwork: item.artwork, durationMs: item.durationMs, source: 'YOUTUBE',
    url: `https://www.youtube.com/watch?v=${item.videoId}` } : null;
}
export function sections(feed) {
  // Keep the provider's section, item and duplicate order. No app ranking.
  return (feed.sections || []).map(section => ({ title: text(section.header?.title || section.title) || 'From YouTube Music',
    items: (section.contents || []).map(entry).filter(Boolean) })).filter(s => s.items.length);
}
export function safeError(error) {
  const m = String(error?.message || error || '').toLowerCase();
  if (m.includes('403')) return { code: 'HTTP_403', message: 'YouTube refused this request (HTTP 403). Try signing in or retry later.' };
  if (m.includes('401') || m.includes('invalid_grant') || m.includes('expired_token')) return { code: 'AUTH_REQUIRED', message: 'Your YouTube session needs to be connected again.' };
  if (m.includes('network') || m.includes('timeout') || m.includes('failed to fetch')) return { code: 'NETWORK', message: 'Could not reach YouTube. Check your connection and retry.' };
  if (m.includes('sign in') || m.includes('bot') || m.includes('verification')) return { code: 'VERIFICATION_REQUIRED', message: 'YouTube requires verification. Connect your account in Settings and retry.' };
  if (m.includes('unavailable') || m.includes('private') || m.includes('not playable')) return { code: 'UNAVAILABLE', message: 'This recording is unavailable to this account or region.' };
  return { code: 'PROVIDER_ERROR', message: 'YouTube could not complete the request. Retry or reconnect your account in Settings.' };
}
