package eu.kanade.tachiyomi.ui.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One materialization and one stable sort; cancellation is checked between filesystem queries. */
object StorageCalculation {
    suspend fun <T, R> calculate(items: List<T>, sizeOf: (T) -> Long, map: (T, Long) -> R): List<R> {
        val context = currentCoroutineContext()
        val measured = items.map { item ->
            context.ensureActive()
            val size = sizeOf(item)
            context.ensureActive()
            size to map(item, size)
        }
        context.ensureActive()
        return measured.sortedByDescending { it.first }.map { it.second }
    }
}
