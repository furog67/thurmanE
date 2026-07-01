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
    private const val PREFS = "easycaller_debug"
    private const val KEY_DISABLED = "kiosk_disabled"

    /** Debug toggle: when true, pin() is a no-op so calls can be tested unpinned. */
    fun isDisabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISABLED, false)

    fun setDisabled(context: Context, disabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DISABLED, disabled).apply()
    }

    fun pin(activity: Activity) {
        if (isDisabled(activity)) {
            runCatching { activity.stopLockTask() }
            return
        }
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        // Only start pinning when we are genuinely NOT already in a lock-task mode.
        // Screen pinning reports LOCK_TASK_MODE_PINNED (not LOCKED), so comparing
        // against LOCKED alone re-pinned on every resume and re-showed the system
        // "Got it" dialog after the screen slept and woke. Skipping when already
        // PINNED (or LOCKED) keeps the elderly user from ever seeing it again.
        if (am?.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
            runCatching { activity.startLockTask() }
        }
    }

    fun unpin(activity: Activity) {
        runCatching { activity.stopLockTask() }
    }
}
