package eu.kanade.tachiyomi.ui.reader.model

// Personal fork: Fold8 replacement app, adaptive catalog and two-page reader changes.

import eu.kanade.tachiyomi.source.model.Page
import java.io.InputStream

open class ReaderPage(
    index: Int,
    url: String = "",
    imageUrl: String? = null,
    var stream: (() -> InputStream)? = null,
) : Page(index, url, imageUrl, null) {

    open lateinit var chapter: ReaderChapter

    /** Personal fork: discovered from image dimensions, only kept for this loaded chapter. */
    var isWidePage = false
    var loadError: String? = null
    var connectionError: Throwable? = null
    val connectionRetry = eu.kanade.tachiyomi.util.system.ConnectionRetry()
}
