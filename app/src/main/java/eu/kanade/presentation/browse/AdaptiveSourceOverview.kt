package eu.kanade.presentation.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import eu.kanade.tachiyomi.ui.reader.viewer.pager.FoldLayoutPolicy
import kotlinx.coroutines.flow.StateFlow

/** Personal fork: both feeds stay on screen, with independent scrolling/retry/pagination. */
@Composable
fun <T : Any> AdaptiveSourceOverview(
    latest: LazyPagingItems<StateFlow<T>>?,
    popular: LazyPagingItems<StateFlow<T>>,
    contentPadding: PaddingValues,
    onOpenLatest: () -> Unit,
    onOpenPopular: () -> Unit,
    card: @Composable (T) -> Unit,
) {
    val latestScroll = rememberLazyGridState()
    val popularScroll = rememberLazyGridState()
    BoxWithConstraints(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 8.dp)) {
        val wide = FoldLayoutPolicy.useSpread(maxWidth.value, maxHeight.value, true, true)
        if (wide) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OverviewSection("최신", latest, 104.dp, onOpenLatest, Modifier.weight(1f), latestScroll, card)
                OverviewSection("인기", popular, 104.dp, onOpenPopular, Modifier.weight(1f), popularScroll, card)
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OverviewSection("최신", latest, 88.dp, onOpenLatest, Modifier.weight(1f), latestScroll, card)
                OverviewSection("인기", popular, 88.dp, onOpenPopular, Modifier.weight(1f), popularScroll, card)
            }
        }
    }
}

@Composable
private fun <T : Any> OverviewSection(
    title: String,
    pagingItems: LazyPagingItems<StateFlow<T>>?,
    tileWidth: Dp,
    onOpenAll: () -> Unit,
    modifier: Modifier,
    scrollState: LazyGridState,
    card: @Composable (T) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (pagingItems != null) {
                IconButton(onClick = pagingItems::refresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "$title 새로고침")
                }
                TextButton(onClick = onOpenAll) { Text("전체") }
            }
        }
        if (pagingItems == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("최신 목록을 지원하지 않는 소스입니다.")
            }
            return@Column
        }
        if (pagingItems.itemCount == 0 && pagingItems.loadState.refresh is LoadState.Loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val failure = pagingItems.loadState.refresh as? LoadState.Error
        if (failure != null && pagingItems.itemCount > 0) {
            TextButton(onClick = pagingItems::retry) { Text("갱신 실패 · 다시 시도") }
        }
        if (pagingItems.itemCount == 0) {
            Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    failure?.error?.localizedMessage ?: "표시할 작품이 없습니다.",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = pagingItems::retry) { Text("다시 시도") }
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(tileWidth),
            state = scrollState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(count = pagingItems.itemCount) { index ->
                val value by pagingItems[index]?.collectAsState() ?: return@items
                card(value)
            }
            if (pagingItems.loadState.append is LoadState.Loading) {
                item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            }
            if (pagingItems.loadState.append is LoadState.Error) {
                item { TextButton(onClick = pagingItems::retry) { Text("다시 시도") } }
            }
        }
    }
}
