package com.lockout.gate

import com.lockout.gate.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class FocusApiContractTest {
    @Test fun bothRouteFamiliesAndPublicSetupSerializeWithoutAnyNetworkRequest() = runBlocking {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"active\":false,\"started_at\":0,\"ends_at\":0,\"remaining_s\":0,\"server_now\":100,\"public_focus_accounts\":true}"
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val api = Retrofit.Builder().baseUrl("https://focus.example.invalid/").client(client)
            .addConverterFactory(GsonConverterFactory.create()).validateEagerly(true).build().create(FocusApi::class.java)
        assertTrue(api.serviceInfo().body()!!.public_focus_accounts)
        assertEquals("/healthz", requests.last().url.encodedPath)
        for (prefix in listOf("/v1/focus", "/v1/public/focus")) {
            if (prefix == "/v1/public/focus") {
                assertTrue(api.publicState("test-device-auth", FocusEndRequest("test-device")).isSuccessful)
                assertEquals("POST", requests.last().method)
                assertEquals("$prefix/state", requests.last().url.encodedPath)
                assertNull(requests.last().url.encodedQuery)
            } else {
                assertTrue(api.focusState("$prefix/state", "test-device-auth", "test-device").isSuccessful)
                assertEquals("$prefix/state?device_id=test-device", requests.last().url.encodedPath + "?" + requests.last().url.encodedQuery)
            }
            assertTrue(api.startFocus("$prefix/start", "test-device-auth", FocusStartRequest("test-device", 5)).isSuccessful)
            assertEquals("POST", requests.last().method)
            assertTrue(api.endFocus("$prefix/end", "test-device-auth", "typed-recovery-key", FocusEndRequest("test-device")).isSuccessful)
            assertEquals("typed-recovery-key", requests.last().header("X-Admin"))
            assertEquals("test-device-auth", requests.last().header("X-Auth"))
        }
        assertTrue(api.enroll(PublicAccountRequest("test-device", "new-device-secret", "off-phone-key")).isSuccessful)
        assertEquals("/v1/public/enroll", requests.last().url.encodedPath)
        assertTrue(api.recover(PublicAccountRequest("test-device", "rotated-device-secret", "off-phone-key")).isSuccessful)
        assertEquals("/v1/public/recover", requests.last().url.encodedPath)
    }
}
