package com.example

import android.app.Application
import com.example.vpn.AppContainer

class PingForgeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
