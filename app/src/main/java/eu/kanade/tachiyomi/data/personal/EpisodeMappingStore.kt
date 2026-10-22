package eu.kanade.tachiyomi.data.personal

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.anime.model.Anime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object EpisodeMappingStore {
    private val store get() = Injekt.get<PreferenceStore>()
    fun read(from: EpisodeMapping.Work, to: EpisodeMapping.Work, chapter: String) = EpisodeMapping.read(
        from, to, chapter, { store.getString(it).get() }, { key, value -> store.getString(key).set(value) },
        EpisodeMapping.registry(store.getString(EpisodeMapping.REGISTRY_KEY).get()),
    )
    fun save(from: EpisodeMapping.Work, to: EpisodeMapping.Work, chapter: String, target: String) {
        require(chapter in from.chapters && target in to.chapters)
        store.getString(EpisodeMapping.stableKey(from, to, chapter)).set(target)
    }
    suspend fun migrateBeforeBackup(mangas: List<Manga>, animes: List<Anime>) {
        val values = store.getAll()
        if (values.keys.none { it.startsWith("dtoon_episode_map:") }) return
        val works = mangas.map { EpisodeMapping.Work(true, it.id, it.source, it.url) } +
            animes.map { EpisodeMapping.Work(false, it.id, it.source, it.url) }
        EpisodeMapping.prepareBackup(works, values) { key, value -> store.getString(key).set(value) }
    }
}
