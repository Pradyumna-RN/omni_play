/** A worker has no Android Java bridge, DOM, account session, or network access. */
export function evaluatePlayer(data) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(new Blob([`onmessage=e=>{try{postMessage({ok:true,value:new Function(e.data)()})}catch(_){postMessage({ok:false})}}`], { type: 'text/javascript' }));
    const worker = new Worker(url);
    const stop = () => { worker.terminate(); URL.revokeObjectURL(url); clearTimeout(timer); };
    const timer = setTimeout(() => { stop(); reject(new Error('Player interpreter timeout')); }, 12000);
    worker.onmessage = e => { stop(); e.data.ok ? resolve(e.data.value) : reject(new Error('Player interpretation failed')); };
    worker.onerror = () => { stop(); reject(new Error('Player interpretation failed')); };
    worker.postMessage(data.output);
  });
}
