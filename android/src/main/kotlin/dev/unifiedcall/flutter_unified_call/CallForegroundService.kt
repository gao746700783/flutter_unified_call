package dev.unifiedcall.flutter_unified_call

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps live Telecom calls user-visible; never captures microphone/media. */
class CallForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = CallRuntime.get(this)
        try {
            val callId = runtime.ongoingTelecomIds().firstOrNull()
            val notification = runtime.foregroundNotification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(197701, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            else startForeground(197701, notification)
            runtime.foregroundStarted(callId)
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
    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        CallRuntime.get(this).foregroundStopped()
        super.onDestroy()
    }
}
