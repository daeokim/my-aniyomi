package eu.kanade.tachiyomi.ui.home.personal

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.items.chapter.interactor.SetReadStatus
import eu.kanade.domain.items.episode.interactor.SetSeenStatus
import eu.kanade.domain.track.anime.interactor.TrackEpisode
import eu.kanade.domain.track.manga.interactor.TrackChapter
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.personal.PersonalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.items.chapter.model.ChapterUpdate
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import tachiyomi.domain.items.episode.model.EpisodeUpdate
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class CopyRow(val id: Long, val title: String, val url: String, val number: Double, val complete: Boolean, val bookmark: Boolean)
data class CopyPlan(val read: List<Long>, val bookmark: List<Long>, val trackNumber: Double?)

object ProgressCopyPolicy {
    /** Merges completion and bookmarks onto matched target rows only; existing target records are never cleared. */
    fun plan(source: List<CopyRow>, target: List<CopyRow>, matches: Map<Long, Long>): CopyPlan? {
        val byId = target.associateBy { it.id }
        val pairs = source.mapNotNull { row -> matches[row.id]?.let(byId::get)?.let { row to it } }
        // A vanished or re-fetched target row means the user's confirmation no longer describes the change.
        if (pairs.size != matches.size || pairs.map { it.second.id }.toSet().size != pairs.size) return null
        return CopyPlan(
            read = pairs.filter { (from, to) -> from.complete && !to.complete }.map { it.second.id },
            bookmark = pairs.filter { (from, to) -> from.bookmark && !to.bookmark }.map { it.second.id },
            trackNumber = pairs.filter { it.first.complete }.maxOfOrNull { it.second.number }?.takeIf { it > 0 },
        )
    }
}

data class ProgressCopyState(
    val source: List<CopyRow> = emptyList(),
    val target: List<CopyRow> = emptyList(),
    val matches: Map<Long, Long> = emptyMap(),
    val message: String = "회차를 불러오는 중…",
    val saving: Boolean = false,
    val autoTrack: AutoTrackState = AutoTrackState.NEVER,
)

/** Held by the screen model so manual matches survive fold/unfold activity recreation. */
class ProgressCopyScreenModel(private val fromId: Long, private val toId: Long, private val manga: Boolean) :
    StateScreenModel<ProgressCopyState>(ProgressCopyState()) {
    private val chapters = Injekt.get<ChapterRepository>()
    private val episodes = Injekt.get<EpisodeRepository>()

    init {
        screenModelScope.launch {
            try {
                val source = rows(fromId).filter { it.complete || it.bookmark }
                val target = rows(toId)
                val matches = mutableMapOf<Long, Long>()
                source.forEach { row ->
                    val url = mapPref(row).get()
                    target.singleOrNull { it.url == url }?.takeIf { it.id !in matches.values }?.let { matches[row.id] = it.id }
                }
                val message = when {
                    target.isEmpty() -> "대상 작품의 상세 화면에서 회차 목록을 먼저 불러와 주세요."
                    source.isEmpty() -> "복사할 완료·북마크 기록이 없습니다."
                    else -> "회차 제목을 확인해 직접 대응시키세요. 완료·북마크만 합치며 원본 기록은 유지합니다. 페이지·재생 위치·시청 기록은 길이가 다를 수 있어 복사하지 않습니다."
                }
                val autoTrack = Injekt.get<TrackPreferences>().autoUpdateTrackOnMarkRead().get()
                mutableState.update { it.copy(source = source, target = target, matches = matches, message = message, autoTrack = autoTrack) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(message = "회차를 불러오지 못했습니다. 다시 열어 주세요.") } }
        }
    }

    fun match(sourceId: Long, targetId: Long?) = mutableState.update {
        it.copy(matches = if (targetId == null) it.matches - sourceId else it.matches + (sourceId to targetId))
    }

    fun copy(syncTrackers: Boolean) {
        if (state.value.saving) return
        mutableState.update { it.copy(saving = true) }
        screenModelScope.launch {
            val snapshot = state.value
            try {
                // Re-read before merging so records updated while the dialog was open are preserved.
                val plan = ProgressCopyPolicy.plan(snapshot.source, rows(toId), snapshot.matches)
                    ?: error("회차 목록이 변경되었습니다.")
                if (manga) {
                    val current = chapters.getChapterByMangaId(toId).filter { it.id in plan.read }
                    // Same path as marking read in the app, including delete-after-read for downloads.
                    val result = Injekt.get<SetReadStatus>().await(true, *current.toTypedArray())
                    if (result is SetReadStatus.Result.InternalError) throw result.error
                    if (plan.bookmark.isNotEmpty()) chapters.updateAllChapters(plan.bookmark.map { ChapterUpdate(id = it, bookmark = true) })
                } else {
                    val current = episodes.getEpisodeByAnimeId(toId).filter { it.id in plan.read }
                    val result = Injekt.get<SetSeenStatus>().await(true, *current.toTypedArray())
                    if (result is SetSeenStatus.Result.InternalError) throw result.error
                    if (plan.bookmark.isNotEmpty()) episodes.updateAllEpisodes(plan.bookmark.map { EpisodeUpdate(id = it, bookmark = true) })
                }
                snapshot.source.forEach { row ->
                    snapshot.matches[row.id]?.let { id -> snapshot.target.find { it.id == id } }?.let { mapPref(row).set(it.url) }
                }
                val number = plan.trackNumber
                if (syncTrackers && number != null) {
                    // Trackers only move forward; a lower or equal number is ignored by the interactors.
                    val app = Injekt.get<Application>()
                    if (manga) Injekt.get<TrackChapter>().await(app, toId, number) else Injekt.get<TrackEpisode>().await(app, toId, number)
                }
                mutableState.update { it.copy(saving = false, message = "${snapshot.matches.size}개 회차의 완료·북마크를 복사했습니다.") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(saving = false, message = "복사하지 못했습니다. 회차 목록을 확인하고 다시 시도하세요.") } }
        }
    }

    private suspend fun rows(id: Long): List<CopyRow> = if (manga) {
        chapters.getChapterByMangaId(id).map { CopyRow(it.id, it.name, it.url, it.chapterNumber, it.read, it.bookmark) }
    } else {
        episodes.getEpisodeByAnimeId(id).map { CopyRow(it.id, it.name, it.url, it.episodeNumber, it.seen, it.bookmark) }
    }

    private fun mapPref(row: CopyRow) = PersonalStore.text("episode_map:${PersonalStore.key("$manga:$fromId:$toId:${row.url}")}")
}

