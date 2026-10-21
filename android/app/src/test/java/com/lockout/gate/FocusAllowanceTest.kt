package com.lockout.gate

import android.content.Context
import com.google.gson.Gson
import com.lockout.gate.network.FocusState
import com.lockout.gate.state.SessionStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class FocusAllowanceTest {
    @Test fun serverAllowancePersistsWithoutChangingNormalPausesOrAllowingStaleUpdates() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("lockout_session", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = SessionStore(context)
        val now = System.currentTimeMillis() / 1000
        val active = FocusState(true, now, now + 60, 60, now, 0, now + 3600, "Asia/Karachi")
        store.updateFocus(active, allowStart = true)
        assertTrue(SessionStore(context).focusEarlyExitMessage(context).contains("0 of 2"))
        assertEquals(2, store.pausesRemainingThisMonth)
        store.updateFocus(active.copy(server_now = now - 1, early_exits_remaining = 2))
        assertTrue(store.focusEarlyExitMessage(context).contains("0 of 2"))
        // A response with the right key ends the lock, not its monthly allowance.
        store.updateFocus(active.copy(active = false, remaining_s = 0, server_now = now + 1), allowEarlyEnd = true)
        assertFalse(store.focusActive())
        assertTrue(store.focusEarlyExitMessage(context).contains("0 of 2"))
    }

    @Test fun olderServerJsonDoesNotInventAnAllowance() {
        val state = Gson().fromJson("""{"active":false,"started_at":0,"ends_at":0,"remaining_s":0,"server_now":100}""", FocusState::class.java)
        assertNull(state.early_exits_remaining)
    }
}
