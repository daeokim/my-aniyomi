package mihon.core.migration

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MigrationCompletionTest {
    @Test
    fun resultDoesNotCompleteBeforeCompletionListener() = runTest {
        var listenerCompleted = false
        val factory = MigrationJobFactory(MigrationContext(false), backgroundScope)
        val strategy = DefaultMigrationStrategy(factory, { listenerCompleted = true }, backgroundScope)

        // The migration itself finishes immediately, while the completion coroutine is queued.
        val result = strategy(listOf(Migration.of(2f) { true }))

        assertFalse(result.isCompleted && !listenerCompleted, "Result completed before its completion listener")
        runCurrent()

        assertTrue(result.await())
        assertTrue(listenerCompleted)
    }
}
