package com.lockout.gate

import android.content.ComponentName
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.lockout.gate.network.FocusState
import com.lockout.gate.state.SessionStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import android.os.Looper
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class FocusTabReturnTest {
    private fun returningFromLock(end: (SessionStore) -> Unit, canStart: Boolean) {
        val screen = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = screen.get()
        activity.setTheme(R.style.Theme_LockoutGate)
        screen.create().start()
        Settings.Secure.putString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ComponentName(activity, AppBlockAccessibilityService::class.java).flattenToString())
        val store = SessionStore(activity)
        if (!BuildConfig.PERSONAL_SETTINGS_GUARD) {
            assertTrue(store.savePublicFocusAccount("disposable-focus-tab-test", "x".repeat(64)))
        }
        store.updateFocus(FocusState(true, 100, 160, 60, 100), allowStart = true)
        val root = activity.layoutInflater.inflate(R.layout.content_focus_tab, null)
        activity.setContentView(root)
        // No refresh/network calls: this reproduces return to an existing tab.
        val tab = FocusTabController(activity, root, store)
        // Match the initial drawing done by showTab, without polling a server.
        FocusTabController::class.java.getDeclaredMethod("render").apply {
            isAccessible = true
        }.invoke(tab)
        screen.resume().visible()
        val start = root.findViewById<Button>(R.id.focusStartButton)
        assertFalse(start.isEnabled)
        root.findViewById<EditText>(R.id.focusCustomAmount).setText("1")
        screen.pause().stop()
        end(store)
        screen.start().resume()
        assertEquals(canStart, start.isEnabled)
        assertEquals("1", root.findViewById<EditText>(R.id.focusCustomAmount).text.toString())
        if (canStart) {
            start.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(org.robolectric.shadows.ShadowDialog.getLatestDialog().isShowing)
        }
        screen.pause().stop().destroy()
    }

    @Test fun validEarlyExitAllowsAnotherLockWithoutSwitchingTabs() = returningFromLock({ store ->
        store.updateFocus(FocusState(false, 0, 0, 0, 101), allowEarlyEnd = true)
    }, true)

    @Test fun naturalExpiryAllowsAnotherLockWithoutSwitchingTabs() = returningFromLock({
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
    }, true)

    @Test fun returningWithAnActiveLockDoesNotEnableStartingAnother() = returningFromLock({}, false)
}
