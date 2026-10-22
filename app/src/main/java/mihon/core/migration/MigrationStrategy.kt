package mihon.core.migration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

interface MigrationStrategy {
    operator fun invoke(migrations: List<Migration>): Deferred<Boolean>
}

class DefaultMigrationStrategy(
    private val migrationJobFactory: MigrationJobFactory,
    private val migrationCompletedListener: MigrationCompletedListener,
    private val scope: CoroutineScope,
) : MigrationStrategy {

    override operator fun invoke(migrations: List<Migration>): Deferred<Boolean> = with(scope) {
        if (migrations.isEmpty()) {
            return@with CompletableDeferred(false)
        }

        val chain = migrationJobFactory.create(migrations)

        async {
            val migrated = chain.await()
            if (migrated) migrationCompletedListener()
            migrated
        }
    }
}

class InitialMigrationStrategy(private val strategy: DefaultMigrationStrategy) : MigrationStrategy {

    override operator fun invoke(migrations: List<Migration>): Deferred<Boolean> {
        return strategy(migrations.filter { it.isAlways })
    }
}

class NoopMigrationStrategy(val state: Boolean) : MigrationStrategy {

    override fun invoke(migrations: List<Migration>): Deferred<Boolean> {
        return CompletableDeferred(state)
    }
}

class VersionRangeMigrationStrategy(
    private val versions: IntRange,
    private val strategy: DefaultMigrationStrategy,
) : MigrationStrategy {

    override operator fun invoke(migrations: List<Migration>): Deferred<Boolean> {
        return strategy(migrations.filter { it.isAlways || it.version.toInt() in versions })
    }
}
