package eu.kanade.tachiyomi.ui.home.personal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.presentation.browse.catalog.CatalogCoverCard
import eu.kanade.tachiyomi.data.personal.*
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch.GlobalMangaSearchScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.withUIContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** A real landing tab combining local manga and video progress, then all installed sources. */
data object DtoonHomeTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 0u, title = "홈", icon = rememberVectorPainter(Icons.Outlined.Home))

    @OptIn(ExperimentalLayoutApi::class)
    @Composable override fun Content() {
        val model = rememberScreenModel { DtoonHomeModel() }
        val state by model.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var category by rememberSaveable { mutableIntStateOf(-2) }
        var feedMode by rememberSaveable { mutableIntStateOf(1) }
        // Saved by key so the long-press dialog survives fold/unfold activity recreation.
        var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
        var choosingLink by rememberSaveable { mutableStateOf(false) }
        var showAllSources by rememberSaveable { mutableStateOf(false) }
        var searchMenu by remember { mutableStateOf(false) }
        var revision by remember { mutableIntStateOf(0) }
        val selectedEntry = selectedKey?.let { key -> (state.recent + state.library + state.feed).firstOrNull { it.key == key } }
        if (selectedEntry != null) {
            val members = remember(selectedEntry.key, revision) { WorkLinks.members(selectedEntry.key) }
            val alternatives = (state.library + state.recent).distinctBy { it.key }
                .filter { it.key != selectedEntry.key && it.manga == selectedEntry.manga && it.kind == selectedEntry.kind }
            AlertDialog(onDismissRequest = { selectedKey = null; choosingLink = false },
                title = { Text(if (choosingLink) "같은 작품 연결" else selectedEntry.title) },
                text = {
                    Column {
                        if (choosingLink) {
                            Text("동일 작품인지 확인 후 선택하세요. 회차와 기록은 자동으로 합치지 않습니다.")
                            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 360.dp)) {
                                items(alternatives.size) { i ->
                                    val other = alternatives[i]
                                    TextButton(onClick = { WorkLinks.connect(selectedEntry.key, other.key); revision++; choosingLink = false }) { Text("${other.title} · ${model.sourceName(other)}") }
                                }
                            }
                        } else {
                            TextButton(onClick = { selectedKey = null; navigator.push(if (selectedEntry.manga) MangaScreen(selectedEntry.id) else AnimeScreen(selectedEntry.id)) }) { Text("작품 상세 · 회차 확인") }
                            TextButton(onClick = { choosingLink = true }) { Text("같은 작품의 다른 소스 연결") }
                            alternatives.filter { it.key in members }.forEach { other ->
                                TextButton(onClick = { selectedKey = null; navigator.push(if (other.manga) MangaScreen(other.id) else AnimeScreen(other.id)) }) { Text("연결된 소스: ${model.sourceName(other)}") }
                                TextButton(onClick = { selectedKey = null; navigator.push(ProgressCopyScreen(selectedEntry.id, other.id, selectedEntry.manga)) }) { Text("이 소스로 회차 대응 · 진행도 복사") }
                            }
                            if (members.isNotEmpty()) TextButton(onClick = { WorkLinks.disconnect(selectedEntry.key); revision++ }) { Text("이 작품 연결 해제") }
                        }
                    }
                }, confirmButton = { TextButton(onClick = { selectedKey = null; choosingLink = false }) { Text("닫기") } })
        }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // No separate title row: the category chips and search share one line on the folded screen.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = category == -2, onClick = { category = -2 }, label = { Text("전체") })
                    FilterChip(selected = category == -1, onClick = { category = -1 }, label = { Text("계속 보기") })
                    MediaKind.entries.forEachIndexed { i, kind -> FilterChip(selected = category == i, onClick = { category = i }, label = { Text(kind.label) }) }
                }
                Box {
                    IconButton(onClick = { searchMenu = true }) { Icon(Icons.Outlined.Search, contentDescription = "검색") }
                    DropdownMenu(expanded = searchMenu, onDismissRequest = { searchMenu = false }) {
                        DropdownMenuItem(text = { Text("만화·웹툰 검색") }, onClick = { searchMenu = false; navigator.push(GlobalMangaSearchScreen()) })
                        DropdownMenuItem(text = { Text("영상 검색") }, onClick = { searchMenu = false; navigator.push(GlobalAnimeSearchScreen()) })
                    }
                }
            }
            state.error?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            val kind = MediaKind.entries.getOrNull(category)
            if (category != -1) TabRow(selectedTabIndex = feedMode) {
                listOf("인기", "최신", "보관함").forEachIndexed { i, label -> Tab(selected = feedMode == i, onClick = { feedMode = i }, text = { Text(label) }) }
            }
            LaunchedEffect(category, feedMode, state.sources) {
                if (category != -1 && feedMode != 2 && state.sources.isNotEmpty()) model.browse(kind, feedMode == 1)
            }
            val entries = (when {
                category == -1 -> state.recent
                feedMode == 2 -> state.library.filter { kind == null || it.kind == kind }
                else -> state.feed
            }).distinctBy { WorkLinks.members(it.key).minOrNull() ?: it.key }
            val sources = if (category == -1) emptyList() else state.sources.filter { kind == null || it.kind == kind }
            BoxWithConstraints(Modifier.weight(1f)) {
                LazyVerticalGrid(columns = GridCells.Adaptive(if (maxWidth < 600.dp) 96.dp else 120.dp), state = rememberLazyGridState(),
                    contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (category == -1 && entries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("아직 읽거나 본 기록이 없습니다. 미디어 분류를 선택해 소스를 열어 주세요.") }
                    if (category == -2 && state.recent.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Text("계속 보기", style = MaterialTheme.typography.titleMedium) }
                        items(state.recent.take(6), key = { "continue:${it.key}" }) { entry ->
                            CatalogCoverCard(entry.title, entry.cover, listOfNotNull(model.sourceName(entry), HomeRecentPolicy.progressLabel(entry.progress)).joinToString(" · "), onClick = { scope.launch {
                                if (entry.manga) {
                                    val chapter = ResumeResolver.manga(entry.id)
                                    if (chapter != null) context.startActivity(ReaderActivity.newIntent(context, entry.id, chapter.id)) else navigator.push(MangaScreen(entry.id))
                                } else {
                                    val episode = ResumeResolver.anime(entry.id)
                                    if (episode != null) MainActivity.startPlayerActivity(context, entry.id, episode.id, Injekt.get<PlayerPreferences>().alwaysUseExternalPlayer().get()) else navigator.push(AnimeScreen(entry.id))
                                }
                            } }, onLongClick = { selectedKey = entry.key })
                        }
                    }
                    if (category != -1 && feedMode != 2) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Row { Text("소스별 ${if (feedMode == 1) "최신" else "인기"} · 한국어 소스 최대 6개 교차 표시", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall); TextButton(onClick = { model.browse(kind, feedMode == 1, true) }) { Text("새로고침") } } }
                        if (state.feedLoading) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        state.feedErrors.forEach { error -> item(span = { GridItemSpan(maxLineSpan) }) { Text(error, style = MaterialTheme.typography.labelSmall) } }
                    }
                    if (sources.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Text("소스 탐색 · 인기 / 최신 / 요일별 / 완결", style = MaterialTheme.typography.titleSmall) }
                        items(if (showAllSources) sources else sources.take(6), key = { "source:${it.manga}:${it.id}" }) { source ->
                            OutlinedButton(onClick = { navigator.push(if (source.manga) BrowseMangaSourceScreen(source.id, null) else BrowseAnimeSourceScreen(source.id, null)) }) { Text(source.name, maxLines = 2) }
                        }
                        if (sources.size > 6) item(span = { GridItemSpan(maxLineSpan) }) {
                            TextButton(onClick = { showAllSources = !showAllSources }) { Text(if (showAllSources) "소스 접기" else "전체 소스 ${sources.size}개 보기") }
                        }
                    } else if (category >= 0) item(span = { GridItemSpan(maxLineSpan) }) { Text("설치된 소스가 없습니다. 탐색 → 확장에서 소스를 설치하세요.") }
                    if (entries.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text(if (category == -1) "계속 보기 · 길게 눌러 소스 연결" else if (feedMode == 2) "내 보관함" else if (feedMode == 1) "최신 작품" else "인기 작품", style = MaterialTheme.typography.titleMedium) }
                    items(entries, key = { it.key }) { entry ->
                        CatalogCoverCard(entry.title, entry.cover, listOfNotNull(entry.kind.label, model.sourceName(entry), HomeRecentPolicy.progressLabel(entry.progress)).joinToString(" · "), onClick = {
                            if (category != -1) navigator.push(if (entry.manga) MangaScreen(entry.id) else AnimeScreen(entry.id))
                            else scope.launch {
                                if (entry.manga) {
                                    val chapter = ResumeResolver.manga(entry.id)
                                    if (chapter != null) context.startActivity(ReaderActivity.newIntent(context, entry.id, chapter.id)) else navigator.push(MangaScreen(entry.id))
                                } else {
                                    val episode = ResumeResolver.anime(entry.id)
                                    if (episode != null) MainActivity.startPlayerActivity(context, entry.id, episode.id, Injekt.get<PlayerPreferences>().alwaysUseExternalPlayer().get()) else navigator.push(AnimeScreen(entry.id))
                                }
                            }
                        }, onLongClick = { selectedKey = entry.key })
                    }
                }
            }
        }
        LaunchedEffect(Unit) { (context as? MainActivity)?.ready = true }
    }
}
