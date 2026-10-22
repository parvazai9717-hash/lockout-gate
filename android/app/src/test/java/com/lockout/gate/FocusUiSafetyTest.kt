package com.lockout.gate

import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.lockout.gate.state.SessionStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class FocusUiSafetyTest {
    @Test fun deletionRequestIsDiscoverableDuringPublicLockWithoutEndingIt() {
        val screen = Robolectric.buildActivity(FocusLockActivity::class.java)
        val activity = screen.get()
        val store = SessionStore(activity)
        if (!BuildConfig.PERSONAL_SETTINGS_GUARD) {
            assertTrue(store.savePublicFocusAccount("disposable-deletion-test", "x".repeat(64)))
        }
        val now = System.currentTimeMillis() / 1000
        store.updateFocus(com.lockout.gate.network.FocusState(true, now, now + 600, 600, now), allowStart = true)
        screen.create()
        val button = activity.findViewById<Button>(R.id.focusRequestDeletionButton)
        assertEquals(if (BuildConfig.PERSONAL_SETTINGS_GUARD) android.view.View.GONE else android.view.View.VISIBLE,
            button.visibility)
        if (!BuildConfig.PERSONAL_SETTINGS_GUARD) {
            button.performClick()
            assertTrue(org.robolectric.shadows.ShadowDialog.getLatestDialog().isShowing)
        }
        assertTrue(store.focusActive())
        screen.destroy()
    }

    @Test fun pendingStartDisablesAccountSwitchAndDeletion() {
        val screen = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = screen.get()
        activity.setTheme(R.style.Theme_LockoutGate)
        screen.create().start()
        val store = SessionStore(activity)
        val root = activity.layoutInflater.inflate(R.layout.content_focus_tab, null)
        activity.setContentView(root)
        val tab = FocusTabController(activity, root, store)
        // Hold the controller in the state of a pending HTTP start, without
        // accessing the owner's server or using their monthly allowance.
        FocusTabController::class.java.getDeclaredField("starting").apply { isAccessible = true }.setBoolean(tab, true)
        FocusTabController::class.java.getDeclaredMethod("render").apply { isAccessible = true }.invoke(tab)
        for (id in listOf(R.id.focusStartButton, R.id.publicFocusEnrollButton,
            R.id.publicFocusRecoverButton, R.id.publicFocusDeleteButton)) {
            assertFalse(root.findViewById<Button>(id).isEnabled)
        }
        screen.stop().destroy()
    }

    @Test fun timerAndServerCompletionNavigateHomeOnlyOnce() {
        val screen = Robolectric.buildActivity(FocusLockActivity::class.java).create()
        val activity = screen.get()
        val finish = FocusLockActivity::class.java.getDeclaredMethod("finishLock").apply { isAccessible = true }
        finish.invoke(activity)
        finish.invoke(activity)
        assertEquals(MainActivity::class.java.name, shadowOf(activity).nextStartedActivity.component!!.className)
        assertNull(shadowOf(activity).nextStartedActivity)
        screen.destroy()
    }
}
