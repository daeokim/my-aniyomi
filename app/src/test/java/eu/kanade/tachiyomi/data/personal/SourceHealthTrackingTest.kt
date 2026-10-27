package eu.kanade.tachiyomi.data.personal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class SourceHealthTrackingTest {
    @Test fun `a failed refresh replaces the previous successful request status`() = runTest {
        val outcomes = mutableListOf<Throwable?>()
        val record: (String, String, Throwable?) -> Unit = { key, stage, error ->
            assertEquals("anime:7", key); assertEquals("목록", stage); outcomes += error
        }
        assertEquals(listOf("a"), SourceHealth.track("anime:7", "목록", record) { listOf("a") })
        val failure = IOException("net::ERR_CONNECTION_RESET")
        try {
            SourceHealth.track("anime:7", "목록", record) { throw failure }
            fail<Unit>("Request must fail")
        } catch (error: IOException) { assertSame(failure, error) }
        assertEquals(listOf(null, failure), outcomes)
    }

    @Test fun `request deadline is recorded but leaving the screen is not a source failure`() = runTest {
        val outcomes = mutableListOf<Throwable?>()
        val record: (String, String, Throwable?) -> Unit = { _, _, error -> outcomes += error }
        try { SourceHealth.track("anime:7", "목록", record) { withTimeout(10) { delay(20) } } }
        catch (_: CancellationException) { /* Expected request deadline. */ }
        assertEquals(1, outcomes.size)
        assertEquals("목록 · 응답 시간 초과", SourceHealth.message("목록", outcomes.single()!!))
        try { SourceHealth.track("anime:7", "목록", record) { throw CancellationException("screen closed") } }
        catch (_: CancellationException) { /* Navigation must not replace the last actual result. */ }
        assertEquals(1, outcomes.size)
    }
}
