package com.lockout.gate

/** Claude's monotonic countdown, with a reboot fallback and a bounded duration. */
object FocusClock {
    fun remaining(wallEnd:Long,elapsedEnd:Long,savedBoot:Int,currentBoot:Int,wallNow:Long,elapsedNow:Long):Long {
        if (wallEnd <= 0) return 0
        val byWall=(wallEnd-wallNow).coerceAtLeast(0)
        val byElapsed=(elapsedEnd-elapsedNow).coerceAtLeast(0)
        val result=when {
            currentBoot >= 0 && currentBoot == savedBoot -> byElapsed
            currentBoot >= 0 -> byWall
            else -> maxOf(byWall,byElapsed)
        }
        return result.coerceAtMost(Config.MAX_FOCUS_MINUTES*60_000L)
    }
}
