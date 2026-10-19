package com.lockout.gate
import com.google.gson.Gson
import com.lockout.gate.network.FocusState
import com.lockout.gate.network.FocusStartRequest
import org.junit.Assert.*
import org.junit.Test
class FocusJsonTest {
    @Test fun serverCountdownAndRequestUseExpectedJsonNames() {
        val state=Gson().fromJson("""{"active":true,"started_at":10,"ends_at":70,"remaining_s":60,"server_now":10}""",FocusState::class.java)
        assertTrue(state.active);assertEquals(60L,state.remaining_s);assertEquals(70L,state.ends_at)
        val request=Gson().toJson(FocusStartRequest("example-device",5))
        assertTrue(request.contains("\"device_id\":\"example-device\""));assertTrue(request.contains("\"minutes\":5"))
    }
}
