package com.elderlycaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Relaunches Easy Caller right after the device finishes booting, so the
 * elderly user never lands on the regular home screen/lock screen. Being
 * the default dialer app exempts this from Android's background-activity-
 * start restrictions.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
    }
}
