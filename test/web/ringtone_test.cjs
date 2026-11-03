const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const http = require('node:http');
const fs = require('node:fs');
const assert = require('node:assert/strict');
(async () => {
  const server = http.createServer((req, res) => {
    if (req.url === '/tone.wav') { res.setHeader('Content-Type', 'audio/wav'); res.end(fs.readFileSync('example/android/app/src/main/res/raw/demo_ringtone.wav')); }
    else if (req.url.endsWith('.js')) {
      const name = req.url === '/core.js' ? 'unified_call_core.js' : 'unified_call_page.js';
      res.setHeader('Content-Type', 'application/javascript'); res.end(fs.readFileSync('web/' + name));
    } else res.end('<button>Enable audio</button><script src="/core.js"></script><script src="/page.js"></script>');
  }).listen(0, '127.0.0.1');
  await new Promise(resolve => server.once('listening', resolve));
  let browser;
  try {
    browser = await chromium.launch({channel: 'chrome', headless: true});
    const context = await browser.newContext();
    await context.addInitScript(() => {
      window.audioCounts = {started: 0, stopped: 0};
      const play = HTMLMediaElement.prototype.play;
      HTMLMediaElement.prototype.play = function () { window.lastAudio = this; return play.call(this); };
      const original = AudioContext.prototype.createOscillator;
      AudioContext.prototype.createOscillator = function () {
        const node = original.call(this); const start = node.start.bind(node); const stop = node.stop.bind(node);
        node.start = (...args) => { audioCounts.started++; return start(...args); };
        node.stop = (...args) => { audioCounts.stopped++; return stop(...args); };
        return node;
      };
    });
    const page = await context.newPage(); page.setDefaultTimeout(5000);
    await page.goto(`http://127.0.0.1:${server.address().port}`); await page.bringToFront();
    const run = (method, args = {}) => page.evaluate(([method, args]) => UnifiedCallPage.invoke(method, args), [method, args]);
    await run('initialize', {});
    const call = id => ({callId: id, callerName: 'Audio Test', mediaType: 'audio', expiresAt: Date.now() + 30000, extra: {}});
    await run('showIncomingCall', call('blocked'));
    const events = (await run('getPendingEvents')).value;
    assert.ok(events.some(e => e.type === 'platformError' && e.metadata.error === 'NotAllowedError'));
    await page.getByRole('button', {name: 'Enable audio'}).click();
    await page.waitForFunction(() => audioCounts.started >= 1);
    await run('acceptCall', {callId: 'blocked'});
    await page.waitForFunction(() => audioCounts.started === audioCounts.stopped);
    await run('showIncomingCall', {...call('timeout'), expiresAt: Date.now() + 200});
    await page.waitForFunction(() => audioCounts.started >= 2);
    await page.waitForFunction(() => audioCounts.started === audioCounts.stopped);
    await run('showIncomingCall', call('focus'));
    await page.waitForFunction(() => audioCounts.started >= 3);
    await page.evaluate(() => window.dispatchEvent(new Event('blur')));
    await page.waitForFunction(() => audioCounts.started === audioCounts.stopped);
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await page.waitForFunction(() => audioCounts.started >= 4);
    await run('declineCall', {callId: 'focus'});
    await page.waitForFunction(() => audioCounts.started === audioCounts.stopped);
    await run('initialize', {webRingtoneUrl: '/tone.wav'});
    await run('showIncomingCall', call('custom'));
    await page.waitForFunction(() => window.lastAudio && !lastAudio.paused && lastAudio.loop);
    await run('endCall', {callId: 'custom'});
    await page.waitForFunction(() => lastAudio.paused);
    await run('initialize', {webRingtoneUrl: '/missing.wav'});
    await run('showIncomingCall', call('bad-media'));
    await page.waitForFunction(async () => (await UnifiedCallCore.invoke('getPendingEvents')).value.some(e => e.call.callId === 'bad-media' && e.type === 'platformError'));
    await assert.rejects(run('initialize', {webRingtoneUrl: 'javascript:alert(1)'}), /HTTP/);
    console.log('PASS: real Web Audio gesture unlock, blocked event, accept/timeout/explicit focus handlers, custom WAV playback/stop and load failure');
  } finally { if (browser) await browser.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
