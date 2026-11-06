/* Page bridge. The host explicitly opts into its own Service Worker adapter. */
(function () {
  'use strict';
  let useWorker = false;
  let audioContext;
  let oscillator;
  let gain;
  let media;
  let currentId;
  let generation = 0;
  let soundGeneration = 0;
  let expiryTimer;
  let pulseTimer;
  let failedId;
  let unlocked = false;
  let pageFocused = document.hasFocus();
  const notifications = new Map();
  const notificationFailures = new Set();
  async function syncNotifications() {
    const hidden = document.visibilityState !== 'visible';
    const enabled = !useWorker && hidden && typeof Notification === 'function' &&
      Notification.permission === 'granted';
    const calls = enabled ? (await UnifiedCallCore.invoke('getActiveCalls')).value : [];
    const options = enabled ? (await UnifiedCallCore.invoke('getOptions')).value : {};
    const ringing = new Map(calls.filter(call => call.state === 'ringing' &&
      call.config.expiresAt > Date.now()).map(call => [call.config.callId, call.config]));
    for (const [id, notification] of notifications) {
      if (!ringing.has(id)) { notification.close(); notifications.delete(id); }
    }
    for (const [id, config] of ringing) {
      if (notifications.has(id) || notificationFailures.has(id)) continue;
      try {
        const notification = new Notification(config.callerName, {
          body: config.callerHandle || UnifiedCallCore.text(options, config.mediaType === 'video' ? 'videoSubtitle' : 'audioSubtitle'),
          tag: 'unified-call-' + id,
          requireInteraction: true,
        });
        notification.onclick = () => { window.focus(); notification.close(); notifications.delete(id); };
        notifications.set(id, notification);
      } catch (error) {
        notificationFailures.add(id);
        await UnifiedCallCore.invoke('reportPlatformError', {
          callId: id, error: error.name || String(error),
        });
      }
    }
  }
  function refreshNotifications() {
    syncNotifications().catch(console.error);
  }
  function stopSound() {
    ++soundGeneration;
    clearTimeout(expiryTimer); clearInterval(pulseTimer);
    if (oscillator) { oscillator.stop(); oscillator.disconnect(); oscillator = null; }
    if (gain) { gain.disconnect(); gain = null; }
    if (media) { media.pause(); media.removeAttribute('src'); media.load(); media = null; }
    currentId = null;
  }
  async function audioFailure(id, error) {
    stopSound();
    if (failedId === id) return;
    failedId = id;
    await UnifiedCallCore.invoke('reportPlatformError', {callId: id, error: error.name || String(error)});
  }
  async function unlock() {
    // Called directly by trusted pointer/keyboard handlers or the public gesture API.
    const Constructor = window.AudioContext || window.webkitAudioContext;
    if (Constructor && !audioContext) audioContext = new Constructor();
    if (audioContext) await audioContext.resume();
    unlocked = true; failedId = null;
    await syncSound();
    return {value: null, events: []};
  }
  async function syncSound() {
    const ticket = ++generation;
    const [{value: calls}, {value: options}] = await Promise.all([
      UnifiedCallCore.invoke('getActiveCalls'), UnifiedCallCore.invoke('getOptions')]);
    if (ticket !== generation) return;
    const call = calls.filter(c => c.state === 'ringing' && c.config.expiresAt > Date.now())
      .sort((a, b) => a.config.expiresAt - b.config.expiresAt)[0];
    if (!call || document.visibilityState !== 'visible' || (!pageFocused || !document.hasFocus()) || options.webEnableRingtone === false) { stopSound(); return; }
    const id = call.config.callId;
    if (id === currentId || id === failedId) return;
    stopSound(); currentId = id;
    const soundTicket = soundGeneration;
    expiryTimer = setTimeout(() => syncSound().catch(console.error), Math.max(0, call.config.expiresAt - Date.now()));
    try {
      if (options.webRingtoneUrl) {
        media = new Audio(new URL(options.webRingtoneUrl, document.baseURI).href);
        media.loop = true;
        const activeMedia = media;
        let timeout;
        try {
          await Promise.race([activeMedia.play(), new Promise((_, reject) => {
            timeout = setTimeout(() => reject(new DOMException('Ringtone playback timed out', 'TimeoutError')), 5000);
          })]);
        } finally { clearTimeout(timeout); }
        if (soundTicket !== soundGeneration || currentId !== id) activeMedia.pause();
      } else {
        if (!unlocked || !audioContext || audioContext.state !== 'running') throw new DOMException('Audio requires a user gesture', 'NotAllowedError');
        oscillator = audioContext.createOscillator(); gain = audioContext.createGain();
        oscillator.frequency.value = 440; gain.gain.value = 0;
        oscillator.connect(gain); gain.connect(audioContext.destination); oscillator.start();
        const pulse = () => {
          if (!gain) return;
          const now = audioContext.currentTime;
          gain.gain.cancelScheduledValues(now); gain.gain.setValueAtTime(0, now);
          gain.gain.linearRampToValueAtTime(.12, now + .02);
          gain.gain.setValueAtTime(.12, now + .8); gain.gain.linearRampToValueAtTime(0, now + .85);
        };
        pulse(); pulseTimer = setInterval(pulse, 2000);
      }
    } catch (error) { if (currentId === id && soundTicket === soundGeneration) await audioFailure(id, error); }
  }
  const audioEvents = new BroadcastChannel('flutter_unified_call_v2');
  audioEvents.onmessage = () => {
    syncSound().catch(console.error);
    refreshNotifications();
  };
  for (const name of ['pointerdown', 'keydown']) document.addEventListener(name, () => { unlock().catch(console.error); }, {capture: true});
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible') { ++generation; stopSound(); }
    else syncSound().catch(console.error);
    refreshNotifications();
  });
  window.addEventListener('blur', () => { pageFocused = false; ++generation; stopSound(); });
  window.addEventListener('focus', () => { pageFocused = true; syncSound().catch(console.error); });
  window.addEventListener('pagehide', () => { ++generation; stopSound(); });
  async function workerInvoke(method, args) {
    const registration = await navigator.serviceWorker.getRegistration(document.baseURI);
    if (!registration?.active) throw new Error('No active host Service Worker');
    return new Promise((resolve, reject) => {
      const channel = new MessageChannel();
      const timer = setTimeout(() => { channel.port1.close(); reject(new Error('Unified call Worker adapter did not respond')); }, 5000);
      channel.port1.onmessage = event => {
        clearTimeout(timer); channel.port1.close();
        if (event.data.ok) resolve(event.data); else reject(new Error(event.data.error));
      };
      registration.active.postMessage({type: 'unified-call', method, args}, [channel.port2]);
    });
  }
  window.UnifiedCallPage = {
    async invoke(method, args) {
      if (method === 'unlockWebAudio') return unlock();
      const nextWorker = method === 'initialize' ? args.webUseServiceWorker === true : useWorker;
      if (nextWorker && !('serviceWorker' in navigator)) throw new Error('Service Workers are unsupported');
      if (method === 'initialize' && args.webLaunchUrl) {
        if (new URL(args.webLaunchUrl, document.baseURI).origin !== location.origin) throw new Error('Launch URL must use the application origin');
      }
      if (method === 'initialize' && args.webRingtoneUrl) {
        const url = new URL(args.webRingtoneUrl, document.baseURI);
        if (!['https:', 'http:'].includes(url.protocol)) throw new Error('Ringtone URL must use HTTP or HTTPS');
      }
      const result = await (nextWorker ? workerInvoke(method, args) : UnifiedCallCore.invoke(method, args));
      if (method === 'initialize') useWorker = nextWorker;
      if (method === 'initialize') { stopSound(); failedId = null; }
      await syncSound();
      await syncNotifications();
      return result;
    }
  };
})();
