/* Shared by the host page and Service Worker. No signaling or media dependencies. */
(function (scope) {
  'use strict';
  const DB = 'flutter_unified_call_v2';
  const channel = typeof BroadcastChannel === 'function' ? new BroadcastChannel(DB) : null;
  function open() {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(DB, 1);
      request.onupgradeneeded = () => request.result.createObjectStore('state');
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
    });
  }
  function validate(config) {
    if (!config || typeof config.callId !== 'string' || !config.callId.trim() ||
        typeof config.callerName !== 'string' || !config.callerName.trim() ||
        !['audio', 'video'].includes(config.mediaType) ||
        !Number.isSafeInteger(config.expiresAt) || !config.extra ||
        typeof config.extra !== 'object' || Array.isArray(config.extra)) {
      throw new Error('Invalid call payload');
    }
    function check(value) {
      if (value === null || typeof value === 'string' || typeof value === 'boolean') return;
      if (typeof value === 'number' && Number.isFinite(value)) return;
      if (Array.isArray(value)) { value.forEach(check); return; }
      if (value && Object.getPrototypeOf(value) === Object.prototype) { Object.values(value).forEach(check); return; }
      throw new Error('Payload must contain JSON values only');
    }
    check(config);
  }
  async function invoke(method, args = {}) {
    const db = await open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('state', 'readwrite');
      const table = tx.objectStore('state');
      const request = table.get('root');
      let value = null;
      let failure;
      const emitted = [];
      request.onsuccess = () => {
        try {
          const root = request.result || { calls: {}, events: [], options: {} };
          const now = Date.now();
          function emit(call, type, reason = null, metadata = {}) {
            const event = {eventId: crypto.randomUUID(), type, call: structuredClone(call.config),
              occurredAt: now, reason, metadata};
            root.events.push(event); emitted.push(event);
          }
          function transition(id, action, reason = null) {
            const call = Object.hasOwn(root.calls, id) ? root.calls[id] : null;
            if (!call || call.state === 'ended') return;
            if (['accepted', 'declined', 'missed'].includes(action) && call.state !== 'ringing') return;
            if (action === 'connected') {
              if (call.state === 'connected') return;
              if (call.state !== 'accepted') throw new Error('Accept the call before connecting');
            }
            if (action === 'accepted' && call.config.expiresAt <= now) { action = 'missed'; reason = 'missed'; }
            call.state = ['accepted', 'connected'].includes(action) ? action : 'ended';
            emit(call, action, reason);
          }
          // Every entry point reconciles expiry; Worker timers are not durable.
          for (const [id, call] of Object.entries(root.calls)) {
            if (call.state === 'ringing' && call.config.expiresAt <= now) transition(id, 'missed', 'missed');
          }
          switch (method) {
            case 'initialize': root.options = structuredClone(args); break;
            case 'reportPlatformError': {
              const call = Object.hasOwn(root.calls, args.callId) ? root.calls[args.callId] : null;
              if (call && call.state !== 'ended') emit(call, 'platformError', null,
                {operation: 'ringtone', error: String(args.error)});
              break;
            }
            case 'getOptions': value = root.options; break;
            case 'showIncomingCall': {
              validate(args);
              if (args.expiresAt <= now) break;
              if (Object.hasOwn(root.calls, args.callId)) {
                const existing = root.calls[args.callId];
                if (existing.state !== 'ended') {
                  existing.config.extra = {...existing.config.extra, ...structuredClone(args.extra)};
                }
                break;
              }
              // Define property explicitly so arbitrary call IDs cannot mutate prototypes.
              const call = {config: structuredClone(args), state: 'ringing'};
              Object.defineProperty(root.calls, args.callId, {value: call, writable: true, enumerable: true, configurable: true});
              emit(call, 'incoming'); break;
            }
            case 'acceptCall': transition(args.callId, 'accepted'); break;
            case 'declineCall': transition(args.callId, 'declined', 'declined'); break;
            case 'setCallConnected': transition(args.callId, 'connected'); break;
            case 'endCall': {
              const reason = args.reason || 'localEnded';
              if (!['localEnded', 'remoteEnded', 'cancelled', 'declined', 'missed', 'failed'].includes(reason)) throw new Error('Invalid end reason');
              transition(args.callId, reason === 'missed' ? 'missed' : 'ended', reason); break;
            }
            case 'endAllCalls': Object.keys(root.calls).forEach(id => transition(id, 'ended', 'localEnded')); break;
            case 'getActiveCalls': value = Object.values(root.calls).filter(call => call.state !== 'ended'); break;
            case 'getPendingEvents': value = root.events; break;
            case 'acknowledgeEvent': root.events = root.events.filter(event => event.eventId !== args.eventId); break;
            default: throw new Error(`Unsupported operation: ${method}`);
          }
          const endedKeys = Object.keys(root.calls).filter(id => root.calls[id].state === 'ended');
          if (endedKeys.length > 50) {
            endedKeys.slice(0, endedKeys.length - 50).forEach(id => delete root.calls[id]);
          }
          table.put(root, 'root');
        } catch (error) { failure = error; tx.abort(); }
      };
      tx.oncomplete = () => {
        db.close();
        if (emitted.length) channel?.postMessage({type: 'unified-call-events', events: emitted});
        resolve({value, events: emitted});
      };
      tx.onabort = () => { db.close(); reject(failure || tx.error || new Error('Storage transaction aborted')); };
      tx.onerror = () => { /* onabort reports the failed transaction. */ };
    });
  }
  scope.UnifiedCallCore = {invoke};
})(globalThis);
