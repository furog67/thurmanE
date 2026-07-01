package com.elderlycaller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Runs as a foreground service for the duration of an outgoing call. Motorola (and
 * other aggressive OEMs) hard-kill our process moments after Telecom binds the
 * InCallService — the emulator does not. An ongoing foreground-service notification
 * raises our process to a priority the OS will not reap mid-call, keeping the
 * Easy Caller in-call screen (and its HANG UP button) alive.
 */
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        DebugLog.log("CallForegroundService started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLog.log("CallForegroundService stopped")
    }

    private fun buildNotification(): Notification {
        createChannel(this)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Easy Caller")
            .setContentText("Call in progress")
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "easycaller_call"
        private const val NOTIF_ID = 42

        private fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
                    nm?.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            "Ongoing Call",
                            NotificationManager.IMPORTANCE_LOW
                        ).apply { setShowBadge(false) }
                    )
                }
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }
}
