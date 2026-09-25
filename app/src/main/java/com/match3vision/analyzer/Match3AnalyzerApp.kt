package com.match3vision.analyzer

import android.app.Application
import timber.log.Timber

/**
 * Application entry — initializes Timber for Phase 1 diagnostics.
 */
class Match3AnalyzerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Timber.i("Match3 Vision Analyzer Phase 1 starting (analyzer only)")
    }
}
