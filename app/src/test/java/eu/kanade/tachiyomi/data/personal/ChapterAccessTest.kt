package eu.kanade.tachiyomi.data.personal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ChapterAccessTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, seoul).toInstant().toEpochMilli()

    @Test fun `naver episodes opening at 23 o'clock belong to the next day`() {
        assertEquals(LocalDate.of(2026, 10, 23), ChapterAccess.releaseDate(ChapterAccess.NAVER, at(22, 23, 5), at(22, 23, 6)))
        assertEquals(LocalDate.of(2026, 10, 22), ChapterAccess.releaseDate(ChapterAccess.NAVER, at(22, 22, 59), at(22, 23, 6)))
    }
    @Test fun `naver uses the release date even when the episode was stored long before as a preview`() {
        assertEquals(LocalDate.of(2026, 10, 22), ChapterAccess.releaseDate(ChapterAccess.NAVER, at(22, 0), at(1, 12)))
    }
    @Test fun `other sources use the release date but never a date after discovery`() {
        assertEquals(LocalDate.of(2026, 10, 20), ChapterAccess.releaseDate(1, at(20, 12), at(22, 12), seoul))
        assertEquals(LocalDate.of(2026, 10, 22), ChapterAccess.releaseDate(1, at(25, 12), at(22, 12), seoul))
        assertEquals(LocalDate.of(2026, 10, 22), ChapterAccess.releaseDate(1, 0, at(22, 23, 30), seoul))
    }
    @Test fun `only naver episodes leaving the locked state count as opened`() {
        assertTrue(ChapterAccess.opened(ChapterAccess.NAVER, "12 - 🔒 미리보기", "12 - 미리보기"))
        assertFalse(ChapterAccess.opened(ChapterAccess.NAVER, "12 - 열림", "12 - 열림"))
        assertFalse(ChapterAccess.opened(ChapterAccess.NAVER, "12 - 열림", "12 - 🔒 열림"))
        assertFalse(ChapterAccess.opened(1, "🔒 12", "12"))
    }
}
