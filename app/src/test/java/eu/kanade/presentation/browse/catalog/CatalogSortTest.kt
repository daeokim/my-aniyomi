package eu.kanade.presentation.browse.catalog

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CatalogSortTest {
    @Test fun `native lists remain available and unsupported combined sort is identified`() {
        for (tab in listOf(CatalogTab.POPULAR, CatalogTab.LATEST)) {
            assertTrue(CatalogPolicy.sortCombinationAllowed(tab, native = true, searchSort = false))
            assertFalse(CatalogPolicy.sortCombinationAllowed(tab, native = false, searchSort = false))
            assertTrue(CatalogPolicy.sortCombinationAllowed(tab, native = false, searchSort = true))
        }
        assertTrue(CatalogPolicy.sortCombinationAllowed(CatalogTab.WEEKDAY, false, false))
        assertTrue(CatalogPolicy.sortCombinationAllowed(CatalogTab.COMPLETED, false, false))
    }
    @Test fun `manga and anime adapters only claim explicitly named matching source sort options`() {
        val manga = FilterList(object : Filter.Select<String>("정렬", arrayOf("이름순", "최신순", "인기순")) {})
        val anime = AnimeFilterList(object : AnimeFilter.Sort("Sort", arrayOf("Latest", "Popularity")) {})
        assertTrue(MangaCatalogFilters.supportsSort(manga, CatalogTab.LATEST)); assertTrue(MangaCatalogFilters.supportsSort(manga, CatalogTab.POPULAR))
        assertTrue(AnimeCatalogFilters.supportsSort(anime, CatalogTab.LATEST)); assertTrue(AnimeCatalogFilters.supportsSort(anime, CatalogTab.POPULAR))
        assertFalse(MangaCatalogFilters.supportsSort(FilterList(), CatalogTab.LATEST)); assertFalse(AnimeCatalogFilters.supportsSort(AnimeFilterList(), CatalogTab.POPULAR))
        MangaCatalogFilters.apply(manga, CatalogRequest(CatalogTab.LATEST, 0, MangaCatalogFilters.snapshot(manga)))
        assertEquals(1, (manga[0] as Filter.Select<*>).state)
        AnimeCatalogFilters.apply(anime, CatalogRequest(CatalogTab.POPULAR, 0, AnimeCatalogFilters.snapshot(anime)))
        assertEquals(AnimeFilter.Sort.Selection(1, false), (anime[0] as AnimeFilter.Sort).state)
    }
    @Test fun `filter restoration is stable and draft edits do not mutate the applied snapshot`() {
        fun manga() = FilterList(object : Filter.Select<String>("요일", arrayOf("전체", "월", "화", "수", "목", "금", "토", "일")) {}, object : Filter.CheckBox("완결") {})
        val draft = manga(); (draft[1] as Filter.CheckBox).state = true
        val applied = MangaCatalogFilters.snapshot(draft); (draft[1] as Filter.CheckBox).state = false
        assertTrue((MangaCatalogFilters.restore(manga(), applied)[1] as Filter.CheckBox).state)
        assertEquals(applied, MangaCatalogFilters.snapshot(MangaCatalogFilters.restore(manga(), applied)))
        val day = MangaCatalogFilters.apply(manga(), CatalogRequest(CatalogTab.WEEKDAY, 4, applied))
        assertEquals(4, (day[0] as Filter.Select<*>).state); assertTrue((day[1] as Filter.CheckBox).state)
        assertTrue(MangaCatalogFilters.capabilities(manga(), true).completed)
    }
    @Test fun `keyword search preserves explicit filters and never applies the catalogue tab sort`() {
        val manga = FilterList(object : Filter.Select<String>("정렬", arrayOf("이름순", "최신순", "인기순")) {})
        val anime = AnimeFilterList(object : AnimeFilter.Sort("Sort", arrayOf("Latest", "Popularity"), AnimeFilter.Sort.Selection(0, true)) {})
        MangaCatalogFilters.apply(manga, CatalogRequest(CatalogTab.POPULAR, 0, MangaCatalogFilters.snapshot(manga), "keyword"))
        AnimeCatalogFilters.apply(anime, CatalogRequest(CatalogTab.POPULAR, 0, AnimeCatalogFilters.snapshot(anime), "keyword"))
        assertEquals(0, (manga[0] as Filter.Select<*>).state)
        assertEquals(AnimeFilter.Sort.Selection(0, true), (anime[0] as AnimeFilter.Sort).state)
    }

    @Test fun `weekday tab is removed outside naver and saved tabs keep their meaning`() {
        val naver = 850164079243120301L
        val all = CatalogCapability(weekday = true, completed = true, latest = true)
        assertEquals(CatalogTab.entries, all.forSource(naver, naver).tabs)
        val other = all.forSource(1L, naver)
        assertEquals(listOf(CatalogTab.POPULAR, CatalogTab.LATEST, CatalogTab.COMPLETED), other.tabs)
        assertEquals(2, other.pageOf(CatalogTab.COMPLETED.ordinal))
        assertEquals(0, other.pageOf(CatalogTab.WEEKDAY.ordinal))
        assertEquals(3, all.pageOf(CatalogTab.COMPLETED.ordinal))
    }
}
