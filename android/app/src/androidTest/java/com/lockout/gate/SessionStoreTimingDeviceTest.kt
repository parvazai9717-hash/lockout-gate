package com.lockout.gate

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStoreTimingDeviceTest : SessionStoreTimingCases() {
    override val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
}
