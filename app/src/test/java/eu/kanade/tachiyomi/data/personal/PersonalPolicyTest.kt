package eu.kanade.tachiyomi.data.personal

import eu.kanade.presentation.browse.catalog.*
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PersonalPolicyTest {
    @Test fun `resume favors most recent unfinished item over an older gap`() {
        assertEquals(4, ResumePolicy.choose(listOf(1, 2, 3, 4, 5), 4L, { it.toLong() }, { it == 3 }))
    }
    @Test fun `completed recent item advances in reading order`() {
        assertEquals(5, ResumePolicy.choose(listOf(1, 2, 3, 4, 5), 4L, { it.toLong() }, { it == 4 }))
    }
    @Test fun `completed series never jumps back to old holes`() {
        assertNull(ResumePolicy.choose(listOf(1, 2, 3), 3L, { it.toLong() }, { it == 3 }))
    }
    @Test fun `deleted history falls back to first unfinished item`() {
        assertEquals(2, ResumePolicy.choose(listOf(1, 2), 99L, { it.toLong() }, { it == 1 }))
    }
    @Test fun `video timestamps are milliseconds including seen threshold`() {
        assertTrue(ResumePolicy.inProgress(1_500_000, 1_800_000, true))
        assertFalse(ResumePolicy.inProgress(1_790_000, 1_800_000, true))
        assertFalse(ResumePolicy.inProgress(0, 1_800_000, false))
        assertFalse(ResumePolicy.inProgress(50_000, 0, true))
        assertTrue(ResumePolicy.inProgress(50_000, 0, false))
    }
    @Test fun `short clips use proportional ending margin`() {
        assertTrue(ResumePolicy.inProgress(8_000, 10_000, true))
        assertFalse(ResumePolicy.inProgress(9_600, 10_000, true))
    }
    @Test fun `window widths yield three to six columns without device names`() {
        assertEquals(3, CatalogPolicy.columns(360f))
        assertEquals(5, CatalogPolicy.columns(700f))
        assertEquals(6, CatalogPolicy.columns(800f))
        assertEquals(1, CatalogPolicy.columns(80f))
    }
    @Test fun `title annotations split only at end and preserve original input`() {
        assertEquals("작품" to "2026", CatalogPolicy.title("작품 (2026)"))
        assertEquals("(작품) 후속" to "", CatalogPolicy.title("(작품) 후속"))
        assertEquals("(제목)" to "", CatalogPolicy.title("(제목)"))
    }
    @Test fun `invalid persisted filter data safely falls back`() {
        assertTrue(CatalogPolicy.decode("broken").isEmpty())
        assertEquals(mapOf("b" to "2", "a" to "1"), CatalogPolicy.decode(CatalogPolicy.encode(mapOf("b" to "2", "a" to "1"))))
    }
    private class Day : Filter.Select<String>("요일", arrayOf("전체", "월", "화", "수", "목", "금", "토", "일"))
    private class Status : Filter.Select<String>("상태", arrayOf("전체", "연재", "완결"))
    @Test fun `weekday query overrides only weekday and retains status`() {
        val day = Day(); val status = Status().apply { state = 1 }
        val saved = MangaCatalogFilters.snapshot(FilterList(day, status))
        val fresh = FilterList(Day(), Status())
        MangaCatalogFilters.apply(fresh, CatalogRequest(CatalogTab.WEEKDAY, 7, saved))
        assertEquals(7, (fresh[0] as Day).state)
        assertEquals(1, (fresh[1] as Status).state)
        assertEquals(0, day.state)
    }
    @Test fun `unsupported source never pretends to provide weekday or completion`() {
        val c = MangaCatalogFilters.capabilities(FilterList(), false)
        assertTrue(c.supports(CatalogTab.POPULAR))
        assertFalse(c.supports(CatalogTab.WEEKDAY))
        assertFalse(c.supports(CatalogTab.COMPLETED))
        assertFalse(c.supports(CatalogTab.LATEST))
    }
    @Test fun `completed tab identifies explicit completion value`() {
        val filters = FilterList(Day(), Status())
        MangaCatalogFilters.apply(filters, CatalogRequest(CatalogTab.COMPLETED, 0, "{}"))
        assertEquals(2, (filters[1] as Status).state)
        assertTrue(MangaCatalogFilters.capabilities(filters, true).weekday)
    }
}
