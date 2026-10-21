package com.lockout.gate.network
import retrofit2.Response
import retrofit2.http.*
@androidx.annotation.Keep
data class FocusStartRequest(val device_id: String, val minutes: Int)
@androidx.annotation.Keep
data class FocusEndRequest(val device_id: String)
@androidx.annotation.Keep
data class PublicAccountRequest(val device_id: String, val device_secret: String, val recovery_key: String)
@androidx.annotation.Keep
data class FocusState(
    val active: Boolean,
    val started_at: Long,
    val ends_at: Long,
    val remaining_s: Long,
    val server_now: Long,
)

interface FocusApi {
    @GET
    suspend fun focusState(
        @Url path: String,
        @Header("X-Auth") auth: String,
        @Query("device_id") deviceId: String,
    ): Response<FocusState>

    @POST
    suspend fun startFocus(
        @Url path: String,
        @Header("X-Auth") auth: String,
        @Body body: FocusStartRequest,
    ): Response<FocusState>

    /** Early exit. [admin] is typed by the user each time and never stored. */
    @POST
    suspend fun endFocus(
        @Url path: String,
        @Header("X-Auth") auth: String,
        @Header("X-Admin") admin: String,
        @Body body: FocusEndRequest,
    ): Response<FocusState>

    @POST("/v1/public/enroll")
    suspend fun enroll(@Body body: PublicAccountRequest): Response<FocusState>

    @POST("/v1/public/focus/state")
    suspend fun publicState(@Header("X-Auth") auth: String, @Body body: FocusEndRequest): Response<FocusState>

    @POST("/v1/public/recover")
    suspend fun recover(@Body body: PublicAccountRequest): Response<FocusState>

    @POST("/v1/public/account/delete")
    suspend fun deleteAccount(@Header("X-Auth") auth: String, @Body body: FocusEndRequest): Response<Map<String, Boolean>>

}
