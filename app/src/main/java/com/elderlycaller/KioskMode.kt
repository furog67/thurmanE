package com.elderlycaller

import android.app.Activity
import android.app.ActivityManager
import android.content.Context

/**
 * Wraps Android's screen-pinning API (startLockTask/stopLockTask) so the
 * elderly user can never reach Home/Recents/Settings — only the Admin
 * screen can call unpin().
 */
object KioskMode {
    fun pin(activity: Activity) {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_LOCKED) {
            runCatching { activity.startLockTask() }
        }
    }

    fun unpin(activity: Activity) {
        runCatching { activity.stopLockTask() }
    }
}
