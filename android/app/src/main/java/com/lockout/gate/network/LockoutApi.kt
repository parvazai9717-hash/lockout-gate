package com.lockout.gate.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

data class LockState(
    val enabled: Boolean,
    val locked: Boolean,
    val hard_lock_active: Boolean,
    val hard_lock_days_remaining: Int,
    val breaks_used_today: Int,
    val breaks_remaining_today: Int,
    val active_break: Boolean,
    val emergency_available: Boolean,
)

data class DeviceRequest(val device_id: String)
data class BreakReportRequest(val device_id: String, val delta_ms: Long)
data class HardLockStartRequest(val device_id: String, val days: Int)

data class BreakClaimResponse(
    val accepted: Boolean,
    val confidence: Double,
    val reasoning: String,
    val enabled: Boolean,
    val locked: Boolean,
    val hard_lock_active: Boolean,
    val hard_lock_days_remaining: Int,
    val breaks_used_today: Int,
    val breaks_remaining_today: Int,
    val active_break: Boolean,
    val emergency_available: Boolean,
) {
    fun toLockState() = LockState(
        enabled, locked, hard_lock_active, hard_lock_days_remaining,
        breaks_used_today, breaks_remaining_today, active_break, emergency_available,
    )
}

data class FocusStartRequest(val device_id: String, val minutes: Int)
data class FocusEndRequest(val device_id: String)
data class FocusState(
    val active: Boolean,
    val started_at: Long,
    val ends_at: Long,
    val remaining_s: Long,
    val server_now: Long,
)

data class CheckRequest(val device_id: String, val used_ms: Long)
data class CheckResponse(val allowed: Boolean, val reason: String, val used_ms: Long, val remaining_ms: Long)

interface LockoutApi {
    @GET("/v1/lock/state")
    suspend fun lockState(
        @Header("X-Auth") auth: String,
        @Query("device_id") deviceId: String,
    ): Response<LockState>

    @POST("/v1/break/start")
    suspend fun startBreak(
        @Header("X-Auth") auth: String,
        @Body body: DeviceRequest,
    ): Response<LockState>

    @Multipart
    @POST("/v1/break/claim")
    suspend fun claimBreak(
        @Header("X-Auth") auth: String,
        @Part("device_id") deviceId: RequestBody,
        @Part file: MultipartBody.Part,
    ): Response<BreakClaimResponse>

    @POST("/v1/break/report")
    suspend fun reportBreakUsage(
        @Header("X-Auth") auth: String,
        @Body body: BreakReportRequest,
    ): Response<LockState>

    @POST("/v1/lock/hardlock/start")
    suspend fun startHardLock(
        @Header("X-Auth") auth: String,
        @Body body: HardLockStartRequest,
    ): Response<LockState>

    @POST("/v1/lock/emergency/disable")
    suspend fun emergencyDisable(
        @Header("X-Auth") auth: String,
        @Body body: DeviceRequest,
    ): Response<LockState>

    @POST("/v1/lock/emergency/enable")
    suspend fun emergencyEnable(
        @Header("X-Auth") auth: String,
        @Body body: DeviceRequest,
    ): Response<LockState>

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

    @POST("/v1/check")
    suspend fun check(
        @Header("X-Auth") auth: String,
        @Body body: CheckRequest,
    ): Response<CheckResponse>
}
