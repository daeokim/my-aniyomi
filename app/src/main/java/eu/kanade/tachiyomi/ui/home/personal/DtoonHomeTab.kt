package eu.kanade.tachiyomi.ui.home.personal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
        var category by rememberSaveable { mutableIntStateOf(-1) }
        var selected by remember { mutableStateOf<HomeEntry?>(null) }
        var choosingLink by remember { mutableStateOf(false) }
        var revision by remember { mutableIntStateOf(0) }
        val selectedEntry = selected
        if (selectedEntry != null) {
            val members = remember(selectedEntry.key, revision) { WorkLinks.members(selectedEntry.key) }
            val alternatives = (state.library + state.recent).distinctBy { it.key }
                .filter { it.key != selectedEntry.key && it.manga == selectedEntry.manga && it.kind == selectedEntry.kind }
            AlertDialog(onDismissRequest = { selected = null; choosingLink = false },
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
                            TextButton(onClick = { selected = null; navigator.push(if (selectedEntry.manga) MangaScreen(selectedEntry.id) else AnimeScreen(selectedEntry.id)) }) { Text("작품 상세 · 회차 확인") }
                            TextButton(onClick = { choosingLink = true }) { Text("같은 작품의 다른 소스 연결") }
                            alternatives.filter { it.key in members }.forEach { other ->
                                TextButton(onClick = { selected = null; navigator.push(if (other.manga) MangaScreen(other.id) else AnimeScreen(other.id)) }) { Text("연결된 소스: ${model.sourceName(other)}") }
                                TextButton(onClick = { selected = null; navigator.push(ProgressCopyScreen(selectedEntry.id, other.id, selectedEntry.manga)) }) { Text("이 소스로 회차 대응 · 진행도 복사") }
                            }
                            if (members.isNotEmpty()) TextButton(onClick = { WorkLinks.disconnect(selectedEntry.key); revision++ }) { Text("이 작품 연결 해제") }
                        }
                    }
                }, confirmButton = { TextButton(onClick = { selected = null; choosingLink = false }) { Text("닫기") } })
        }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Text("Dtoon", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = category == -1, onClick = { category = -1 }, label = { Text("계속 보기") })
                MediaKind.entries.forEachIndexed { i, kind -> FilterChip(selected = category == i, onClick = { category = i }, label = { Text(kind.label) }) }
            }
            state.error?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
            val entries = (if (category == -1) state.recent else state.library.filter { it.kind == MediaKind.entries[category] }).distinctBy { WorkLinks.members(it.key).minOrNull() ?: it.key }
            val sources = if (category == -1) emptyList() else state.sources.filter { it.kind == MediaKind.entries[category] }
            BoxWithConstraints(Modifier.weight(1f)) {
                LazyVerticalGrid(columns = GridCells.Adaptive(if (maxWidth < 600.dp) 96.dp else 120.dp), state = rememberLazyGridState(),
                    contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (category == -1 && entries.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("아직 읽거나 본 기록이 없습니다. 미디어 분류를 선택해 소스를 열어 주세요.") }
                    if (sources.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Text("소스 탐색 · 인기 / 최신 / 요일별 / 완결", style = MaterialTheme.typography.titleSmall) }
                        items(sources, key = { "source:${it.manga}:${it.id}" }) { source ->
                            OutlinedButton(onClick = { navigator.push(if (source.manga) BrowseMangaSourceScreen(source.id, null) else BrowseAnimeSourceScreen(source.id, null)) }) { Text(source.name, maxLines = 2) }
                        }
                    } else if (category >= 0) item(span = { GridItemSpan(maxLineSpan) }) { Text("설치된 소스가 없습니다. 탐색 → 확장에서 소스를 설치하세요.") }
                    if (entries.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text(if (category == -1) "계속 보기 · 길게 눌러 소스 연결" else "내 보관함", style = MaterialTheme.typography.titleMedium) }
                    items(entries, key = { it.key }) { entry ->
                        CatalogCoverCard(entry.title, entry.cover, "${entry.kind.label} · ${model.sourceName(entry)}", onClick = {
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
                        }, onLongClick = { selected = entry })
                    }
                }
            }
        }
        LaunchedEffect(Unit) { (context as? MainActivity)?.ready = true }
    }
}
