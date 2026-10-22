package com.lockout.gate

import com.lockout.gate.network.FocusClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class FocusTransportSecurityTest {
    @Test fun onlyPersonalFocusRequestsCarryTheInstallationSecret() {
        for (publicMode in listOf(true, false)) {
            for (path in listOf("/healthz", "/v1/public/enroll", "/v1/public/focus/state", "/v1/focus/state")) {
                var secretReads = 0
                var observed: Request? = null
                val client = FocusClient.createHttpClient(publicMode) {
                    secretReads++
                    "synthetic-installation-secret"
                }.newBuilder().addInterceptor { chain ->
                    observed = chain.request()
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                        .code(200).message("OK").body("{}".toResponseBody()).build()
                }.build()
                client.newCall(Request.Builder().url("https://test.invalid$path")
                    .header("X-Device-Secret", "stale-secret").build()).execute().close()
                val personalFocus = !publicMode && path == "/v1/focus/state"
                assertEquals(if (personalFocus) "synthetic-installation-secret" else null,
                    observed!!.header("X-Device-Secret"))
                assertEquals(if (personalFocus) 1 else 0, secretReads)
                assertFalse(client.followRedirects)
                assertFalse(client.followSslRedirects)
                assertEquals(45_000, client.callTimeoutMillis)
            }
        }
    }
}
