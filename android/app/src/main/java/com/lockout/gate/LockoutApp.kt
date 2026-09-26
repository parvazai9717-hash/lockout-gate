package com.lockout.gate

import android.app.Application
import com.lockout.gate.network.ApiClient

class LockoutApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
    }
}
