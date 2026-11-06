import { Parser } from 'youtubei.js/web';
import { homeCursor } from './feed-cursor.mjs';

const fail = (code, message, diagnostic = code) => Object.assign(new Error(message), { code, diagnostic });
const label = value => typeof value === 'string' ? value : value?.simpleText || value?.runs?.map(run => run.text || '').join('') || '';
const nextToken = continuations => (continuations || []).map(item => item.nextContinuationData?.continuation || item.reloadContinuationData?.continuation).find(Boolean) || null;
const itemToken = items => (items || []).map(item => item.continuationItemRenderer?.continuationEndpoint?.continuationCommand?.token).find(Boolean) || null;

/** Read content containers, not menus or unrelated response metadata. Continuations need no Home tab. */
export function readBrowsePage(data, { continuation: following = false } = {}) {
  let contents, cursor;
  const shelfContinuation = data?.continuationContents?.musicShelfContinuation || data?.continuationContents?.gridContinuation;
  const continuation = data?.continuationContents?.sectionListContinuation
    || (shelfContinuation && { contents: [{ musicShelfRenderer: { contents: shelfContinuation.contents || shelfContinuation.items || [] } }], continuations: shelfContinuation.continuations });
  if (continuation) {
    contents = continuation.contents || [];
    cursor = nextToken(continuation.continuations) || itemToken(contents);
  } else {
    const actions = [...(data?.onResponseReceivedActions || []), ...(data?.onResponseReceivedEndpoints || []), ...(data?.onResponseReceivedCommands || [])]
      .map(action => action.appendContinuationItemsAction || action.reloadContinuationItemsCommand).filter(Boolean);
    if (actions.length) {
      contents = actions.flatMap(action => action.continuationItems || []);
      cursor = itemToken(contents);
    } else {
      const root = data?.contents;
      const tabs = root?.singleColumnBrowseResultsRenderer?.tabs || root?.twoColumnBrowseResultsRenderer?.tabs || [];
      const tab = tabs.map(node => node.tabRenderer).find(tab => tab?.selected) || (tabs.length === 1 ? tabs[0].tabRenderer : null);
      const list = tab?.content?.sectionListRenderer || root?.sectionListRenderer;
      // A page that follows another and carries no content is where the feed ends, not a failure.
      if (!list && following) return { sections: [], has_continuation: false, page: { continuation_contents: { continuation: null } } };
      if (!list || !Array.isArray(list.contents)) throw fail('BROWSE_RESPONSE', 'YouTube returned a page Music could not read. Your loaded music is kept. Retry or check for an update.', 'browse response has no supported content container');
      contents = list.contents;
      cursor = nextToken(list.continuations) || itemToken(contents);
    }
  }
  const shelves = [];
  function collect(nodes, depth = 0) {
    if (depth > 12) return;
    for (const node of nodes || []) {
      const nested = node.sectionListRenderer?.contents || node.itemSectionRenderer?.contents;
      if (nested) { collect(nested, depth + 1); continue; }
      const rich = node.richSectionRenderer?.content || node.richItemRenderer?.content;
      if (rich) { collect([rich], depth + 1); continue; }
      for (const [kind, body] of Object.entries(node || {})) {
        if (!/^(music.*ShelfRenderer|gridRenderer)$/.test(kind)) continue;
        const items = body.contents || body.items;
        if (!Array.isArray(items)) continue;
        const header = body.header && Object.values(body.header).find(value => value?.title);
        shelves.push({ title: label(body.title || header?.title) || (kind === 'gridRenderer' ? 'Browse' : 'From YouTube Music'), contents: Parser.parseArray(items) });
      }
    }
  }
  collect(contents);
  return { sections: shelves, has_continuation: !!cursor, page: { continuation_contents: { continuation: cursor } } };
}

export async function fetchBrowsePage(api, id, params = '') {
  if (!/^[A-Za-z0-9_-]{1,200}$/.test(id || '') || typeof params !== 'string' || params.length > 8192)
    throw fail('BROWSE_TARGET', 'Choose a valid YouTube Music category.');
  const response = await api.actions.execute('/browse', { client: 'YTMUSIC', browseId: id, ...(params ? { params } : {}) });
  if (!response.success) throw fail('BROWSE_REQUEST', 'YouTube Music could not load this category. Retry or reconnect your account.');
  return readBrowsePage(response.data);
}

export async function fetchBrowseContinuation(api, page) {
  const cursor = homeCursor(page);
  if (!cursor) return { sections: [], has_continuation: false, page: { continuation_contents: { continuation: null } } };
  const response = await api.actions.execute('/browse', { client: 'YTMUSIC', continuation: cursor });
  if (!response.success) throw fail('FEED_REQUEST', 'YouTube Music could not load the next page. Your loaded music is kept.');
  return readBrowsePage(response.data, { continuation: true });
}
