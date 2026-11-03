const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
(async () => {
  const root = path.resolve('example/build/web');
  const types = {'.js': 'application/javascript', '.wasm': 'application/wasm', '.html': 'text/html', '.json': 'application/json'};
  const server = http.createServer((req, res) => {
    const pathname = new URL(req.url, 'http://localhost').pathname;
    if (pathname === '/sw.js') {
      res.setHeader('Content-Type', 'application/javascript');
      res.end("importScripts('/unified_call_worker.js'); self.addEventListener('install', e => e.waitUntil(self.skipWaiting())); self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));"); return;
    }
    if (['/unified_call_worker.js', '/unified_call_core.js'].includes(pathname)) {
      res.setHeader('Content-Type', 'application/javascript'); res.end(fs.readFileSync('web' + pathname)); return;
    }
    const file = path.resolve(root, '.' + new URL(req.url, 'http://localhost').pathname);
    if (!file.startsWith(root + path.sep) && file !== root) { res.writeHead(403); res.end(); return; }
    const target = file === root ? path.join(root, 'index.html') : file;
    fs.readFile(target, (error, bytes) => {
      if (error) { res.writeHead(404); res.end(); return; }
      res.setHeader('Content-Type', types[path.extname(target)] || 'application/octet-stream'); res.end(bytes);
    });
  }).listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  let browser;
  try {
    browser = await chromium.launch({channel: 'chrome', headless: true});
    const context = await browser.newContext();
    const a = await context.newPage(); const b = await context.newPage();
    const errors = [];
    for (const page of [a, b]) page.on('pageerror', error => errors.push(error.message));
    const url = `http://127.0.0.1:${server.address().port}/`;
    async function load(page) {
      await page.goto(url);
      await page.locator('flt-semantics-placeholder').evaluate(element => element.click());
      await page.getByText('Ready', {exact: true}).waitFor();
    }
    await load(a); await load(b);
    await a.getByRole('button', {name: 'Enable incoming audio', exact: true}).click();
    for (const workerMode of [false, true]) {
    if (workerMode) {
      await a.evaluate(async () => { await navigator.serviceWorker.register('/sw.js'); await navigator.serviceWorker.ready; });
      for (const page of [a, b]) await page.evaluate(() => UnifiedCallPage.invoke('initialize', {webUseServiceWorker: true}));
    }
    await a.getByRole('button', {name: 'Simulate incoming call', exact: true}).click();
    await b.getByRole('button', {name: 'Accept', exact: true}).waitFor();
    await b.getByRole('button', {name: 'Accept', exact: true}).click();
    await a.getByRole('button', {name: 'Mark media connected', exact: true}).waitFor();
    await a.getByRole('button', {name: 'Mark media connected', exact: true}).click();
    await b.bringToFront();
    await b.getByRole('group', {name: 'Alice — connected', exact: true}).waitFor();
    await b.getByRole('button', {name: 'End call', exact: true}).click();
    await a.bringToFront();
    await a.getByText('ended: Alice', {exact: true}).waitFor();
    await b.bringToFront();
    await b.getByText('ended: Alice', {exact: true}).waitFor();
    assert.deepEqual(await a.evaluate(async () => (await UnifiedCallCore.invoke('getActiveCalls')).value), []);
    await b.getByRole('button', {name: 'End call', exact: true}).waitFor({state: 'detached'});
    }
    assert.deepEqual(errors, []);
    console.log('PASS: compiled Flutter Web app, packaged JS assets, foreground + Worker cross-tab banner/accept/connect/end');
  } catch (error) {
    if (browser) for (const page of browser.contexts()[0].pages()) console.error(await page.locator('body').ariaSnapshot());
    throw error;
  } finally { if (browser) await browser.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
