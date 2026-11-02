export function cookieValue(cookie, name) {
  for (const part of String(cookie || '').split(';')) { const i = part.indexOf('='); if (i > 0 && part.slice(0, i).trim() === name) return part.slice(i + 1).trim(); }
  return '';
}
async function sha1Hex(input) {
  const digest = await crypto.subtle.digest('SHA-1', new TextEncoder().encode(input));
  return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, '0')).join('');
}
/** The same SAPISIDHASH (plus 1P/3P variants) a browser sends from the given origin. */
export async function sidAuthorization(cookie, origin, now = Date.now()) {
  const ts = Math.floor(now / 1000); const parts = [];
  for (const [scheme, name] of [['SAPISIDHASH', 'SAPISID'], ['SAPISID1PHASH', '__Secure-1PAPISID'], ['SAPISID3PHASH', '__Secure-3PAPISID']]) {
    const value = cookieValue(cookie, name) || (scheme === 'SAPISIDHASH' ? cookieValue(cookie, '__Secure-3PAPISID') : '');
    if (value) parts.push(`${scheme} ${ts}_${await sha1Hex(`${ts} ${value} ${origin}`)}`);
  }
  return parts.join(' ');
}
