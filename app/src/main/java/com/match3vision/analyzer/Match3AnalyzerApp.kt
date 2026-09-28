package com.match3vision.analyzer

import android.app.Application
import java.io.File
import timber.log.Timber

/**
 * Application entry — Timber + fatal crash log for device diagnostics.
 * Does NOT start capture, AccessibilityService, or input. Those stay opt-in.
 */
class Match3AnalyzerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        installCrashLogger()
        Timber.i("Match3 Vision Analyzer starting (UI only; input DEFAULT DISABLED)")
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val msg = buildString {
                    append("FATAL on ").append(thread.name).append('\n')
                    append(throwable.stackTraceToString())
                }
                Timber.e(throwable, "FATAL uncaught on %s", thread.name)
                File(filesDir, "crash.log").appendText(
                    "${System.currentTimeMillis()} $msg\n-----\n",
                )
            } catch (_: Throwable) {
                // never let logging mask the original crash
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
