package eu.kanade.tachiyomi.ui.home.personal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.personal.EpisodeMapping
import eu.kanade.tachiyomi.data.personal.EpisodeMappingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import tachiyomi.domain.items.chapter.model.ChapterUpdate
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import tachiyomi.domain.items.episode.model.EpisodeUpdate
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Explicit, one-to-one correspondence. Page offsets and playback timestamps are never guessed. */
data class ProgressCopyScreen(val fromId: Long, val toId: Long, val manga: Boolean) : Screen() {
    private data class Row(val id: Long, val title: String, val url: String, val complete: Boolean, val bookmark: Boolean)
    @Composable override fun Content() {
        val scope = rememberCoroutineScope()
        var source by remember { mutableStateOf<List<Row>>(emptyList()) }
        var target by remember { mutableStateOf<List<Row>>(emptyList()) }
        var fromWork by remember { mutableStateOf<EpisodeMapping.Work?>(null) }
        var toWork by remember { mutableStateOf<EpisodeMapping.Work?>(null) }
        val matches = remember { mutableStateMapOf<Long, Long>() }
        var choosing by remember { mutableStateOf<Row?>(null) }
        var message by remember { mutableStateOf("회차를 불러오는 중…") }
        var saving by remember { mutableStateOf(false) }
        var confirm by remember { mutableStateOf(false) }
        LaunchedEffect(fromId, toId) {
            try {
                if (manga) {
                    val repo = Injekt.get<ChapterRepository>()
                    source = repo.getChapterByMangaId(fromId).filter { it.read || it.bookmark }.map { Row(it.id, it.name, it.url, it.read, it.bookmark) }
                    target = repo.getChapterByMangaId(toId).map { Row(it.id, it.name, it.url, it.read, it.bookmark) }
                } else {
                    val repo = Injekt.get<EpisodeRepository>()
                    source = repo.getEpisodeByAnimeId(fromId).filter { it.seen || it.bookmark }.map { Row(it.id, it.name, it.url, it.seen, it.bookmark) }
                    target = repo.getEpisodeByAnimeId(toId).map { Row(it.id, it.name, it.url, it.seen, it.bookmark) }
                }
                if (manga) {
                    val get = Injekt.get<tachiyomi.domain.entries.manga.interactor.GetManga>()
                    val from = get.await(fromId) ?: error("Source work unavailable")
                    val to = get.await(toId) ?: error("Target work unavailable")
                    fromWork = EpisodeMapping.Work(true, fromId, from.source, from.url, source.map { it.url })
                    toWork = EpisodeMapping.Work(true, toId, to.source, to.url, target.map { it.url })
                } else {
                    val get = Injekt.get<tachiyomi.domain.entries.anime.interactor.GetAnime>()
                    val from = get.await(fromId) ?: error("Source work unavailable")
                    val to = get.await(toId) ?: error("Target work unavailable")
                    fromWork = EpisodeMapping.Work(false, fromId, from.source, from.url, source.map { it.url })
                    toWork = EpisodeMapping.Work(false, toId, to.source, to.url, target.map { it.url })
                }
                source.forEach { row ->
                    val url = EpisodeMappingStore.read(requireNotNull(fromWork), requireNotNull(toWork), row.url)
                    target.singleOrNull { it.url == url }?.takeIf { it.id !in matches.values }?.let { matches[row.id] = it.id }
                }
                message = when { target.isEmpty() -> "대상 작품의 상세 화면에서 회차 목록을 먼저 불러와 주세요."; source.isEmpty() -> "복사할 완료·북마크 기록이 없습니다."; else -> "회차 제목을 확인해 직접 대응시키세요. 완료·북마크만 합치며 원본 기록은 유지합니다. 페이지·재생 위치·시청 기록은 길이가 다를 수 있어 복사하지 않습니다." }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "회차를 불러오지 못했습니다. 다시 열어 주세요." }
        }
        choosing?.let { row ->
            var query by remember(row.id) { mutableStateOf("") }
            AlertDialog(onDismissRequest = { choosing = null }, title = { Text("${row.title}의 대상 회차") }, text = {
                Column {
                    OutlinedTextField(query, { query = it }, label = { Text("회차 검색") })
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(target.filter { it.title.contains(query, true) && (it.id !in matches.values || it.id == matches[row.id]) }) { other ->
                            TextButton(onClick = { matches[row.id] = other.id; choosing = null }) { Text(other.title) }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { matches.remove(row.id); choosing = null }) { Text("대응 해제") } })
        }
        if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("${matches.size}개 회차 진행도 복사") }, text = { Text("선택한 대상 회차의 완료·북마크를 합칩니다. 대상에 이미 있는 기록은 지우지 않습니다.") }, confirmButton = {
            TextButton(onClick = {
                confirm = false; saving = true
                scope.launch {
                    try {
                        // Re-read before merging so records updated while the dialog was open are preserved.
                        if (manga) {
                            val repo = Injekt.get<ChapterRepository>()
                            val current = repo.getChapterByMangaId(toId).associateBy { it.id }
                            val updates = source.mapNotNull { row -> matches[row.id]?.let { id -> current[id]?.let { old -> ChapterUpdate(id = id, read = old.read || row.complete, bookmark = old.bookmark || row.bookmark) } } }
                            require(updates.size == matches.size) { "회차 목록이 변경되었습니다." }
                            repo.updateAllChapters(updates)
                        } else {
                            val repo = Injekt.get<EpisodeRepository>()
                            val current = repo.getEpisodeByAnimeId(toId).associateBy { it.id }
                            val updates = source.mapNotNull { row -> matches[row.id]?.let { id -> current[id]?.let { old -> EpisodeUpdate(id = id, seen = old.seen || row.complete, bookmark = old.bookmark || row.bookmark) } } }
                            require(updates.size == matches.size) { "회차 목록이 변경되었습니다." }
                            repo.updateAllEpisodes(updates)
                        }
                        source.forEach { row -> matches[row.id]?.let { id -> target.find { it.id == id }?.let { other -> EpisodeMappingStore.save(requireNotNull(fromWork), requireNotNull(toWork), row.url, other.url) } } }
                        message = "${matches.size}개 회차의 완료·북마크를 복사했습니다."
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "복사하지 못했습니다. 회차 목록을 확인하고 다시 시도하세요." }
                    finally { saving = false }
                }
            }) { Text("복사") }
        }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } })
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
            Text("회차 대응 · 진행도 복사", style = MaterialTheme.typography.titleLarge)
            Text(message, Modifier.padding(vertical = 12.dp))
            Button(onClick = { confirm = true }, enabled = matches.isNotEmpty() && !saving) { Text(if (saving) "복사 중…" else "확인한 ${matches.size}개 복사") }
            LazyColumn(Modifier.weight(1f)) {
                items(source, key = { it.id }) { row ->
                    TextButton(onClick = { choosing = row }, enabled = !saving) {
                        Text("${row.title}\n→ ${target.find { it.id == matches[row.id] }?.title ?: "대상 회차 선택"}")
                    }
                }
            }
        }
    }
}
