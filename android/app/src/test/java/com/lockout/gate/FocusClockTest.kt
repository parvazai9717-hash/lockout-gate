package com.lockout.gate
import org.junit.Assert.assertEquals
import org.junit.Test
class FocusClockTest {
    @Test fun dateChangeCannotShortenSameBootCountdown() {
        assertEquals(5000L,FocusClock.remaining(10000,6000,2,2,999999,1000))
    }
    @Test fun rebootUsesPersistedWallDeadline() {
        assertEquals(9000L,FocusClock.remaining(10000,6000,2,3,1000,0))
    }
    @Test fun missingBootCountUsesLongerDeadline() {
        assertEquals(9000L,FocusClock.remaining(10000,6000,-1,-1,1000,1000))
    }
    @Test fun completedSessionIsZero() {
        assertEquals(0L,FocusClock.remaining(1000,1000,2,2,2000,2000))
        assertEquals(0L,FocusClock.remaining(0,1000,2,2,0,0))
    }
}
