package eu.kanade.tachiyomi.data.personal

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object ChapterAccess {
    const val NAVER = 850164079243120301L
    fun restricted(sourceId: Long, title: String) = sourceId == NAVER && title.contains("🔒")
    fun label(sourceId: Long, title: String): String? = if (sourceId != NAVER) null else if (restricted(sourceId, title)) "제한 회차 · 공식 웹에서 확인" else "무료 공개"
    fun title(sourceId: Long, title: String) = if (sourceId == NAVER) title.replace("🔒 ", "").replace("🔒", "").trim() else title

    private val KOREA = ZoneId.of("Asia/Seoul")

    /**
     * The day an update belongs to: the release date when known. Naver's dates are reliable and its next-day
     * episodes open at 23:00 KST, so those count as tomorrow; other sources never date past discovery.
     */
    fun releaseDate(sourceId: Long, dateUpload: Long, dateFetch: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
        if (sourceId == NAVER && dateUpload > 0) {
            val korea = Instant.ofEpochMilli(dateUpload).atZone(KOREA)
            return (if (korea.hour >= 23) korea.plusDays(1) else korea).toLocalDate()
        }
        val at = if (dateUpload in 1 until dateFetch) dateUpload else dateFetch
        return Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    }

    /** True when a stored episode was locked and the source now lists it as open. */
    fun opened(sourceId: Long, before: String, after: String) = restricted(sourceId, before) && !restricted(sourceId, after)
}
