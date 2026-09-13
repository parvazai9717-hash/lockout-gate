package com.lockout.gate.network

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.GET
import retrofit2.http.Part
import retrofit2.http.Query

data class WorkStartRequest(val device_id: String, val task: String)
data class WorkStartResponse(val session_id: String, val task: String, val started_at: Long)

data class WorkStateResponse(
    val active: Boolean,
    val session_id: String?,
    val task: String?,
    val unlocked: Boolean,
    val breaks_used_today: Int = 0,
    val breaks_remaining_today: Int = 0,
)

data class WorkEndRequest(val device_id: String, val session_id: String)
data class WorkEndResponse(val session_id: String, val ended_at: Long)

data class ProofResponse(val accepted: Boolean, val confidence: Double, val reasoning: String)

data class BreakClaimResponse(
    val accepted: Boolean,
    val confidence: Double,
    val reasoning: String,
    val breaks_used_today: Int,
    val breaks_remaining_today: Int,
)

data class CheckRequest(val device_id: String, val used_ms: Long)
data class CheckResponse(val allowed: Boolean, val reason: String, val used_ms: Long, val remaining_ms: Long)

interface LockoutApi {
    @POST("/v1/work/start")
    suspend fun startWork(
        @Header("X-Auth") auth: String,
        @Body body: WorkStartRequest,
    ): Response<WorkStartResponse>

    @GET("/v1/work/state")
    suspend fun workState(
        @Header("X-Auth") auth: String,
        @Query("device_id") deviceId: String,
    ): Response<WorkStateResponse>

    @POST("/v1/work/end")
    suspend fun endWork(
        @Header("X-Auth") auth: String,
        @Body body: WorkEndRequest,
    ): Response<WorkEndResponse>

    @Multipart
    @POST("/v1/work/proof")
    suspend fun submitProof(
        @Header("X-Auth") auth: String,
        @Part("device_id") deviceId: okhttp3.RequestBody,
        @Part("session_id") sessionId: okhttp3.RequestBody,
        @Part("note") note: okhttp3.RequestBody,
        @Part file: MultipartBody.Part,
    ): Response<ProofResponse>

    @POST("/v1/check")
    suspend fun check(
        @Header("X-Auth") auth: String,
        @Body body: CheckRequest,
    ): Response<CheckResponse>

    @Multipart
    @POST("/v1/break/claim")
    suspend fun claimBreak(
        @Header("X-Auth") auth: String,
        @Part("device_id") deviceId: okhttp3.RequestBody,
        @Part file: MultipartBody.Part,
    ): Response<BreakClaimResponse>
}
