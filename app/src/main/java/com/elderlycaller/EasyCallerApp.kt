package com.elderlycaller

import android.app.Application

/**
 * Installs a global uncaught-exception handler as early as possible so that a
 * crash during the call flow (which otherwise hard-kills the process with no
 * lifecycle callbacks) gets written to the persistent DebugLog and is readable
 * afterward on the Admin screen. Purely a debugging aid.
 */
class EasyCallerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        DebugLog.log("App.onCreate pid=${android.os.Process.myPid()}")

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val trace = throwable.stackTrace.take(6).joinToString(" | ") {
                "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
            }
            DebugLog.log("CRASH on ${thread.name}: ${throwable.javaClass.simpleName}: ${throwable.message} @ $trace")
            previous?.uncaughtException(thread, throwable)
        }
    }
}
