package com.lockout.gate

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class RecoveryClipboardTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val clipboard get() = context.getSystemService(ClipboardManager::class.java)

    @Test fun explicitCopyIsSensitiveAndOwnCopyExpires() {
        RecoveryClipboard.copy(context, "synthetic-recovery-key")
        assertEquals("synthetic-recovery-key", clipboard.primaryClip!!.getItemAt(0).text)
        assertTrue(clipboard.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(59))
        assertTrue(clipboard.hasPrimaryClip())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(clipboard.hasPrimaryClip())
    }

    @Test fun cleanupDoesNotEraseSomethingElseTheUserCopied() {
        RecoveryClipboard.copy(context, "synthetic-recovery-key")
        clipboard.setPrimaryClip(ClipData.newPlainText("User text", "keep this"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals("keep this", clipboard.primaryClip!!.getItemAt(0).text)
    }

    @Test fun copyingSameKeyAgainGetsItsOwnExpiry() {
        RecoveryClipboard.copy(context, "synthetic-recovery-key")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        RecoveryClipboard.copy(context, "synthetic-recovery-key")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertTrue(clipboard.hasPrimaryClip())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertFalse(clipboard.hasPrimaryClip())
    }
}
