const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const http = require('node:http');
const fs = require('node:fs');
const assert = require('node:assert/strict');
(async () => {
  const server = http.createServer((req, res) => {
    if (req.url === '/page.js') { res.setHeader('Content-Type', 'application/javascript'); res.end(fs.readFileSync('web/unified_call_page.js')); }
    else if (req.url === '/worker.js') { res.setHeader('Content-Type', 'application/javascript'); res.end(fs.readFileSync('web/unified_call_worker.js')); }
    else if (req.url === '/sw.js') { res.setHeader('Content-Type', 'application/javascript'); res.end("importScripts('/worker.js'); self.addEventListener('install', e => e.waitUntil(self.skipWaiting())); self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));"); }
    else if (req.url === '/core.js' || req.url === '/unified_call_core.js') { res.setHeader('Content-Type', 'application/javascript'); res.end(fs.readFileSync('web/unified_call_core.js')); }
    else res.end('<script src="/core.js"></script><script src="/page.js"></script>');
  }).listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  let browser;
  try {
    browser = await chromium.launch({channel: 'chrome', headless: true});
    const context = await browser.newContext();
    const a = await context.newPage(); const b = await context.newPage();
    const url = `http://127.0.0.1:${server.address().port}`;
    await Promise.all([a.goto(url), b.goto(url)]);
    await Promise.all([a.evaluate(() => UnifiedCallPage.invoke('initialize', {webEnableRingtone: false})), b.evaluate(() => UnifiedCallPage.invoke('initialize', {webEnableRingtone: false}))]);
    const call = {callId: 'one', callerName: 'Alice', mediaType: 'audio', expiresAt: Date.now() + 30000, extra: {room: 'test'}};
    const run = (page, method, args = {}) => page.evaluate(([method, args]) => UnifiedCallCore.invoke(method, args), [method, args]);
    await Promise.all([run(a, 'showIncomingCall', call), run(b, 'showIncomingCall', call)]);
    assert.equal((await run(a, 'getPendingEvents')).value.length, 1);
    await b.evaluate(call => UnifiedCallCore.invoke('showIncomingCall', {...call,
      callerName: 'Changed', expiresAt: call.expiresAt + 60000,
      extra: JSON.parse('{"room":"updated","nullable":null,"__proto__":{"safe":true}}')}), call);
    const merged = (await run(a, 'getActiveCalls')).value[0].config;
    assert.equal(merged.callerName, 'Alice');
    assert.equal(merged.expiresAt, call.expiresAt);
    assert.equal(merged.extra.room, 'updated');
    assert.equal(merged.extra.nullable, null);
    assert.equal(await a.evaluate(async () => {
      const extra = (await UnifiedCallCore.invoke('getActiveCalls')).value[0].config.extra;
      return Object.hasOwn(extra, '__proto__') && extra.__proto__.safe === true &&
        Object.getPrototypeOf(extra) === Object.prototype;
    }), true);
    assert.equal((await run(a, 'getPendingEvents')).value[0].call.extra.room, 'test');
    await run(a, 'showIncomingCall', {...call, expiresAt: Date.now() - 1, extra: {room: 'expired'}});
    assert.equal((await run(a, 'getActiveCalls')).value[0].config.extra.room, 'updated');
    await assert.rejects(run(a, 'setCallConnected', {callId: 'one'}), /Accept/);
    await Promise.all([run(a, 'acceptCall', {callId: 'one'}), run(b, 'declineCall', {callId: 'one'})]);
    assert.equal((await run(a, 'getPendingEvents')).value.length, 2);
    assert.equal((await run(a, 'getPendingEvents')).value[1].call.extra.room, 'updated');
    const active = (await run(a, 'getActiveCalls')).value;
    if (active.length) { await run(a, 'setCallConnected', {callId: 'one'}); await run(a, 'endCall', {callId: 'one'}); }
    await a.reload();
    const persisted = (await run(a, 'getPendingEvents')).value;
    await run(b, 'acknowledgeEvent', {eventId: persisted[0].eventId});
    assert.equal((await run(a, 'getPendingEvents')).value.length, persisted.length - 1);
    await run(a, 'showIncomingCall', {...call, callId: 'late', expiresAt: Date.now() + 100});
    await new Promise(resolve => setTimeout(resolve, 150));
    await run(b, 'acceptCall', {callId: 'late'});
    assert.equal((await run(a, 'getPendingEvents')).value.at(-1).type, 'missed');
    await run(a, 'showIncomingCall', {...call, callId: '__proto__'});
    assert.equal((await run(a, 'getActiveCalls')).value.at(-1).config.callId, '__proto__');
    await a.evaluate(async () => {
      await navigator.serviceWorker.register('/sw.js');
      await navigator.serviceWorker.ready;
    });
    const workerRun = (method, args = {}) => a.evaluate(async ([method, args]) => {
      const registration = await navigator.serviceWorker.ready;
      return new Promise((resolve, reject) => {
        const channel = new MessageChannel();
        const timeout = setTimeout(() => reject(new Error('Worker RPC timed out')), 5000);
        channel.port1.onmessage = event => {
          clearTimeout(timeout); channel.port1.close();
          if (event.data.ok) resolve(event.data); else reject(new Error(event.data.error));
        };
        registration.active.postMessage({type: 'unified-call', method, args}, [channel.port2]);
      });
    }, [method, args]);
    await workerRun('showIncomingCall', {...call, callId: 'worker-call'});
    assert.ok((await run(b, 'getActiveCalls')).value.some(c => c.config.callId === 'worker-call'));
    await workerRun('declineCall', {callId: 'worker-call'});
    assert.equal((await run(b, 'getPendingEvents')).value.at(-1).type, 'declined');
    await assert.rejects(workerRun('unknownMethod'), /Unsupported/);
    await a.evaluate(() => UnifiedCallPage.invoke('initialize', {webUseServiceWorker: true, webEnableRingtone: false}));
    const viaPage = await a.evaluate(() => UnifiedCallPage.invoke('getActiveCalls', {}));
    assert.ok(Array.isArray(viaPage.value));
    await assert.rejects(a.evaluate(() => UnifiedCallPage.invoke('initialize', {webLaunchUrl: 'https://example.org/'})), /origin/);
    console.log('PASS: browser IndexedDB concurrency, lifecycle, replay/ack, expiry, arbitrary IDs, Worker RPC/shared state');
  } finally { if (browser) await browser.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
