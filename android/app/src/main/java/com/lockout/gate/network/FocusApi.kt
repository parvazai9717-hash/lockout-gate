package com.lockout.gate.network
import retrofit2.Response
import retrofit2.http.*
@androidx.annotation.Keep
data class FocusStartRequest(val device_id: String, val minutes: Int)
@androidx.annotation.Keep
data class FocusEndRequest(val device_id: String)
@androidx.annotation.Keep
data class FocusState(
    val active: Boolean,
    val started_at: Long,
    val ends_at: Long,
    val remaining_s: Long,
    val server_now: Long,
)

interface FocusApi {
    @GET("/v1/focus/state")
    suspend fun focusState(
        @Header("X-Auth") auth: String,
        @Query("device_id") deviceId: String,
    ): Response<FocusState>

    @POST("/v1/focus/start")
    suspend fun startFocus(
        @Header("X-Auth") auth: String,
        @Body body: FocusStartRequest,
    ): Response<FocusState>

    /** Early exit. [admin] is typed by the user each time and never stored. */
    @POST("/v1/focus/end")
    suspend fun endFocus(
        @Header("X-Auth") auth: String,
        @Header("X-Admin") admin: String,
        @Body body: FocusEndRequest,
    ): Response<FocusState>

}
