package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.data.personal.ChapterAccess

/** One row of the reader's chapter list (목차). */
data class ReaderTocEntry(
    val id: Long,
    val title: String,
    val dateUpload: Long,
    val read: Boolean,
    val bookmark: Boolean,
    val current: Boolean,
    val locked: Boolean,
)

object ReaderToc {
    /**
     * Rows in the manga screen's order, with locked Naver previews kept visible but not openable, and the
     * index to scroll to so the current chapter is on screen when the sheet opens.
     */
    fun <T> build(
        chapters: List<T>,
        currentId: Long?,
        sourceId: Long,
        id: (T) -> Long,
        name: (T) -> String,
        dateUpload: (T) -> Long,
        read: (T) -> Boolean,
        bookmark: (T) -> Boolean,
    ): Pair<List<ReaderTocEntry>, Int> {
        val entries = chapters.map {
            ReaderTocEntry(
                id = id(it),
                title = ChapterAccess.title(sourceId, name(it)),
                dateUpload = dateUpload(it),
                read = read(it),
                bookmark = bookmark(it),
                current = id(it) == currentId,
                locked = ChapterAccess.restricted(sourceId, name(it)),
            )
        }
        return entries to entries.indexOfFirst { it.current }.coerceAtLeast(0)
    }
}
