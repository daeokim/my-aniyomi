package eu.kanade.tachiyomi.data.library

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.service.LibraryPreferences

class LibraryUpdatePolicyTest {
    data class Work(val id: Long, val category: Long, val always: Boolean = true, val complete: Boolean = false,
                    val unviewed: Long = 0, val total: Long = 10, val started: Boolean = true, val next: Long = 0)
    private val restrictions = setOf(LibraryPreferences.ENTRY_NON_COMPLETED, LibraryPreferences.ENTRY_HAS_UNVIEWED,
        LibraryPreferences.ENTRY_NON_VIEWED, LibraryPreferences.ENTRY_OUTSIDE_RELEASE_PERIOD)
    private val library = listOf(Work(1, 1), Work(2, 2, unviewed = 5), Work(3, 3, started = false),
        Work(4, 1, complete = true), Work(5, 2, next = 999), Work(6, 2, always = false), Work(2, 3, unviewed = 5))
    private fun refresh(manual: Boolean, category: Long = -1, include: Set<Long> = emptySet(), exclude: Set<Long> = emptySet()) =
        LibraryUpdatePolicy.categories(library, manual, category, include, exclude, Work::id, Work::category)
            .filter { LibraryUpdatePolicy.skip(manual, it.always, it.complete, it.unviewed, it.total, it.started, it.next, 100, restrictions) == null }
            .map { it.id }
    @Test fun `manual refresh checks unread unstarted completed and outside release period works once`() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), refresh(true))
        assertEquals(listOf(1L), refresh(false))
    }
    @Test fun `manual entire library refresh is not restricted by automatic category preferences`() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), refresh(true, include = setOf(1), exclude = setOf(3)))
        assertEquals(emptyList<Long>(), refresh(false, include = setOf(2), exclude = setOf(3)))
    }
    @Test fun `explicit category refresh respects the chosen category and removes duplicate works`() {
        assertEquals(listOf(2L, 5L), refresh(true, category = 2))
        assertEquals(listOf(1L, 4L), refresh(true, category = 1))
    }
    @Test fun `scheduled updates retain every configured exclusion reason`() {
        val reasons = library.take(6).map { LibraryUpdatePolicy.skip(false, it.always, it.complete, it.unviewed, it.total, it.started, it.next, 100, restrictions) }
        assertEquals(listOf(null, LibraryUpdatePolicy.Skip.UNVIEWED, LibraryUpdatePolicy.Skip.NOT_STARTED,
            LibraryUpdatePolicy.Skip.COMPLETED, LibraryUpdatePolicy.Skip.RELEASE_PERIOD, LibraryUpdatePolicy.Skip.SOURCE), reasons)
    }
    @Test fun `unsupported source strategy is respected even in a manual refresh`() {
        assertEquals(LibraryUpdatePolicy.Skip.SOURCE, LibraryUpdatePolicy.skip(true, false, false, 0, 0, false, 0, 100, emptySet()))
        assertNull(LibraryUpdatePolicy.skip(false, true, true, 50, 50, false, 999, 100, emptySet()))
    }
    @Test fun `new chapters on a known entry stay after now in source order`() {
        assertEquals(listOf(1003L, 1002L, 1001L), LibraryUpdatePolicy.fetchDates(3, now = 1000, dateAdded = 500, firstLibrarySync = false))
    }
    @Test fun `first library sync backdates the backlog before the library date`() {
        val dates = LibraryUpdatePolicy.fetchDates(3, now = 1000, dateAdded = 900, firstLibrarySync = true)
        assertEquals(listOf(899L, 898L, 897L), dates)
        // updatesView keeps only date_fetch > date_added, so none of the backlog is reported.
        assertTrue(dates.all { it <= 900 })
    }
    @Test fun `first sync never dates items after now even with a future library date`() {
        assertEquals(listOf(1000L, 999L), LibraryUpdatePolicy.fetchDates(2, now = 1000, dateAdded = 5000, firstLibrarySync = true))
        assertTrue(LibraryUpdatePolicy.fetchDates(0, now = 1000, dateAdded = 900, firstLibrarySync = true).isEmpty())
    }
    @Test fun `updates badge counts each listed work once`() {
        assertEquals(1, LibraryUpdatePolicy.badgeIncrement(newItems = 7, status = 1))
        assertEquals(0, LibraryUpdatePolicy.badgeIncrement(newItems = 0, status = 1))
        for (hidden in listOf(2L, 4L, 5L, 6L)) assertEquals(0, LibraryUpdatePolicy.badgeIncrement(3, hidden))
        assertEquals(1, LibraryUpdatePolicy.badgeIncrement(3, 0))
    }
}
