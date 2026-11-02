import { BotGuardClient } from 'bgutils-js/botguard';
import { WebPoMinter } from 'bgutils-js/webpo';
import { buildURL, getHeaders } from 'bgutils-js/utils';
import { nativeTransport } from './transport.mjs';

// Separate WebView: no account vault, cookies, or MusicNative interface exists here.
const native = globalThis.ProofNative;
const fetchProvider = nativeTransport(native, 'proofHttpResult');
for (const method of ['log', 'info', 'debug', 'warn', 'error']) console[method] = () => {};
let minterPromise, expires = 0;
const REQUEST_KEY = 'O43z0dpjhgX20SCx4KAo';
async function minter(challenge) {
  if (!minterPromise || Date.now() >= expires) {
    minterPromise = (async () => {
      let code = challenge.interpreterJavascript?.privateDoNotAccessOrElseSafeScriptWrappedValue;
      if (!code) {
        const address = challenge.interpreterUrl?.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue;
        if (!address) throw new Error('No attestation interpreter');
        const response = await fetchProvider(address.startsWith('//') ? 'https:' + address : address);
        if (!response.ok) throw new Error('Attestation interpreter rejected');
        code = await response.text();
      }
      new Function(code)();
      const client = await BotGuardClient.create({ program: challenge.program, globalName: challenge.globalName, globalObject: globalThis });
      const webPoSignalOutput = [];
      const snapshot = await client.snapshot({ webPoSignalOutput });
      const response = await fetchProvider(buildURL('GenerateIT', false), { method: 'POST', headers: getHeaders(), body: JSON.stringify([REQUEST_KEY, snapshot]) });
      if (!response.ok) throw new Error('Provider declined runtime attestation');
      const [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken] = await response.json();
      if (!integrityToken) throw new Error('No integrity response');
      expires = Date.now() + Math.max(1, Math.min(estimatedTtlSecs || 300, 300) - 30) * 1000;
      return WebPoMinter.create({ integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken }, webPoSignalOutput);
    })().catch(error => { minterPromise = undefined; throw error; });
  }
  return minterPromise;
}
globalThis.proofCall = async (id, args) => {
  try {
    if (!/^[A-Za-z0-9_-]{11}$/.test(args.videoId) || !args.playerBinding || !args.streamBinding || args.playerBinding.length > 4096 || args.streamBinding.length > 4096 || !args.challenge?.program || !args.challenge?.globalName) throw new Error('Invalid proof binding or challenge');
    const generator = await minter(args.challenge);
    const playerToken = await generator.mintAsWebsafeString(args.playerBinding);
    const streamToken = await generator.mintAsWebsafeString(args.streamBinding);
    native.result(id, JSON.stringify({ playerToken, streamToken }));
  } catch { native.result(id, JSON.stringify({ error: 'YouTube runtime verification did not complete.' })); }
};
native.ready();
