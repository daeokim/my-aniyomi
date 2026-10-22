package eu.kanade.tachiyomi.data.recommendations

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

data class RelatedManga(
    val id: Int,
    val title: String,
    val cover: String?,
    val aliases: List<String>,
    val localId: Long? = null,
    val genres: Set<String> = emptySet(),
    val commonGenres: Set<String> = emptySet(),
    val origin: String = "AniList",
    val score: Int = 0,
    val scoreSource: String = "direct",
)

sealed interface RelatedMangaState {
    data object Loading : RelatedMangaState
    data object Empty : RelatedMangaState
    data object Error : RelatedMangaState
    data object Disabled : RelatedMangaState
    data object GenreUnavailable : RelatedMangaState
    data class NoMatches(val genres: Set<String>) : RelatedMangaState
    data class Choose(val candidates: List<RelatedManga>) : RelatedMangaState
    data class Ready(val items: List<RelatedManga>, val sameGenre: Boolean = false, val originLabel: String? = null) : RelatedMangaState
}

/** Public catalogue metadata only; this never reads or changes the user's AniList account. */
class RelatedMangaRepository(
    private val enabled: () -> Boolean = { true },
    private val requestScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowNanos: () -> Long = System::nanoTime,
    private val fetch: suspend (String, JsonObject) -> String = ::fetchPublicRecommendations,
) {
    private data class Cached(val time: Long, val state: RelatedMangaState)
    private val cache = linkedMapOf<String, Cached>()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private data class InFlight(val work: Deferred<Result<RelatedMangaState>>, var users: Int = 0)
    private val pending = mutableMapOf<String, InFlight>()

    suspend fun load(title: String, linkedId: Int? = null, forceRefresh: Boolean = false): RelatedMangaState {
        if (!enabled()) return RelatedMangaState.Disabled
        val key = linkedId?.takeIf { it > 0 }?.let { "id:$it" } ?: "title:${normalizeTitle(title)}"
        val flight = synchronized(this) {
            if (!forceRefresh) cached(key)?.let { return it }
            val active = pending[key] ?: InFlight(requestScope.async(start = CoroutineStart.LAZY) {
                val state = try {
                    loadUncached(title, linkedId?.takeIf { it > 0 })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@async Result.failure(e)
                }
                currentCoroutineContext().ensureActive()
                if (!enabled()) return@async Result.success(RelatedMangaState.Disabled)
                save(key, state)
                Result.success(state)
            }).also { pending[key] = it }
            active.users++
            active
        }
        try {
            return flight.work.await().getOrThrow().let { if (enabled()) it else RelatedMangaState.Disabled }
        } finally {
            synchronized(this) {
                flight.users--
                if (flight.users == 0) {
                    if (pending[key] === flight) pending.remove(key)
                    // One screen leaving must not cancel another screen's identical load.
                    // Once nobody needs it, cancellation reaches the HTTP call as before.
                    flight.work.cancel()
                }
            }
        }
    }

    private suspend fun loadUncached(title: String, linkedId: Int?): RelatedMangaState {
        val id = linkedId ?: run {
            if (normalizeTitle(title).isBlank()) return RelatedMangaState.Empty
            val response = request(SEARCH_QUERY, buildJsonObject { put("search", cleanSearchTitle(title)) })
            val candidates = response.Page?.media.orEmpty().filter(::isComic).distinctBy { it.id }
            val matches = candidates.filter { candidate ->
                candidate.names().any { normalizeTitle(it) == normalizeTitle(title) }
            }
            // Even exact aliases can be shared by multiple editions. Never choose the first arbitrarily.
            if (matches.size != 1) {
                return candidates.mapNotNull { it.toCard() }.takeIf { it.isNotEmpty() }
                    ?.let { RelatedMangaState.Choose(it) } ?: RelatedMangaState.Empty
            }
            matches.single().id
        }
        val media = request(RELATED_QUERY, buildJsonObject { put("id", id) }).Media
            ?.takeIf { it.id == id && isComic(it) } ?: return RelatedMangaState.Empty
        val seedGenres = GenreMatching.normalize(media.genres)
        if (seedGenres.isEmpty()) return RelatedMangaState.GenreUnavailable
        val direct = media.recommendations?.nodes.orEmpty().sortedByDescending { it.rating }
            .mapNotNull { node -> node.mediaRecommendation?.eligibleCard(id, seedGenres)?.copy(score = node.rating) }
            .distinctBy { it.id }
        val all = direct.toMutableList()
        if (direct.size < 3) {
            try {
                // One bounded request; all seed genres are used again during final validation.
                val supplements = request(GENRE_QUERY, buildJsonObject {
                    put("id", id)
                    put("genres", buildJsonArray { media.genres.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                }).Page?.media.orEmpty()
                supplements.mapIndexedNotNull { index, candidate ->
                    candidate.eligibleCard(id, seedGenres)?.copy(score = -index, scoreSource = "genre")
                }.let(all::addAll)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (direct.isEmpty()) throw e
                // Keep already validated recommendations when only the supplementary request fails.
            }
        }
        val cards = GenreMatching.rank(seedGenres, all.distinctBy { it.id }).take(18)
        return if (cards.isEmpty()) RelatedMangaState.NoMatches(seedGenres)
        else RelatedMangaState.Ready(cards, sameGenre = true)
    }

    private fun Media.eligibleCard(seedId: Int, seedGenres: Set<String>): RelatedManga? {
        if (id == seedId || !isComic(this) || isAdult) return null
        val genres = GenreMatching.normalize(this.genres)
        val common = seedGenres intersect genres
        return toCard()?.takeIf { common.isNotEmpty() }?.copy(genres = genres, commonGenres = common)
    }

    private suspend fun request(query: String, variables: JsonObject): Data {
        currentCoroutineContext().ensureActive()
        if (!enabled()) throw CancellationException("External recommendations disabled")
        val raw = fetch(query, variables)
        currentCoroutineContext().ensureActive()
        if (!enabled()) throw CancellationException("External recommendations disabled")
        val response = json.decodeFromString<Response>(raw)
        if (response.errors.isNotEmpty() || response.data == null) throw IOException("Recommendation catalogue unavailable")
        return response.data
    }

    @Synchronized
    private fun cached(key: String): RelatedMangaState? = cache[key]
        ?.takeIf { nowNanos() - it.time < TimeUnit.HOURS.toNanos(6) }?.state

    @Synchronized
    private fun save(key: String, state: RelatedMangaState) {
        if (state is RelatedMangaState.Empty) return // A later retry may resolve a newly added catalogue title.
        cache[key] = Cached(nowNanos(), state)
        while (cache.size > 48) cache.remove(cache.keys.first())
    }

    companion object {
        val shared by lazy { createRelatedMangaRepository() }

        private fun cleanSearchTitle(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFKC)
            .replace(Regex("[\\[(](완결|연재|단행본|컬러|웹툰|개정판|완전판)[)\\]]"), "").trim()

        fun normalizeTitle(title: String): String = cleanSearchTitle(title)
            .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        private fun isComic(media: Media) = media.id > 0 && media.type == "MANGA" && media.format != "NOVEL"

        private const val FIELDS = "id type format isAdult title { english romaji native } synonyms coverImage { large } genres"
        internal val SEARCH_QUERY = "query DtoonMatch(\$search: String!) { Page(perPage: 5) { media(search: \$search, type: MANGA, format_not: NOVEL) { $FIELDS } } }"
        internal val RELATED_QUERY = "query DtoonRelated(\$id: Int!) { Media(id: \$id, type: MANGA) { $FIELDS recommendations(perPage: 25, sort: RATING_DESC) { nodes { rating mediaRecommendation { $FIELDS } } } } }"
        internal val GENRE_QUERY = "query DtoonGenres(\$id: Int!, \$genres: [String]) { Page(perPage: 18) { media(type: MANGA, format_not: NOVEL, id_not: \$id, genre_in: \$genres, isAdult: false, sort: POPULARITY_DESC) { $FIELDS } } }"
    }

    @Serializable
    private data class Response(val data: Data? = null, val errors: List<ApiError> = emptyList())
    @Serializable
    private data class ApiError(val message: String = "")
    @Serializable
    private data class Data(val Page: PageData? = null, val Media: Media? = null)
    @Serializable
    private data class PageData(val media: List<Media> = emptyList())
    @Serializable
    private data class Titles(val english: String? = null, val romaji: String? = null, val native: String? = null)
    @Serializable
    private data class Cover(val large: String? = null)
    @Serializable
    private data class Recommendations(val nodes: List<Recommendation> = emptyList())
    @Serializable
    private data class Recommendation(val rating: Int = 0, val mediaRecommendation: Media? = null)
    @Serializable
    private data class Media(
        val id: Int,
        val type: String? = null,
        val format: String? = null,
        val isAdult: Boolean = false,
        val title: Titles = Titles(),
        val synonyms: List<String> = emptyList(),
        val coverImage: Cover? = null,
        val genres: List<String> = emptyList(),
        val recommendations: Recommendations? = null,
    ) {
        fun names() = (listOfNotNull(title.native, title.english, title.romaji) + synonyms).filter { it.isNotBlank() }.distinct()
        fun toCard(): RelatedManga? {
            val names = names()
            val display = names.firstOrNull { name -> name.any { it in '\uAC00'..'\uD7A3' } }
                ?: title.english?.takeIf { it.isNotBlank() }
                ?: title.romaji?.takeIf { it.isNotBlank() }
                ?: names.firstOrNull() ?: return null
            return RelatedManga(id, display, coverImage?.large?.takeIf { it.startsWith("https://") }, names)
        }
    }
}
