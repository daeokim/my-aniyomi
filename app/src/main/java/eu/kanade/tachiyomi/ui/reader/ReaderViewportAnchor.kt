package eu.kanade.tachiyomi.ui.reader

import kotlin.math.roundToInt

/** A position in source content, independent of adapter slots, spread count and window pixels. */
data class ReaderViewportAnchor(val chapterId: Long, val pageIndex: Int, val offsetFraction: Float = 0f) {
    fun matches(chapter: Long, page: Int) = chapterId == chapter && pageIndex == page

    fun pixelOffset(pageHeight: Int): Int =
        -(pageHeight.coerceAtLeast(0) * normalizedFraction(offsetFraction)).roundToInt()

    companion object {
        fun normalizedFraction(value: Float) = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

        fun capture(chapter: Long, page: Int, top: Int, height: Int, viewportTop: Int) = ReaderViewportAnchor(
            chapter, page,
            if (height > 0) normalizedFraction((viewportTop.toFloat() - top) / height) else 0f,
        )
    }
}

/** Layout/image callbacks never release a restore; only explicit user navigation does. */
class ReaderViewportState {
    var anchor: ReaderViewportAnchor? = null
        private set
    var isRestoring = false
        private set

    fun restore(position: ReaderViewportAnchor) {
        anchor = position
        isRestoring = true
    }

    fun beginUserScroll() {
        isRestoring = false
    }

    fun observe(position: ReaderViewportAnchor): Boolean {
        if (isRestoring) return false
        anchor = position
        return true
    }
}

object ReaderPositionPolicy {
    /** Duplicate selection/layout callbacks on a spread must keep its explicitly focused source page. */
    fun <T> spreadFocus(requested: T?, current: T?, first: T, contains: (T) -> Boolean): T =
        requested?.takeIf(contains) ?: current?.takeIf(contains) ?: first
}
