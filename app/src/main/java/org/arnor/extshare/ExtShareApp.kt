package org.arnor.extshare

import android.app.Application

class ExtShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DebugLog.log("Crash", "Uncaught on ${thread.name}: ${DebugLog.describe(throwable)}")
            previous?.uncaughtException(thread, throwable)
        }
    }
}
