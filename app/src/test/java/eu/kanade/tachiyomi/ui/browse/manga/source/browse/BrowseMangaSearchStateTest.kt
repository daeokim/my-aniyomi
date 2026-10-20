package eu.kanade.tachiyomi.ui.browse.manga.source.browse

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreenModel.State
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BrowseMangaSearchStateTest {
    @Test
    fun `closing submitted search clears catalog query and preserves filters`() {
        val filters = FilterList(object : Filter.CheckBox("완결", true) {})
        val state = State(Listing.Search("검색어", filters), filters, "검색어")

        val cleared = state.withToolbarQuery(null)

        assertNull(cleared.toolbarQuery)
        assertNull(cleared.listing.query)
        assertFalse(cleared.isUserQuery)
        assertSame(filters, cleared.filters)
        assertSame(filters, cleared.listing.filters)
    }

    @Test
    fun `clear button restores catalog while keeping search field open`() {
        val state = State(Listing.Search("없는 작품", FilterList()), toolbarQuery = "없는 작품")

        val cleared = state.withToolbarQuery("")

        assertEquals("", cleared.toolbarQuery)
        assertNull(cleared.listing.query)
        assertFalse(cleared.isUserQuery)
    }

    @Test
    fun `editing a query waits for submit before changing the feed`() {
        val state = State(Listing.Search("기존", FilterList()), toolbarQuery = "기존")

        val edited = state.withToolbarQuery("새 검색어")

        assertEquals("새 검색어", edited.toolbarQuery)
        assertEquals("기존", edited.listing.query)
    }

    @Test
    fun `cancelling an unsubmitted search preserves popular and latest listings`() {
        for (listing in listOf(Listing.Popular, Listing.Latest)) {
            val state = State(listing, toolbarQuery = "입력중")
            assertEquals(listing, state.withToolbarQuery(null).listing)
        }
    }

    @Test
    fun `closing search twice does not bring the old query back`() {
        val state = State(Listing.Search("검색어", FilterList()), toolbarQuery = "검색어")
        val cleared = state.withToolbarQuery(null)
        assertEquals(cleared, cleared.withToolbarQuery(null))
    }
    @Test
    fun `editing and resetting draft filters preserves applied catalog snapshot`() {
        val checkbox = object : Filter.CheckBox("완결", false) {}
        val filters = FilterList(checkbox)
        val snapshot = eu.kanade.presentation.browse.catalog.MangaCatalogFilters.snapshot(filters)
        val state = State(Listing.Search("검색어", filters), filters, "검색어", catalogFiltersSnapshot = snapshot)

        checkbox.state = true
        val edited = state.copy(filters = filters)
        val reset = edited.copy(filters = FilterList())

        assertEquals(snapshot, edited.catalogFiltersSnapshot)
        assertEquals(snapshot, reset.catalogFiltersSnapshot)
        assertEquals(snapshot, edited.withToolbarQuery(null).catalogFiltersSnapshot)
        assertNotEquals(snapshot, eu.kanade.presentation.browse.catalog.MangaCatalogFilters.snapshot(filters))
    }
}
