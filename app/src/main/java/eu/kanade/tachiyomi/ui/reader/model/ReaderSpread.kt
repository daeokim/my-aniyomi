package eu.kanade.tachiyomi.ui.reader.model

/** Personal fork: two distinct pages in logical reading order, never across chapters. */
data class ReaderSpread(val first: ReaderPage, val second: ReaderPage) {
    fun contains(page: ReaderPage) = first == page || second == page
}
