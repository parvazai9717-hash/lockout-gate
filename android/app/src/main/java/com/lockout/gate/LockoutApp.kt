package com.lockout.gate
import android.app.Application
class LockoutApp : Application() {
    override fun onCreate() { super.onCreate(); instance=this }
    companion object { lateinit var instance: LockoutApp; private set }
}
