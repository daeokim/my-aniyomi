package eu.kanade.tachiyomi.ui.home.personal

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.personal.*
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
data class HomeSource(val id: Long, val name: String, val manga: Boolean) { val kind get() = MediaKinds.forSource(id, manga) }
data class DtoonHomeState(val recent: List<HomeEntry> = emptyList(), val library: List<HomeEntry> = emptyList(), val sources: List<HomeSource> = emptyList(), val error: String? = null)

class DtoonHomeModel : StateScreenModel<DtoonHomeState>(DtoonHomeState()) {
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
