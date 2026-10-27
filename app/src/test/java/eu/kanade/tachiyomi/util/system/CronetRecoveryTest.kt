package eu.kanade.tachiyomi.util.system

import eu.kanade.tachiyomi.data.personal.SourceHealth
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.TransportErrors
import eu.kanade.tachiyomi.network.WebViewChallengeException
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import java.util.concurrent.CancellationException

class CronetRecoveryTest {
    private fun cronet(code: String, retryable: Boolean = true) = IOException(
        "Exception in CronetUrlRequest: net::$code, ErrorCode=8, InternalErrorCode=-101, Retryable=$retryable",
    )

    @Test fun `the device log reset is explained and gets one retry after a network change`() {
        val error = cronet("ERR_CONNECTION_RESET")
        assertEquals("목록 · 네트워크 연결 끊김", SourceHealth.message("목록", error))
        assertTrue(ConnectionRecovery.isTransient(IOException("extension wrapper", error)))
        val retry = ConnectionRetry()
        assertTrue(retry.claim(error, false))
        repeat(10) { assertFalse(retry.claim(error, false)) }
    }

    @Test fun `Chromium timeouts DNS and disconnected network have distinct reasons`() {
        assertEquals("목록 · 연결 시간 초과", SourceHealth.message("목록", cronet("ERR_TIMED_OUT")))
        assertEquals("목록 · 주소/DNS 연결 실패", SourceHealth.message("목록", cronet("ERR_NAME_NOT_RESOLVED")))
        assertTrue(ConnectionRecovery.isTransient(cronet("ERR_NETWORK_CHANGED")))
        assertTrue(ConnectionRecovery.isTransient(cronet("ERR_INTERNET_DISCONNECTED")))
    }

    @Test fun `certificate HTTP and cancellation errors never turn into network retries`() {
        listOf(
            SSLHandshakeException("connection reset"), HttpException(403), CancellationException(),
            cronet("ERR_CERT_AUTHORITY_INVALID"), cronet("ERR_SSL_PROTOCOL_ERROR"),
            cronet("ERR_CONNECTION_RESET", false), IOException("No space left on device"),
            IOException("HTTP 403", cronet("ERR_CONNECTION_RESET")),
            IOException("Cloudflare challenge", cronet("ERR_CONNECTION_RESET")),
        ).forEach { assertFalse(ConnectionRecovery.isTransient(it), it.toString()) }
        assertFalse(ConnectionRecovery.isTransient(IOException("net::ERR_CONNECTION_RESET", SSLHandshakeException("certificate"))))
        assertEquals(TransportErrors.Kind.SECURITY, TransportErrors.kind(cronet("ERR_CERT_DATE_INVALID")))
    }

    @Test fun `a foreground site check retains only safe headers and hides signed query tokens in logs`() {
        val request = Request.Builder().url("https://video.example/c.html?token=secret&expires=123")
            .header("User-Agent", "site-agent").header("Referer", "https://player.example/watch")
            .header("Origin", "https://player.example").header("Cookie", "private=1")
            .header("Authorization", "Bearer secret").build()
        val error = WebViewChallengeException.forRequest("사이트 확인 필요", request, IOException("challenge"))
        val challenge = WebViewChallengeException.find(IOException("extension wrapper", error))!!
        assertEquals(request.url.toString(), challenge.url)
        assertEquals(setOf("user-agent", "referer", "origin"), challenge.headers.keys.map { it.lowercase() }.toSet())
        assertFalse(error.toString().contains("secret"))
        assertFalse(ConnectionRecovery.isTransient(error))
    }

    @Test fun `WebView handoff does not replay POST or credentialed and insecure URLs`() {
        listOf(
            Request.Builder().url("https://site.example/api").post("data".toRequestBody()).build(),
            Request.Builder().url("http://site.example/").build(),
            Request.Builder().url("https://user:secret@site.example/").build(),
        ).forEach {
            assertNull(WebViewChallengeException.find(WebViewChallengeException.forRequest("check", it, IOException())))
        }
    }
}
