package eu.kanade.tachiyomi.data.personal

import kotlinx.serialization.json.*

enum class MediaKind(val label: String) { WEBTOON("웹툰"), MANGA("만화"), MOVIE("영화"), DRAMA("드라마"), ANIME("애니"), VARIETY("예능"), VIDEO("기타 영상") }

object MediaKinds {
    private val video = mapOf(
        2135778533207260895L to MediaKind.MOVIE, 850164079243120101L to MediaKind.MOVIE,
        2494226570510620615L to MediaKind.DRAMA, 850164079243120102L to MediaKind.DRAMA,
        6870613666340329L to MediaKind.VARIETY, 850164079243120103L to MediaKind.VARIETY,
        1616219882372839663L to MediaKind.ANIME, 850164079243120104L to MediaKind.ANIME,
        850164079243120201L to MediaKind.ANIME, 850164079243120202L to MediaKind.ANIME,
    )
    fun forSource(sourceId: Long, manga: Boolean) = if (manga) {
        if (sourceId in setOf(850164079243120301L, 7080800841003944426L, 760550510744678728L)) MediaKind.WEBTOON else MediaKind.MANGA
    } else video[sourceId] ?: MediaKind.VIDEO

    /** AniList has anime but no Korean dramas, films or variety shows: genre recommendations only fit 애니. */
    fun animeRecommendations(sourceId: Long) = forSource(sourceId, manga = false) == MediaKind.ANIME
}

/** Explicit user-confirmed groups. Original entries, source IDs, downloads and histories stay intact. */
object WorkLinks {
    private fun groups(): List<Set<String>> = runCatching {
        Json.parseToJsonElement(PersonalStore.text("work_groups", "[]").get()).jsonArray
            .map { a -> a.jsonArray.map { it.jsonPrimitive.content }.toSet() }
    }.getOrDefault(emptyList())
    @Synchronized fun members(key: String) = groups().firstOrNull { key in it }.orEmpty()
    @Synchronized fun connect(first: String, second: String) {
        if (first == second || first.substringBefore(':') != second.substringBefore(':')) return
        val groups = groups()
        val joined = groups.filter { first in it || second in it }.flatten().toSet() + setOf(first, second)
        save(groups.filterNot { first in it || second in it } + listOf(joined))
    }
    @Synchronized fun disconnect(key: String) = save(groups().map { it - key }.filter { it.size > 1 })
    private fun save(groups: List<Set<String>>) = PersonalStore.text("work_groups").set(
        JsonArray(groups.map { JsonArray(it.sorted().map(::JsonPrimitive)) }).toString(),
    )
    fun key(sourceId: Long, path: String, manga: Boolean) = (if (manga) "manga:" else "video:") + PersonalStore.key("$sourceId:$path")
}
