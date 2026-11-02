/** Parse JSON only. Never execute a downloaded watch-page HTML script. */
export function readYtConfig(html) {
  const merged = {};
  for (const marker of ['ytcfg.set(', 'ytcfg.data_ = ']) {
    let offset = 0;
    while ((offset = html.indexOf(marker, offset)) >= 0) {
      const start = html.indexOf('{', offset + marker.length);
      if (start < 0) break;
      let depth = 0, quoted = false, escape = false;
      for (let i = start; i < html.length; i++) {
        const c = html[i];
        if (quoted) { if (escape) escape = false; else if (c === '\\') escape = true; else if (c === '"') quoted = false; }
        else if (c === '"') quoted = true;
        else if (c === '{') depth++;
        else if (c === '}' && --depth === 0) {
          try { Object.assign(merged, JSON.parse(html.slice(start, i + 1))); } catch { /* Other inline statements are not configuration. */ }
          offset = i + 1; break;
        }
        if (i === html.length - 1) offset = html.length;
      }
    }
  }
  return merged;
}
export function sanitizeReason(value) {
  return String(value || '').replace(/https?:\/\/\S+/g, '[link]')
    .replace(/(?:Cookie|Authorization)\s*[:=]\s*[^|]+/gi, '[redacted]')
    .replace(/(?:SAPISID|SID|access_token|refresh_token|poToken|pot)\s*[:=]\s*[^\s;|]+/gi, '[redacted]')
    .replace(/[\r\n\t]/g, ' ').slice(0, 200);
}
export function playerFailure(client, info) {
  const status = info.playability_status?.status || 'UNKNOWN';
  const raw = sanitizeReason(info.playability_status?.reason || 'YouTube did not return playable audio.');
  const error = new Error(raw);
  error.code = /bot|verification/i.test(raw) ? 'VERIFICATION_REQUIRED' : status === 'LOGIN_REQUIRED' ? 'AUTH_REQUIRED' : 'PLAYER_REJECTED';
  error.diagnostic = `${client}: ${status}. ${raw}`;
  return error;
}
