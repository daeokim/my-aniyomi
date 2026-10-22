package eu.kanade.tachiyomi.ui.storage

import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StorageCalculationTest {
    @Test fun `one pass and stable size ordering match the old incremental calculation`() = runTest {
        val values = listOf(5L, 3L, 9L, 3L, 0L); var measured = 0; var mapped = 0
        val old = values.fold(emptyList<Pair<Int, Long>>()) { acc, size -> (acc + (acc.size to size)).sortedByDescending { it.second } }
        val new = StorageCalculation.calculate(values.withIndex().toList(), { measured++; it.value }) { it, size -> mapped++; it.index to size }
        assertEquals(old, new); assertEquals(values.size, measured); assertEquals(values.size, mapped)
    }
    @Test fun `cancelled category computation cannot return an outdated result`() = runTest {
        var mapped = 0; var published = false
        val job = async {
            val context = currentCoroutineContext()
            StorageCalculation.calculate((1..1000).toList(), { if (it == 10) context.cancel(); it.toLong() }) { item, _ -> mapped++; item }
            published = true
        }
        runCurrent(); assertTrue(job.isCancelled); assertFalse(published); assertEquals(9, mapped)
    }
}
