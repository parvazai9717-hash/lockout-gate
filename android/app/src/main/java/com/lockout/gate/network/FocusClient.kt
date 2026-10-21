package com.lockout.gate.network

import com.lockout.gate.Config
import com.lockout.gate.BuildConfig
import com.lockout.gate.LockoutApp
import com.lockout.gate.state.SessionStore
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object FocusClient {
    val publicMode: Boolean get() = !BuildConfig.PERSONAL_SETTINGS_GUARD
    val connectionAvailable: Boolean get() = Config.SERVER_BASE_URL.startsWith("https://")
    private val store: SessionStore get() = SessionStore(LockoutApp.instance)
    val configured: Boolean get() = connectionAvailable &&
        if (publicMode) store.publicFocusEnrolled else Config.DEVICE_KEY.isNotBlank()
    private val auth: String get() = if (publicMode) store.focusDeviceSecret else Config.DEVICE_KEY
    private fun path(action: String) = if (publicMode) "/v1/public/focus/$action" else "/v1/focus/$action"
    private fun stillCurrent(store: SessionStore, id: String, credential: String) {
        check(store.focusDeviceId == id && auth == credential && (!publicMode || store.publicFocusEnrolled)) {
            "Focus account changed while the request was running"
        }
    }
    suspend fun state(store: SessionStore): retrofit2.Response<FocusState> {
        val id = store.focusDeviceId; val credential = auth
        val result = if (publicMode) api.publicState(credential, FocusEndRequest(id))
            else api.focusState(path("state"), credential, id)
        stillCurrent(store, id, credential)
        return result
    }
    suspend fun start(store: SessionStore, minutes: Int): retrofit2.Response<FocusState> {
        val id = store.focusDeviceId; val credential = auth
        return api.startFocus(path("start"), credential, FocusStartRequest(id, minutes)).also { stillCurrent(store, id, credential) }
    }
    suspend fun end(store: SessionStore, admin: String): retrofit2.Response<FocusState> {
        val id = store.focusDeviceId; val credential = auth
        return api.endFocus(path("end"), credential, admin, FocusEndRequest(id)).also { stillCurrent(store, id, credential) }
    }
    suspend fun delete(store: SessionStore): retrofit2.Response<Map<String, Boolean>> {
        val id = store.focusDeviceId; val credential = auth
        return api.deleteAccount(credential, FocusEndRequest(id)).also { stillCurrent(store, id, credential) }
    }
    val api: FocusApi by lazy {
        check(connectionAvailable) { "Focus server is not configured" }
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor { chain ->
                val store=com.lockout.gate.state.SessionStore(com.lockout.gate.LockoutApp.instance)
                chain.proceed(chain.request().newBuilder().header("X-Device-Secret",store.focusDeviceSecret).build())
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(Config.SERVER_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .validateEagerly(true)
            .build()
            .create(FocusApi::class.java)
    }
}
