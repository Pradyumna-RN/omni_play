export function nativeTransport(bridge, callbackName) {
  const pending = new Map(); let sequence = 0;
  globalThis[callbackName] = (id, response) => {
    const item = pending.get(id); if (!item) return;
    pending.delete(id); clearTimeout(item.timer);
    if (response.error) { item.reject(new Error('Network request failed')); return; }
    const bytes = Uint8Array.from(atob(response.body || ''), c => c.charCodeAt(0));
    item.resolve(new Response([204, 205, 304].includes(response.status) ? null : bytes,
      { status: response.status, headers: response.headers || {} }));
  };
  return async (input, init = {}) => {
    const req = input instanceof Request ? input : new Request(input, init);
    const body = init.body == null ? (['GET', 'HEAD'].includes(req.method) ? '' : await req.clone().text()) : String(init.body);
    return new Promise((resolve, reject) => {
      const id = String(++sequence);
      const timer = setTimeout(() => { pending.delete(id); bridge.cancelHttp(id); reject(new Error('Network timeout')); }, 25000);
      pending.set(id, { resolve, reject, timer });
      bridge.http(id, req.url, JSON.stringify({ method: init.method || req.method,
        headers: Object.fromEntries(new Headers(init.headers || req.headers)), body }));
    });
  };
}
