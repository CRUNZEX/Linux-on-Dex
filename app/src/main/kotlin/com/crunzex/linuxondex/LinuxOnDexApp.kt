package com.crunzex.linuxondex

import android.app.Application
import com.crunzex.linuxondex.display.settings.DisplayWindowPerformance

/**
 * Application entry point.
 *
 * Holds the single [AppContainer] so every screen and service shares the same
 * engine/runtime objects without a DI framework.
 */
class LinuxOnDexApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        DisplayWindowPerformance.install(this)
    }
}
