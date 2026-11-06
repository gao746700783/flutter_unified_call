/* Import this from the host-owned Service Worker. Do not register a second worker
 * over an existing push SDK worker scope. */
importScripts('unified_call_core.js');

async function syncNotifications() {
  const {value: active} = await UnifiedCallCore.invoke('getActiveCalls');
  const ringing = new Set(active.filter(c => c.state === 'ringing').map(c => c.config.callId));
  for (const notification of await self.registration.getNotifications()) {
    if (notification.data?.unifiedCallId && !ringing.has(notification.data.unifiedCallId)) notification.close();
  }
}
async function incoming(config) {
  const result = await UnifiedCallCore.invoke('showIncomingCall', config);
  if (!result.events.some(e => e.type === 'incoming' && e.call.callId === config.callId)) return result;
  const windows = await self.clients.matchAll({type: 'window', includeUncontrolled: true});
  if (!windows.some(client => client.visibilityState === 'visible' && client.url.startsWith(self.registration.scope))) {
    const {value: options} = await UnifiedCallCore.invoke('getOptions');
    await self.registration.showNotification(config.callerName, {
      body: config.callerHandle || UnifiedCallCore.text(options, UnifiedCallCore.isMeeting(config) ? 'meetingInvitationText' : config.mediaType === 'video' ? 'videoSubtitle' : 'audioSubtitle'),
      tag: `unified-call-${config.callId}`,
      data: {unifiedCallId: config.callId},
      actions: [{action: 'accepted', title: UnifiedCallCore.text(options, UnifiedCallCore.isMeeting(config) ? 'joinMeetingText' : 'acceptText')},
        {action: 'declined', title: UnifiedCallCore.text(options, 'declineText')}],
      requireInteraction: true,
    });
  }
  return result;
}
self.addEventListener('message', event => {
  if (event.data?.type !== 'unified-call') return;
  const port = event.ports[0];
  if (!port) return;
  event.waitUntil((async () => {
    try {
      const result = event.data.method === 'showIncomingCall' ? await incoming(event.data.args) :
        await UnifiedCallCore.invoke(event.data.method, event.data.args);
      await syncNotifications();
      port.postMessage({ok: true, ...result});
    } catch (error) { port.postMessage({ok: false, error: String(error)}); }
  })());
});
self.addEventListener('push', event => {
  let payload;
  try { payload = event.data?.json()?.flutter_unified_call; }
  catch (error) { console.error('unified-call: invalid push JSON', error); return; }
  if (!payload) return; // Other push SDK payloads belong to the host worker.
  event.waitUntil((async () => {
    if (payload.type === 'cancel') await UnifiedCallCore.invoke('endCall', {callId: payload.callId, reason: 'cancelled'});
    else await incoming(payload.call || payload);
    await syncNotifications();
  })());
});
self.addEventListener('notificationclick', event => {
  const id = event.notification.data?.unifiedCallId;
  if (!id) return;
  event.notification.close();
  event.waitUntil((async () => {
    if (event.action === 'accepted') await UnifiedCallCore.invoke('acceptCall', {callId: id});
    else if (event.action === 'declined') await UnifiedCallCore.invoke('declineCall', {callId: id});
    await syncNotifications();
    if (event.action === 'declined') return;
    const {value: options} = await UnifiedCallCore.invoke('getOptions');
    const target = new URL(options.webLaunchUrl || self.registration.scope, self.registration.scope);
    if (target.origin !== self.location.origin) throw new Error('Launch URL must use the application origin');
    const windows = await self.clients.matchAll({type: 'window', includeUncontrolled: true});
    const existing = windows.find(client => client.url.startsWith(self.registration.scope));
    if (existing) {
      await existing.focus();
      existing.postMessage({type: 'unified-call-action', callId: id, action: event.action});
    } else await self.clients.openWindow(target.href);
  })());
});
