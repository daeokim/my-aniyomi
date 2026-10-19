package eu.kanade.tachiyomi.data.personal

import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.history.manga.interactor.GetMangaHistory
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.items.episode.service.getEpisodeSort
import tachiyomi.domain.items.chapter.service.getChapterSort
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** DB fields lastSecondSeen/totalSeconds contain milliseconds despite their legacy names. */
object ResumePolicy {
    fun inProgress(positionMs: Long, durationMs: Long, markedSeen: Boolean): Boolean {
        if (positionMs <= 0) return false
        if (durationMs <= 0) return !markedSeen
        val endMargin = minOf(15_000L, durationMs / 20)
        return positionMs < durationMs - endMargin
    }
    fun <T> choose(items: List<T>, lastId: Long?, id: (T) -> Long, completed: (T) -> Boolean): T? {
        val index = items.indexOfFirst { id(it) == lastId }
        if (index >= 0) {
            if (!completed(items[index])) return items[index]
            return items.drop(index + 1).firstOrNull { !completed(it) }
        }
        return items.firstOrNull { !completed(it) }
    }
}

object ResumeResolver {
    suspend fun anime(animeId: Long): Episode? {
        val anime = Injekt.get<GetAnime>().await(animeId) ?: return null
        val items = Injekt.get<GetEpisodesByAnimeId>().await(animeId).sortedWith(getEpisodeSort(anime, sortDescending = false))
        val ids = items.mapTo(hashSetOf()) { it.id }
        val latest = Injekt.get<GetAnimeHistory>().await(animeId).filter { it.episodeId in ids && (it.seenAt?.time ?: 0) > 0 }
            .maxWithOrNull(compareBy({ it.seenAt?.time ?: 0 }, { it.id }))
        return ResumePolicy.choose(items, latest?.episodeId, { it.id }) { it.seen && !ResumePolicy.inProgress(it.lastSecondSeen, it.totalSeconds, it.seen) }
    }
    suspend fun manga(mangaId: Long): Chapter? {
        val manga = Injekt.get<GetManga>().await(mangaId) ?: return null
        val items = Injekt.get<GetChaptersByMangaId>().await(mangaId).sortedWith(getChapterSort(manga, sortDescending = false))
        val ids = items.mapTo(hashSetOf()) { it.id }
        val latest = Injekt.get<GetMangaHistory>().await(mangaId).filter { it.chapterId in ids && (it.readAt?.time ?: 0) > 0 }
            .maxWithOrNull(compareBy({ it.readAt?.time ?: 0 }, { it.id }))
        return ResumePolicy.choose(items, latest?.chapterId, { it.id }, { it.read })
    }
}
