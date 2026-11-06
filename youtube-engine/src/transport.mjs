export function nativeTransport(bridge, callbackName) {
  const pending = new Map(); let sequence = 0;
  const finish = id => {
    const item = pending.get(id); if (!item) return null;
    pending.delete(id); clearTimeout(item.timer); item.cleanup?.(); return item;
  };
  globalThis[callbackName] = (id, response) => {
    const item = finish(id); if (!item) return;
    if (response.error) { item.reject(new Error('Network request failed')); return; }
    const bytes = Uint8Array.from(atob(response.body || ''), c => c.charCodeAt(0));
    item.resolve(new Response([204, 205, 304].includes(response.status) ? null : bytes,
      { status: response.status, headers: response.headers || {} }));
  };
  const fetch = async (input, init = {}) => {
    const req = input instanceof Request ? input : new Request(input, init);
    const body = init.body == null ? (['GET', 'HEAD'].includes(req.method) ? '' : await req.clone().text()) : String(init.body);
    const signal = init.signal || req.signal;
    if (signal?.aborted) throw new DOMException('Request cancelled', 'AbortError');
    return new Promise((resolve, reject) => {
      const id = String(++sequence);
      const abort = () => { const item = finish(id); if (item) { bridge.cancelHttp(id); item.reject(new DOMException('Request cancelled', 'AbortError')); } };
      const timer = setTimeout(() => { const item = finish(id); if (item) { bridge.cancelHttp(id); item.reject(new Error('Network timeout')); } }, 25000);
      pending.set(id, { resolve, reject, timer, key: init.cancelKey, abort,
        cleanup: () => signal?.removeEventListener('abort', abort) });
      signal?.addEventListener('abort', abort, { once: true });
      bridge.http(id, req.url, JSON.stringify({ method: init.method || req.method,
        headers: Object.fromEntries(new Headers(init.headers || req.headers)), body }));
    });
  };
  fetch.cancel = key => { for (const item of [...pending.values()]) if (item.key === key) item.abort(); };
  return fetch;
}
