package com.carplay.android

import android.app.Application
import timber.log.Timber

/**
 * CarPlay Android Application
 *
 * Initializes global components on app startup.
 */
class CarPlayApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.d("CarPlay Android started")
    }
}
