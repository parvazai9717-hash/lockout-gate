package com.lockout.gate.network

import com.lockout.gate.Config
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object FocusClient {
    val configured: Boolean get() = Config.SERVER_BASE_URL.startsWith("https://") && Config.DEVICE_KEY.isNotBlank()
    val api: FocusApi by lazy {
        check(configured) { "Focus server is not configured" }
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
            .build()
            .create(FocusApi::class.java)
    }
}
