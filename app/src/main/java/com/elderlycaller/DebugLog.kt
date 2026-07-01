package com.elderlycaller

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Tiny persistent event log backed by SharedPreferences. Survives the app being
 * closed, the activity being recreated, and the process being killed — so we can
 * capture what happens during the "flash and close" and read it afterward on the
 * Admin screen. Purely a debugging aid.
 */
object DebugLog {
    private const val PREFS = "easycaller_debug"
    private const val KEY = "log"
    private const val MAX_CHARS = 6000

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    @Synchronized
    fun log(event: String) {
        val p = prefs ?: return
        // Relative seconds since boot — enough to see ordering and gaps without Date APIs.
        val t = SystemClock.elapsedRealtime() / 1000
        val line = "[$t] $event"
        val updated = ((p.getString(KEY, "") ?: "") + "\n" + line).takeLast(MAX_CHARS)
        // commit() (synchronous) not apply() — the process may be killed milliseconds
        // after a log call, and apply()'s async disk write would lose the last events.
        p.edit().putString(KEY, updated).commit()
        // Mirror to Logcat (tag EASYCALLER) so it's visible in Android Studio too.
        android.util.Log.i("EASYCALLER", event)
    }

    fun read(): String = prefs?.getString(KEY, "") ?: ""

    fun clear() {
        prefs?.edit()?.remove(KEY)?.apply()
    }
}
