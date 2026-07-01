package com.elderlycaller

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Tiny persistent event log backed by SharedPreferences. Survives the app being
 * closed, the activity being recreated, and the process being killed — so we can
 * read what happened during a call afterward on the Admin screen.
 *
 * Hard-bounded by both line count and characters, and every operation is wrapped
 * so a write failure (e.g. storage full) can NEVER crash the app.
 */
object DebugLog {
    private const val PREFS = "easycaller_debug"
    private const val KEY = "log"
    private const val MAX_LINES = 150
    private const val MAX_CHARS = 8000

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            runCatching {
                prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            }
        }
    }

    @Synchronized
    fun log(event: String) {
        runCatching {
            android.util.Log.i("EASYCALLER", event)
            val p = prefs ?: return
            // Relative seconds since boot — enough to see ordering and gaps without Date APIs.
            val t = SystemClock.elapsedRealtime() / 1000
            val existing = p.getString(KEY, "") ?: ""
            // Keep only the last MAX_LINES lines, then hard-cap total characters.
            val lines = (existing + "\n[$t] $event")
                .lineSequence()
                .filter { it.isNotBlank() }
                .toList()
            val trimmed = lines.takeLast(MAX_LINES).joinToString("\n").takeLast(MAX_CHARS)
            // apply() (async) is enough now that logging is low-frequency; it also
            // won't block the caller if the disk is slow or failing.
            p.edit().putString(KEY, trimmed).apply()
        }
    }

    fun read(): String = runCatching { prefs?.getString(KEY, "") ?: "" }.getOrDefault("")

    fun clear() {
        runCatching { prefs?.edit()?.remove(KEY)?.apply() }
    }
}
