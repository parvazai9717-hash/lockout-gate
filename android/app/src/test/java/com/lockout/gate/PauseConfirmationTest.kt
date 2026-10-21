package com.lockout.gate

import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class PauseConfirmationTest {
    private fun activity() = Robolectric.buildActivity(AppCompatActivity::class.java).apply {
        get().setTheme(R.style.Theme_LockoutGate)
        setup()
    }

    @Test fun pauseRequiresFullWaitAndExplicitConfirmation() {
        val controller = activity()
        var confirmations = 0
        val dialog = PauseConfirmation.show(controller.get(), 2) { confirmations++ }
        val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        assertFalse(button.isEnabled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
        assertFalse(button.isEnabled)
        assertEquals(0, confirmations)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertTrue(button.isEnabled)
        assertEquals(0, confirmations)
        button.performClick()
        button.performClick()
        assertEquals(1, confirmations)
        assertFalse(dialog.isShowing)
        controller.pause().stop().destroy()
    }

    @Test fun cancellingOrLeavingDoesNotConfirmAndNewDialogWaitsAgain() {
        val controller = activity()
        var confirmations = 0
        val cancelled = PauseConfirmation.show(controller.get(), 2) { confirmations++ }
        cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val left = PauseConfirmation.show(controller.get(), 2) { confirmations++ }
        controller.pause()
        assertFalse(left.isShowing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertEquals(0, confirmations)
        controller.resume()
        val fresh = PauseConfirmation.show(controller.get(), 2) { confirmations++ }
        assertFalse(fresh.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
        fresh.dismiss()
        controller.pause().stop().destroy()
    }

    @Test fun publicTestCannotReplacePersonalInstallOrEnablePersonalGuard() {
        if (BuildConfig.BUILD_TYPE == "publicPreview") {
            assertEquals("com.lockout.gate.publicpreview", BuildConfig.APPLICATION_ID)
            assertFalse(BuildConfig.PERSONAL_SETTINGS_GUARD)
            assertEquals("", BuildConfig.DEVICE_KEY)
            assertEquals("", BuildConfig.FOCUS_DEVICE_ID)
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            event.packageName = "com.android.settings"
            assertFalse(PersonalAccessibilityGuard.shouldReturnHome(event, "Lockout Gate Public Test", "") {
                error("Public test must never inspect Settings contents")
            })
            event.recycle()
        } else if (BuildConfig.BUILD_TYPE == "debug") {
            assertEquals("com.lockout.gate.preview", BuildConfig.APPLICATION_ID)
            assertTrue(BuildConfig.PERSONAL_SETTINGS_GUARD)
        }
    }
}
