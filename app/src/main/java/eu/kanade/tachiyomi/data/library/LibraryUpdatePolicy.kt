package eu.kanade.tachiyomi.data.library

import tachiyomi.domain.library.service.LibraryPreferences

object LibraryUpdatePolicy {
    enum class Skip { SOURCE, COMPLETED, UNVIEWED, NOT_STARTED, RELEASE_PERIOD }

    fun skip(manual: Boolean, alwaysUpdate: Boolean, completed: Boolean, unviewed: Long,
             total: Long, started: Boolean, nextUpdate: Long, windowEnd: Long, restrictions: Set<String>): Skip? = when {
        !alwaysUpdate -> Skip.SOURCE
        manual -> null
        LibraryPreferences.ENTRY_NON_COMPLETED in restrictions && completed -> Skip.COMPLETED
        LibraryPreferences.ENTRY_HAS_UNVIEWED in restrictions && unviewed != 0L -> Skip.UNVIEWED
        LibraryPreferences.ENTRY_NON_VIEWED in restrictions && total > 0 && !started -> Skip.NOT_STARTED
        LibraryPreferences.ENTRY_OUTSIDE_RELEASE_PERIOD in restrictions && nextUpdate > windowEnd -> Skip.RELEASE_PERIOD
        else -> null
    }

    /**
     * Fetch dates for newly stored items in source order (newest first, highest value). The first sync of a
     * library entry is dated before its library date, so the existing backlog never counts as an update.
     */
    fun fetchDates(count: Int, now: Long, dateAdded: Long, firstLibrarySync: Boolean): List<Long> {
        val start = if (firstLibrarySync) minOf(now, dateAdded - 1) else now + count
        return List(count) { start - it }
    }

    // Completed, licensed, cancelled and on hiatus: the updates list (updatesView.sq) hides these works.
    private val HIDDEN_FROM_UPDATES = setOf(2L, 4L, 5L, 6L)

    /**
     * Updates-tab badge step for one work: the list shows one row per work (its latest open item) and hides
     * finished or paused works, so the badge counts works, not every new chapter or episode.
     */
    fun badgeIncrement(newItems: Int, status: Long) = if (newItems > 0 && status !in HIDDEN_FROM_UPDATES) 1 else 0

    fun <T> categories(items: List<T>, manual: Boolean, category: Long, included: Set<Long>, excluded: Set<Long>,
                       id: (T) -> Long, categoryId: (T) -> Long): List<T> {
        if (category != -1L) return items.filter { categoryId(it) == category }.distinctBy(id)
        if (manual) return items.distinctBy(id)
        val excludedIds = items.filter { categoryId(it) in excluded }.map(id).toSet()
        return items.filter { (included.isEmpty() || categoryId(it) in included) && id(it) !in excludedIds }.distinctBy(id)
    }
}
