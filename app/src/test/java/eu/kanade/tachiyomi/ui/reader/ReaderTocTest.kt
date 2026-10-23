package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.data.personal.ChapterAccess
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReaderTocTest {
    private data class C(val id: Long, val name: String, val read: Boolean = false)
    private fun build(chapters: List<C>, current: Long?, source: Long) = ReaderToc.build(
        chapters, current, source, { it.id }, { it.name }, { 0L }, { it.read }, { false },
    )

    @Test fun `current chapter is marked and becomes the scroll target`() {
        val (entries, index) = build(listOf(C(3, "3화"), C(2, "2화", read = true), C(1, "1화", read = true)), 2, 1)
        assertEquals(listOf(false, true, false), entries.map { it.current })
        assertEquals(1, index)
    }
    @Test fun `naver locked previews stay listed without the lock mark and cannot be opened`() {
        val (entries, _) = build(listOf(C(2, "2화 🔒"), C(1, "1화")), 1, ChapterAccess.NAVER)
        assertEquals(listOf("2화", "1화"), entries.map { it.title })
        assertEquals(listOf(true, false), entries.map { it.locked })
        assertFalse(build(listOf(C(2, "2화 🔒")), 2, 7).first.single().locked)
    }
    @Test fun `unknown current chapter falls back to the top`() {
        assertEquals(0, build(listOf(C(1, "1화")), 99, 1).second)
        assertEquals(0, build(emptyList(), 1, 1).second)
    }
}
