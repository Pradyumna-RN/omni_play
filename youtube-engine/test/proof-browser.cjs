// Google's attestation protocol is represented by fixtures here, never a live token.
const { chromium } = require('playwright-core');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const assert = require('node:assert/strict');
const script = fs.readFileSync(path.resolve(__dirname, '../../app/src/main/assets/engine/proof.js'));
const vm = `globalThis.fixtureVM={a:function(program,setup){if(typeof MusicNative!=="undefined")throw Error("account bridge leaked");setup(function(done,options){options[2].push(async function(integrity){return async function(binding){return new Uint8Array([70,...binding])}});done("fixture-snapshot")},function(){},function(){},function(){});return [function(){}]}};`;
(async () => {
  const server = http.createServer((req, res) => {
    res.setHeader('Content-Type', req.url === '/proof.js' ? 'application/javascript' : 'text/html');
    res.end(req.url === '/proof.js' ? script : `<!doctype html><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' 'unsafe-eval'; connect-src 'none'"><script src="/proof.js"></script>`);
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const browser = await chromium.launch({ executablePath: process.env.BROWSER_EXECUTABLE || '/usr/bin/chromium', headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage(); const calls = []; let blocked = false;
    await page.exposeFunction('httpNative', async (id, url, options) => {
      const o = JSON.parse(options); calls.push({ url, ...o });
      let data;
      if (url.endsWith('/GenerateIT')) { assert.deepEqual(JSON.parse(o.body), ['O43z0dpjhgX20SCx4KAo', 'fixture-snapshot']); data = [Buffer.from('fixture-integrity').toString('base64'), 120, 30, '']; }
      else throw Error('Unexpected proof host or endpoint');
      await page.evaluate(({ id, payload }) => globalThis.proofHttpResult(id, payload), { id, payload: { status: blocked ? 403 : 200, headers: { 'content-type': 'application/json' }, body: Buffer.from(JSON.stringify(data)).toString('base64') } });
    });
    await page.addInitScript(() => {
      window.__ready = false; window.__results = {};
      window.ProofNative = { ready() { window.__ready = true; }, result(id, json) { window.__results[id] = JSON.parse(json); }, http: (...args) => window.httpNative(...args), cancelHttp() {} };
    });
    await page.goto(`http://127.0.0.1:${server.address().port}/`); await page.waitForFunction(() => window.__ready);
    const args = { videoId: 'jNQXAC9IVRw', playerBinding: 'jNQXAC9IVRw', streamBinding: 'fixture-account||', challenge: { program: 'fixture-program', globalName: 'fixtureVM', interpreterJavascript: { privateDoNotAccessOrElseSafeScriptWrappedValue: vm } } };
    async function mint(id) {
      await page.evaluate(({ id, args }) => { window.proofCall(id, args); }, { id, args });
      await page.waitForFunction(id => !!window.__results[id], id);
      return page.evaluate(id => window.__results[id], id);
    }
    const result = await mint('one');
    assert.equal(Buffer.from(result.playerToken, 'base64url').toString(), 'F' + args.playerBinding, JSON.stringify(result));
    assert.equal(Buffer.from(result.streamToken, 'base64url').toString(), 'F' + args.streamBinding);
    await mint('two'); assert.equal(calls.filter(c => c.url.endsWith('/GenerateIT')).length, 1);
    assert(calls.every(c => c.url.startsWith('https://jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/') && !c.headers.cookie && !c.headers.authorization));
    assert.equal(await page.evaluate(() => typeof MusicNative), 'undefined');
    blocked = true; await page.reload(); await page.waitForFunction(() => window.__ready);
    const rejected = await mint('blocked'); assert(rejected.error); assert(!rejected.playerToken && !rejected.streamToken);
    console.log(JSON.stringify({ passed: ['exact isolated proof bundle', 'BgUtils supplied-challenge/snapshot/integrity/minter flow with fixture VM', 'player and authenticated-session bindings are distinct', 'short-lived minter reuse', 'no account bridge or cookie headers', 'rejected integrity cannot fabricate a token'], liveAttestation: false }, null, 2));
  } finally { await browser.close(); await new Promise(r => server.close(r)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
