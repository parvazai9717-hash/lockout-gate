package com.lockout.gate.network

import android.content.Context
import com.lockout.gate.Config
import com.lockout.gate.state.SessionStore
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private lateinit var appContext: Context

    /** Call once from Application/Activity/Service startup before using [api]. */
    fun init(context: Context) {
        if (!::appContext.isInitialized) appContext = context.applicationContext
    }

    val api: LockoutApi by lazy {
        val store = SessionStore(appContext)
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("X-Device-Secret", store.deviceSecret)
                    .build()
                chain.proceed(request)
            }
            .build()

        Retrofit.Builder()
            .baseUrl(Config.SERVER_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LockoutApi::class.java)
    }
}
