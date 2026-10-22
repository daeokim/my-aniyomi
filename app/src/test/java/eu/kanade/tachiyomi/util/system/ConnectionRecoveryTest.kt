package eu.kanade.tachiyomi.util.system

import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.*
import java.util.concurrent.CancellationException
import javax.net.ssl.SSLHandshakeException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionRecoveryTest {
    @Test fun `only transport failures qualify for download retries`() {
        listOf(SocketTimeoutException(), ConnectException(), UnknownHostException(), NoRouteToHostException(), SocketException()).forEach {
            assertTrue(ConnectionRecovery.isTransient(it)); assertTrue(ConnectionRecovery.isTransient(IOException("wrapped", it)))
        }
        listOf(IOException("No space left on device"), SecurityException(), SSLHandshakeException("certificate"), HttpException(401), HttpException(403), HttpException(410), HttpException(503),
            CancellationException(), java.io.FileNotFoundException("permission"), IOException("wrapped", SSLHandshakeException("tls"))).forEach {
            assertFalse(ConnectionRecovery.isTransient(it), "$it")
        }
        assertFalse(ConnectionRecovery.isTransient(IOException("outer", CancellationException())))
    }
    @Test fun `one failed load gets one automatic retry even when connection events repeat`() {
        val retry = ConnectionRetry(); val error = ConnectException()
        assertFalse(retry.claim(error, busy = true)); assertTrue(retry.claim(error, busy = false))
        repeat(100) { assertFalse(retry.claim(ConnectException(), busy = false)) }
        retry.reset(); assertTrue(retry.claim(error, false))
        retry.reset(); assertFalse(retry.claim(HttpException(403), false)); assertFalse(retry.claim(null, false))
        assertTrue(retry.claim(error, false))
    }
    @Test fun `mpv generic errors and HTTP access failures do not imply expired URLs`() {
        listOf("HTTP error 403", "HTTP 410", "status code 401", "failed", "error", "unable", "TLS certificate failed", "No space left", "Forbidden connection timed out").forEach {
            assertFalse(ConnectionRecovery.isTransientPlayback(it), it)
        }
        listOf("Connection reset by peer", "Connection timed out", "Network is unreachable").forEach {
            assertTrue(ConnectionRecovery.isTransientPlayback(it), it)
        }
    }
    @Test fun `connection debounce ignores initial state duplicate signals and cancelled screens`() = runTest {
        val signals = MutableSharedFlow<ConnectionSignal>(); var retries = 0
        val job = launch { signals.afterConnectionSettles().collect { retries++ } }; runCurrent()
        signals.emit(ConnectionSignal("wifi", true)); advanceTimeBy(2000); runCurrent(); assertEquals(0, retries)
        signals.emit(ConnectionSignal("wifi", true)); advanceTimeBy(2000); runCurrent(); assertEquals(0, retries)
        signals.emit(ConnectionSignal("mobile", false)); advanceTimeBy(1300); runCurrent(); assertEquals(0, retries)
        signals.emit(ConnectionSignal("wg", true)); advanceTimeBy(600)
        signals.emit(ConnectionSignal("wg2", true)); advanceTimeBy(1199); runCurrent(); assertEquals(0, retries)
        advanceTimeBy(1); runCurrent(); assertEquals(1, retries)
        job.cancelAndJoin(); signals.emit(ConnectionSignal("wifi2", true)); advanceTimeBy(2000); runCurrent(); assertEquals(1, retries)
    }
}
