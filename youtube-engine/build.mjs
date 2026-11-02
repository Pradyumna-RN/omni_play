import { build } from 'esbuild';
import { mkdir, writeFile } from 'node:fs/promises';
import { readFileSync, readdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
const target = new URL('../app/src/main/assets/engine/', import.meta.url);
await mkdir(target, { recursive: true });
await build({ entryPoints: ['src/engine.mjs'], absWorkingDir: new URL('.', import.meta.url).pathname,
  bundle: true, platform: 'browser', format: 'iife', target: 'chrome100', keepNames: true,
  minify: true, legalComments: 'eof', outfile: new URL('engine.js', target).pathname });
await build({ entryPoints: ['src/proof.mjs'], absWorkingDir: new URL('.', import.meta.url).pathname,
  bundle: true, platform: 'browser', format: 'iife', target: 'chrome100', keepNames: true,
  minify: true, legalComments: 'eof', outfile: new URL('proof.js', target).pathname });
await writeFile(new URL('index.html', target), `<!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'self' 'unsafe-eval'; worker-src blob:; connect-src 'none'"><script src="engine.js"></script>`);
const inputs = ['build.mjs', 'package.json', 'package-lock.json', ...readdirSync(new URL('src/', import.meta.url), { recursive: true }).filter(f => f.endsWith('.mjs')).map(f => 'src/' + f)].sort();
const hash = createHash('sha256');
for (const name of inputs) hash.update('youtube-engine/' + name).update('\0').update(readFileSync(new URL(name, import.meta.url))).update('\0');
await writeFile(new URL('manifest.json', target), JSON.stringify({ client: 'youtubei.js 18.3.0', sourceDigest: hash.digest('hex'), bundleDigest: createHash('sha256').update(readFileSync(new URL('engine.js', target))).digest('hex'), proofDigest: createHash('sha256').update(readFileSync(new URL('proof.js', target))).digest('hex') }, null, 2) + '\n');
