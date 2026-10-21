package com.lockout.gate

import android.content.Context
import com.lockout.gate.network.FocusState
import com.lockout.gate.state.SessionStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class PublicFocusAccountTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("lockout_session", Context.MODE_PRIVATE)
    private val id = "12345678-1234-4234-8234-123456789abc"
    private val secret = "new-public-device-secret-for-test-only-123456"

    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun setupDoesNotResetSelectionsPausesWallpaperOrBreakAccounting() {
        val store = SessionStore(context)
        if (BuildConfig.PERSONAL_SETTINGS_GUARD) {
            assertThrows(IllegalStateException::class.java) { store.savePublicFocusAccount(id, secret) }
            return
        }
        store.standardBlockedPackages = setOf("example.social")
        store.customBackgroundPath = "/private/owner-wallpaper.jpg"
        assertTrue(store.setProtection(false)); assertTrue(store.setProtection(true))
        assertTrue(store.startBreakLocally())
        val breakId = store.breakId
        assertTrue(store.savePublicFocusAccount(id, secret))
        val reloaded = SessionStore(context)
        assertTrue(reloaded.publicFocusEnrolled)
        assertEquals(id, reloaded.focusDeviceId)
        assertEquals(secret, reloaded.focusDeviceSecret)
        assertEquals(1, reloaded.pausesRemainingThisMonth)
        assertEquals(breakId, reloaded.breakId)
        assertTrue(reloaded.activeBreak)
        assertEquals(setOf("example.social"), reloaded.standardBlockedPackages)
        assertEquals("/private/owner-wallpaper.jpg", reloaded.customBackgroundPath)
        assertFalse(prefs.all.keys.any { it.contains("recovery") || it.contains("admin_key") })
    }

    @Test fun activeLockCannotBeDeletedOrSwitchedAndCredentialRecoveryPreservesDeadline() {
        val store = SessionStore(context)
        if (BuildConfig.PERSONAL_SETTINGS_GUARD) {
            assertThrows(IllegalStateException::class.java) { store.clearPublicFocusAccount() }
            return
        }
        assertTrue(store.savePublicFocusAccount(id, secret))
        store.updateFocus(FocusState(true, 10, 70, 60, 10), allowStart = true)
        val deadline = store.focusEndsAtWall()
        assertFalse(store.clearPublicFocusAccount())
        assertFalse(store.savePublicFocusAccount("87654321-4321-4321-8321-cba987654321", secret))
        assertTrue(store.savePublicFocusAccount(id, secret + "rotated"))
        assertEquals(deadline, SessionStore(context).focusEndsAtWall())
        assertTrue(store.focusActive())
        assertEquals(2, store.pausesRemainingThisMonth)
    }

    @Test fun deletingIdleFocusAccountPreservesOtherAppDataAndCreatesFreshDeviceIdentity() {
        val store = SessionStore(context)
        if (BuildConfig.PERSONAL_SETTINGS_GUARD) {
            assertFalse(store.publicFocusEnrolled)
            return
        }
        store.standardBlockedPackages = setOf("example.social")
        assertTrue(store.savePublicFocusAccount(id, secret))
        assertTrue(store.clearPublicFocusAccount())
        assertFalse(SessionStore(context).publicFocusEnrolled)
        assertNotEquals(id, store.focusDeviceId)
        assertNotEquals(secret, store.focusDeviceSecret)
        assertEquals(setOf("example.social"), store.standardBlockedPackages)
    }
}
