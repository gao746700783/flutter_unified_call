package dev.unifiedcall.flutter_unified_call

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps accepted Telecom calls user-visible; never captures microphone/media. */
class CallForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = CallRuntime.get(this)
        try {
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "unified_call_ongoing") else Notification.Builder(this)
            builder.setSmallIcon(android.R.drawable.sym_call_incoming).setContentTitle("Call in progress")
                .setCategory(Notification.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                builder.setContentIntent(PendingIntent.getActivity(this, 0, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            if (Build.VERSION.SDK_INT >= 29) startForeground(197701, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            else startForeground(197701, builder.build())
            // End may race with delivery of startForegroundService. Promote first
            // to satisfy the deadline, then remove the obsolete notification.
            if (runtime.ongoingTelecomIds().isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
            }
        } catch (error: RuntimeException) {
            runtime.reportForegroundError(error)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() { stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
}
