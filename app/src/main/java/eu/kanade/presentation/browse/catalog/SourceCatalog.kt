package eu.kanade.presentation.browse.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.data.personal.PersonalStore
import eu.kanade.tachiyomi.data.personal.SourceHealth
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.math.abs

/** One catalog contract for both image and video sources. No horizontal cover lists. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun <T : Any> SourceCatalog(
    sourceKey: String,
    contentPadding: PaddingValues,
    initialTab: Int,
    initialDay: Int,
    revision: Int,
    filterKey: String,
    capability: CatalogCapability,
    onTab: (Int) -> Unit,
    onDay: (Int) -> Unit,
    onFilter: () -> Unit,
    onWeb: () -> Unit,
    itemsFor: (CatalogTab, Int) -> Flow<PagingData<StateFlow<T>>>,
    entryKey: (T) -> String,
    card: @Composable (T) -> Unit,
) {
    val pager = rememberPagerState(initialPage = initialTab.coerceIn(0, 3)) { 4 }
    val scope = rememberCoroutineScope()
    val holder = rememberSaveableStateHolder()
    var day by remember(sourceKey) { mutableIntStateOf(initialDay.coerceIn(1, 7)) }
    var showFilter by remember { mutableStateOf(true) }
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { onTab(it); showFilter = true } }
    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        Column {
            TabRow(selectedTabIndex = pager.currentPage) {
                CatalogTab.entries.forEachIndexed { i, tab ->
                    Tab(selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } }, text = { Text(tab.label) })
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f), key = { CatalogTab.entries[it].name }) { page ->
                val tab = CatalogTab.entries[page]
                Column(Modifier.fillMaxSize()) {
                    if (tab == CatalogTab.WEEKDAY) {
                        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("월", "화", "수", "목", "금", "토", "일").forEachIndexed { i, label ->
                                FilterChip(selected = day == i + 1, onClick = { day = i + 1; onDay(day); showFilter = true }, label = { Text(label) })
                            }
                        }
                    }
                    if (!capability.supports(tab)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp)) {
                            Text("이 소스는 ${tab.label} 목록을 제공하지 않습니다.")
                            Text("소스에서 제공하는 필터는 우하단 필터에서 확인할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        val dayKey = if (tab == CatalogTab.WEEKDAY) day else 0
                        val queryKey = "$sourceKey:${tab.name}:$dayKey:$filterKey"
                        holder.SaveableStateProvider(queryKey) {
                            val feed = remember(tab, dayKey, revision, filterKey) { itemsFor(tab, day) }
                            CatalogGridPage(sourceKey, queryKey, feed.collectAsLazyPagingItems(), entryKey, onWeb, { showFilter = it }, card)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = showFilter, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(16.dp)) {
            FloatingActionButton(onClick = onFilter) { Icon(Icons.Outlined.FilterList, "필터") }
        }
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun <T : Any> CatalogGridPage(
    sourceKey: String,
    queryKey: String,
    items: LazyPagingItems<StateFlow<T>>,
    entryKey: (T) -> String,
    onWeb: () -> Unit,
    onFilterVisible: (Boolean) -> Unit,
    card: @Composable (T) -> Unit,
) {
    val key = PersonalStore.key(queryKey)
    val savedIndex = remember(key) { PersonalStore.number("grid_index:$key").get().coerceAtLeast(0) }
    val savedOffset = remember(key) { PersonalStore.number("grid_offset:$key").get().coerceAtLeast(0) }
    val state = rememberLazyGridState(savedIndex, savedOffset)
    var restored by remember(key) { mutableStateOf(savedIndex == 0) }
    LaunchedEffect(key, items.itemCount, items.loadState.append, items.loadState.refresh) {
        if (!restored && items.itemCount > 0 && items.loadState.refresh !is LoadState.Loading) {
            val index = savedIndex.coerceAtMost(items.itemCount - 1)
            state.scrollToItem(index, if (index == savedIndex) savedOffset else 0)
            if (index < savedIndex && items.loadState.append !is LoadState.Error && (items.loadState.append as? LoadState.NotLoading)?.endOfPaginationReached != true) {
                items[index] // Trigger paging prefetch while restoring a saved position beyond page one.
            } else restored = true
        }
    }
    val callback by rememberUpdatedState(onFilterVisible)
    val nested = remember {
        object : NestedScrollConnection {
            private var travel = 0f
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && abs(available.y) > abs(available.x)) {
                    if (travel * available.y < 0) travel = 0f
                    travel += available.y
                    if (abs(travel) > 12f) { callback(travel > 0); travel = 0f }
                }
                return Offset.Zero
            }
        }
    }
    val error = (items.loadState.refresh as? LoadState.Error) ?: (items.loadState.append as? LoadState.Error)
    LaunchedEffect(error, items.itemCount > 0) {
        if (error != null) SourceHealth.record(sourceKey, "목록", error.error)
        else if (items.itemCount > 0) SourceHealth.record(sourceKey, "목록", null)
    }
    LaunchedEffect(state, key, restored) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged().debounce(250).collect { (i, offset) ->
                if (restored && items.itemCount > 0 && items.loadState.refresh !is LoadState.Loading) {
                    val value = items.peek(i)?.value
                    if (value != null) {
                        PersonalStore.number("grid_index:$key").set(i)
                        PersonalStore.number("grid_offset:$key").set(offset)
                        PersonalStore.text("grid_anchor:$key").set(entryKey(value))
                    }
                }
            }
    }
    var showHealth by remember { mutableStateOf(false) }
    if (showHealth) AlertDialog(onDismissRequest = { showHealth = false },
        title = { Text("소스 상태") }, text = { Text(SourceHealth.summary(sourceKey)) },
        confirmButton = { TextButton(onClick = { showHealth = false }) { Text("닫기") } })
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showHealth = true }) { Text("소스 상태") }
            TextButton(onClick = items::refresh) { Text("새로고침") }
        }
        if (error != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(SourceHealth.message("목록", error.error), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = items::retry) { Text("재시도") }
                TextButton(onClick = onWeb) { Text("웹") }
            }
        }
        if (items.itemCount == 0 && items.loadState.refresh is LoadState.Loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (items.itemCount == 0 && error == null) {
            Text("조건에 맞는 작품이 없습니다.", Modifier.padding(24.dp))
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val columns = CatalogPolicy.columns(maxWidth.value)
            var previousColumns by remember { mutableIntStateOf(columns) }
            LaunchedEffect(columns, items.itemCount) {
                if (columns != previousColumns && items.itemCount > 0) {
                    val anchor = PersonalStore.text("grid_anchor:$key").get()
                    val index = items.itemSnapshotList.items.indexOfFirst { entryKey(it.value) == anchor }
                    if (index >= 0) state.scrollToItem(index, PersonalStore.number("grid_offset:$key").get().coerceAtLeast(0))
                    previousColumns = columns
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (maxWidth < 600.dp) 96.dp else 120.dp),
                state = state,
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().nestedScroll(nested),
            ) {
                items(count = items.itemCount, key = { i -> items.peek(i)?.value?.let(entryKey) ?: "loading:$i" }) { index ->
                    val entry by items[index]?.collectAsState() ?: return@items
                    card(entry)
                }
                if (items.loadState.append is LoadState.Loading) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
fun CatalogCoverCard(title: String, cover: Any?, metadata: String = "", onClick: () -> Unit, onLongClick: () -> Unit = onClick) {
    val (display, annotation) = remember(title) { CatalogPolicy.title(title) }
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f))
        Text(display, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(listOf(metadata, annotation).filter { it.isNotBlank() }.joinToString(" · "),
            minLines = 1, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}
