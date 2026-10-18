package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.widget.LinearLayout
import eu.kanade.tachiyomi.ui.reader.model.ReaderSpread
import eu.kanade.tachiyomi.widget.ViewPagerAdapter

/** Personal fork: each page retains its own tiled image loader, zoom, retry and lifecycle. */
@SuppressLint("ViewConstructor")
class PagerSpreadHolder(
    context: Context,
    viewer: PagerViewer,
    val spread: ReaderSpread,
) : LinearLayout(context), ViewPagerAdapter.PositionableView {
    override val item get() = spread

    init {
        orientation = HORIZONTAL
        // Explicit order also works when Android's UI language itself is RTL.
        layoutDirection = LAYOUT_DIRECTION_LTR
        val pages = FoldLayoutPolicy.physicalPair(spread.first, spread.second, viewer is R2LPagerViewer)
        pages.forEach { page ->
            addView(PagerPageHolder(context, viewer, page), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }
}
