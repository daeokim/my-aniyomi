package eu.kanade.tachiyomi.data.personal

object ChapterAccess {
    const val NAVER = 850164079243120301L
    fun restricted(sourceId: Long, title: String) = sourceId == NAVER && title.contains("🔒")
    fun label(sourceId: Long, title: String): String? = if (sourceId != NAVER) null else if (restricted(sourceId, title)) "제한 회차 · 공식 웹에서 확인" else "무료 공개"
    fun title(sourceId: Long, title: String) = if (sourceId == NAVER) title.replace("🔒 ", "").replace("🔒", "").trim() else title
}
