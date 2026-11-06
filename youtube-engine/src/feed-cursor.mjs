/** The parser exposes cursor locations, although HomeFeed keeps its working cursor private. */
export function homeCursor(page) {
  const contents = page?.page?.contents;
  const root = typeof contents?.item === 'function' ? contents.item() : contents?.[0];
  return page?.page?.continuation_contents?.continuation ||
    root?.tabs?.find(tab => tab.selected)?.content?.continuation || null;
}
export function advanceHomeCursor(seen, page, more) {
  if (!more) seen.clear();
  const cursor = homeCursor(page);
  if (cursor && seen.has(cursor)) throw Object.assign(new Error('YouTube repeated a feed page. Pull down to refresh before trying again.'), { code: 'FEED_CONTINUATION' });
  if (cursor) seen.add(cursor);
}