/** Explicit, one-to-one correspondence. Page offsets and playback timestamps are never guessed. */
data class ProgressCopyScreen(val fromId: Long, val toId: Long, val manga: Boolean) : Screen() {
    @Composable override fun Content() {
        val model = rememberScreenModel { ProgressCopyScreenModel(fromId, toId, manga) }
        val state by model.state.collectAsState()
        var choosingId by rememberSaveable { mutableStateOf<Long?>(null) }
        var confirm by rememberSaveable { mutableStateOf(false) }
        state.source.find { it.id == choosingId }?.let { row ->
            var query by rememberSaveable(row.id) { mutableStateOf("") }
            AlertDialog(onDismissRequest = { choosingId = null }, title = { Text("${row.title}의 대상 회차") }, text = {
                Column {
                    OutlinedTextField(query, { query = it }, label = { Text("회차 검색") })
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(state.target.filter { it.title.contains(query, true) && (it.id !in state.matches.values || it.id == state.matches[row.id]) }) { other ->
                            TextButton(onClick = { model.match(row.id, other.id); choosingId = null }) { Text(other.title) }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { model.match(row.id, null); choosingId = null }) { Text("대응 해제") } })
        }
        if (confirm) {
            val canTrack = state.autoTrack != AutoTrackState.NEVER
            var syncTrackers by rememberSaveable { mutableStateOf(canTrack) }
            AlertDialog(onDismissRequest = { confirm = false }, title = { Text("${state.matches.size}개 회차 진행도 복사") }, text = {
                Column {
                    Text("선택한 대상 회차의 완료·북마크를 합칩니다. 대상에 이미 있는 기록은 지우지 않습니다.")
                    if (canTrack) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(syncTrackers, { syncTrackers = it })
                            Text("연결된 트래커에도 반영")
                        }
                    }
                }
            }, confirmButton = {
                TextButton(onClick = { confirm = false; model.copy(canTrack && syncTrackers) }) { Text("복사") }
            }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } })
        }
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
            Text("회차 대응 · 진행도 복사", style = MaterialTheme.typography.titleLarge)
            Text(state.message, Modifier.padding(vertical = 12.dp))
            Button(onClick = { confirm = true }, enabled = state.matches.isNotEmpty() && !state.saving) { Text(if (state.saving) "복사 중…" else "확인한 ${state.matches.size}개 복사") }
            LazyColumn(Modifier.weight(1f)) {
                items(state.source, key = { it.id }) { row ->
                    TextButton(onClick = { choosingId = row.id }, enabled = !state.saving) {
                        Text("${row.title}\n→ ${state.target.find { it.id == state.matches[row.id] }?.title ?: "대상 회차 선택"}")
                    }
                }
            }
        }
    }
}
