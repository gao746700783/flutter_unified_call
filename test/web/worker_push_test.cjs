const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const http = require('node:http');
const fs = require('node:fs');
const assert = require('node:assert/strict');
(async () => {
  const server = http.createServer((req, res) => {
    if (req.url === '/calls/sw.js') {
      res.setHeader('Content-Type', 'application/javascript');
      res.end("importScripts('unified_call_worker.js'); self.addEventListener('install', e => e.waitUntil(self.skipWaiting())); self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));");
    } else if (req.url.startsWith('/calls/') && req.url.endsWith('.js')) {
      const file = req.url.endsWith('unified_call_core.js') ? 'web/unified_call_core.js' : 'web/unified_call_worker.js';
      res.setHeader('Content-Type', 'application/javascript'); res.end(fs.readFileSync(file));
    } else res.end('<title>Worker observer outside application scope</title>');
  }).listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  let browser;
  try {
    browser = await chromium.launch({channel: 'chrome', headless: true});
    const context = await browser.newContext({permissions: ['notifications'], locale: 'zh-CN'});
    const page = await context.newPage(); page.setDefaultTimeout(10000);
    const origin = `http://127.0.0.1:${server.address().port}`;
    await page.goto(origin + '/observer');
    const cdp = await context.newCDPSession(page);
    let registrationId;
    cdp.on('ServiceWorker.workerRegistrationUpdated', ({registrations}) => {
      for (const registration of registrations) if (registration.scopeURL === origin + '/calls/' && !registration.isDeleted) registrationId = registration.registrationId;
    });
    await cdp.send('ServiceWorker.enable');
    await page.evaluate(async () => {
      const registration = await navigator.serviceWorker.register('/calls/sw.js', {scope: '/calls/'});
      const worker = registration.installing || registration.waiting || registration.active;
      if (worker.state !== 'activated') await new Promise(resolve => worker.addEventListener('statechange', () => { if (worker.state === 'activated') resolve(); }));
    });
    assert.ok(registrationId, 'Browser did not report registration');
    const push = payload => cdp.send('ServiceWorker.deliverPushMessage', {origin, registrationId, data: JSON.stringify(payload)});
    const config = {callId: 'background', callerName: 'Push Test', mediaType: 'audio', expiresAt: Date.now() + 30000, extra: {room: 'test'}};
    await push({flutter_unified_call: config});
    await page.waitForFunction(async () => (await (await navigator.serviceWorker.getRegistration('/calls/')).getNotifications()).some(n => n.data.unifiedCallId === 'background'));
    const notes = await page.evaluate(async () => (await (await navigator.serviceWorker.getRegistration('/calls/')).getNotifications()).map(n => ({title: n.title, id: n.data.unifiedCallId, body: n.body, labels: n.actions.map(a => a.title), actions: n.actions.map(a => a.action)})));
    assert.equal(notes[0].title, 'Push Test');
    assert.deepEqual(notes[0].actions, ['accepted', 'declined']);
    assert.equal(notes[0].body, '语音来电');
    assert.deepEqual(notes[0].labels, ['接听', '拒绝']);
    await cdp.send('ServiceWorker.stopAllWorkers');
    await push({flutter_unified_call: {type: 'cancel', callId: 'background'}});
    await page.waitForFunction(async () => (await (await navigator.serviceWorker.getRegistration('/calls/')).getNotifications()).length === 0);
    const events = await page.evaluate(async () => {
      const registration = await navigator.serviceWorker.getRegistration('/calls/');
      return new Promise((resolve, reject) => {
        const channel = new MessageChannel();
        const timeout = setTimeout(() => reject(new Error('Inbox RPC timeout')), 5000);
        channel.port1.onmessage = e => { clearTimeout(timeout); channel.port1.close(); e.data.ok ? resolve(e.data.value) : reject(new Error(e.data.error)); };
        registration.active.postMessage({type: 'unified-call', method: 'getPendingEvents', args: {}}, [channel.port2]);
      });
    });
    assert.deepEqual(events.map(e => e.type), ['incoming', 'ended']);
    assert.equal(events[1].reason, 'cancelled');
    assert.equal(events[1].call.extra.room, 'test');
    await page.evaluate(async () => {
      const registration = await navigator.serviceWorker.getRegistration('/calls/');
      await new Promise((resolve, reject) => {
        const channel = new MessageChannel();
        const timeout = setTimeout(() => reject(new Error('Initialize RPC timeout')), 5000);
        channel.port1.onmessage = event => {
          clearTimeout(timeout); channel.port1.close();
          event.data.ok ? resolve() : reject(new Error(event.data.error));
        };
        registration.active.postMessage({type: 'unified-call', method: 'initialize', args: {
          audioSubtitle: '语音邀请', meetingInvitationText: '会议邀请', acceptText: '加入', declineText: '忽略',
        }}, [channel.port2]);
      });
    });
    await cdp.send('ServiceWorker.stopAllWorkers');
    await push({flutter_unified_call: {...config, kind: 'meeting', callId: 'custom-text', expiresAt: Date.now() + 30000}});
    const custom = await page.evaluate(async () => {
      const registration = await navigator.serviceWorker.getRegistration('/calls/');
      const deadline = Date.now() + 10000;
      while (Date.now() < deadline) {
        const note = (await registration.getNotifications()).find(n => n.data.unifiedCallId === 'custom-text');
        if (note) return {body: note.body, labels: note.actions.map(a => a.title)};
        await new Promise(resolve => setTimeout(resolve, 50));
      }
      throw new Error('Custom notification did not appear');
    });
    assert.deepEqual(custom, {body: '会议邀请', labels: ['加入', '忽略']});
    console.log('PASS: browser-delivered Push, background system notification/actions, Worker restart/cancel and durable inbox');
  } finally { if (browser) await browser.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
