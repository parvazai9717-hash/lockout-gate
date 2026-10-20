package com.lockout.gate

import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class SessionStoreTimingTest : SessionStoreTimingCases() {
    override val context: Context get() = RuntimeEnvironment.getApplication()
}
