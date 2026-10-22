package eu.kanade.tachiyomi.ui.home.personal

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.personal.*
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.entries.manga.model.toDomainManga
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.history.manga.interactor.GetMangaHistory
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.entries.anime.interactor.GetLibraryAnime
import tachiyomi.domain.entries.manga.interactor.GetLibraryManga
import tachiyomi.domain.entries.anime.model.asAnimeCover
import tachiyomi.domain.entries.manga.model.asMangaCover
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class HomeEntry(val id: Long, val title: String, val sourceId: Long, val path: String, val manga: Boolean, val cover: Any?, val at: Long = 0) {
    val key get() = WorkLinks.key(sourceId, path, manga)
    val kind get() = MediaKinds.forSource(sourceId, manga)
}
data class HomeSource(val id: Long, val name: String, val manga: Boolean, val supportsLatest: Boolean = true) { val kind get() = MediaKinds.forSource(id, manga) }

object HomeFeedPolicy {
    /** Sources that cannot serve the requested list never take a slot, so they cannot block the success cache. */
    fun pick(sources: List<HomeSource>, kind: MediaKind?, latest: Boolean, limit: Int = 6): List<HomeSource> =
        sources.filter { (kind == null || it.kind == kind) && (!latest || it.supportsLatest) }.sortedBy { it.name }
            .groupBy { it.kind }.values.toList().let { groups -> (0 until limit).flatMap { i -> groups.mapNotNull { it.getOrNull(i) } }.take(limit) }
}
data class DtoonHomeState(val recent: List<HomeEntry> = emptyList(), val library: List<HomeEntry> = emptyList(), val sources: List<HomeSource> = emptyList(), val error: String? = null, val feed: List<HomeEntry> = emptyList(), val feedLoading: Boolean = false, val feedErrors: List<String> = emptyList())

class DtoonHomeModel : StateScreenModel<DtoonHomeState>(DtoonHomeState()) {
    private var feedJob: Job? = null
    private val cache = linkedMapOf<Pair<MediaKind?, Boolean>, Pair<Long, List<HomeEntry>>>()
    fun browse(kind: MediaKind?, latest: Boolean, refresh: Boolean = false) {
        feedJob?.cancel()
        val key = kind to latest
        val cached = cache[key]
        if (!refresh && cached != null && System.currentTimeMillis() - cached.first < 300_000) {
            mutableState.update { it.copy(feed = cached.second, feedLoading = false, feedErrors = emptyList()) }; return
        }
        feedJob = screenModelScope.launch(Dispatchers.IO) {
            mutableState.update { it.copy(feed = emptyList(), feedLoading = true, feedErrors = emptyList()) }
            // Bound requests and interleave per-source results; no claim of a cross-source popularity rank.
            val available = HomeFeedPolicy.pick(
                mangaSources.getCatalogueSources().filter { it.lang == "ko" }.map { HomeSource(it.id, it.name, true, it.supportsLatest) } +
                    animeSources.getAll().filter { it.lang == "ko" && it.id >= 0 }.map { HomeSource(it.id, it.name, false, it.supportsLatest) },
                kind, latest,
            )
            val gate = Semaphore(3)
            val results = coroutineScope { available.map { source -> async {
                gate.withPermit {
                    try {
                        val entries = withTimeout(25_000) {
                            if (source.manga) {
                                val src = mangaSources.get(source.id) as? eu.kanade.tachiyomi.source.CatalogueSource ?: error("Source unavailable")
                                require(!latest || src.supportsLatest) { "최신 목록 미지원" }
                                val rows = if (latest) src.getLatestUpdates(1) else src.getPopularManga(1)
                                rows.mangas.take(12).map { remote ->
                                    val item = Injekt.get<tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga>().await(remote.toDomainManga(source.id))
                                    HomeEntry(item.id, item.title, item.source, item.url, true, item.asMangaCover())
                                }
                            } else {
                                val src = animeSources.get(source.id) ?: error("Source unavailable")
                                require(!latest || src.supportsLatest) { "최신 목록 미지원" }
                                val rows = if (latest) src.getLatestUpdates(1) else src.getPopularAnime(1)
                                rows.animes.take(12).map { remote ->
                                    val item = Injekt.get<tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime>().await(remote.toDomainAnime(source.id))
                                    HomeEntry(item.id, item.title, item.source, item.url, false, item.asAnimeCover())
                                }
                            }
                        }
                        SourceHealth.record("${if (source.manga) "manga" else "anime"}:${source.id}", "목록", null)
                        entries to null
                    } catch (e: TimeoutCancellationException) { emptyList<HomeEntry>() to "${source.name} · 응답 시간 초과" }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { emptyList<HomeEntry>() to "${source.name} · ${SourceHealth.message("목록", e)}" }
                }
            } }.awaitAll() }
            val entries = (0 until 12).flatMap { i -> results.mapNotNull { it.first.getOrNull(i) } }.distinctBy { it.key }
            if (results.none { it.second != null }) {
                cache[key] = System.currentTimeMillis() to entries
                if (cache.size > 16) cache.remove(cache.keys.first())
            }
            mutableState.update { it.copy(feed = entries, feedLoading = false, feedErrors = results.mapNotNull { row -> row.second }) }
        }
    }
    private val mangaSources = Injekt.get<MangaSourceManager>()
    private val animeSources = Injekt.get<AnimeSourceManager>()
    init {
        screenModelScope.launch(Dispatchers.IO) {
            combine(Injekt.get<GetAnimeHistory>().subscribe(""), Injekt.get<GetMangaHistory>().subscribe(""),
                Injekt.get<GetLibraryAnime>().subscribe(), Injekt.get<GetLibraryManga>().subscribe()) { ah, mh, al, ml ->
                val library = ml.map { it.manga }.map { HomeEntry(it.id, it.title, it.source, it.url, true, it.asMangaCover()) } +
                    al.map { it.anime }.map { HomeEntry(it.id, it.title, it.source, it.url, false, it.asAnimeCover()) }
                val recent = ah.sortedByDescending { it.seenAt?.time }.distinctBy { it.animeId }.take(18).mapNotNull { h ->
                    Injekt.get<GetAnime>().await(h.animeId)?.let { HomeEntry(it.id, it.title, it.source, it.url, false, h.coverData, h.seenAt?.time ?: 0) }
                } + mh.sortedByDescending { it.readAt?.time }.distinctBy { it.mangaId }.take(18).mapNotNull { h ->
                    Injekt.get<GetManga>().await(h.mangaId)?.let { HomeEntry(it.id, it.title, it.source, it.url, true, h.coverData, h.readAt?.time ?: 0) }
                }
                recent.sortedByDescending { it.at } to library
            }.catch { mutableState.update { it.copy(error = "기록을 불러오지 못했습니다. 기록 탭에서 다시 확인해 주세요.") } }
                .collect { (recent, library) -> mutableState.update { it.copy(recent = recent, library = library) } }
        }
        screenModelScope.launch {
            combine(mangaSources.catalogueSources, animeSources.sources) { manga, anime ->
                manga.map { HomeSource(it.id, it.name, true) } + anime.filterNot { it.id < 0 }.map { HomeSource(it.id, it.name, false) }
            }.catch { mutableState.update { it.copy(error = "소스 목록을 불러오지 못했습니다.") } }
                .collect { sources -> mutableState.update { it.copy(sources = sources.sortedBy { s -> s.name }) } }
        }
    }
    fun sourceName(entry: HomeEntry) = state.value.sources.firstOrNull { it.id == entry.sourceId && it.manga == entry.manga }?.name ?: "소스 ${entry.sourceId}"
}
