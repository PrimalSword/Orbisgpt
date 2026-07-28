package com.orbisgpt.terminal

import android.app.Application

class OrbisTerminalApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: OrbisTerminalApplication
            private set
    }
}
