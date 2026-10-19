package eu.kanade.tachiyomi.data.recommendations

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.jsonMime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

data class RelatedManga(val id: Int, val title: String, val cover: String?, val aliases: List<String>)

sealed interface RelatedMangaState {
    data object Loading : RelatedMangaState
    data object Empty : RelatedMangaState
    data object Error : RelatedMangaState
    data class Choose(val candidates: List<RelatedManga>) : RelatedMangaState
    data class Ready(val items: List<RelatedManga>, val sameGenre: Boolean = false) : RelatedMangaState
}

/** Public catalogue metadata only; this never reads or changes the user's AniList account. */
class RelatedMangaRepository(
    private val fetch: suspend (String, JsonObject) -> String = ::fetchPublicRecommendations,
) {
    private data class Cached(val time: Long, val state: RelatedMangaState)
    private val cache = linkedMapOf<String, Cached>()
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    suspend fun load(title: String, linkedId: Int? = null, forceRefresh: Boolean = false): RelatedMangaState {
        val key = linkedId?.let { "id:$it" } ?: "title:${normalizeTitle(title)}"
        if (!forceRefresh) cached(key)?.let { return it }
        val id = if (linkedId != null && linkedId > 0) {
            linkedId
        } else {
            if (normalizeTitle(title).isBlank()) return RelatedMangaState.Empty
            val response = request(SEARCH_QUERY, buildJsonObject { put("search", title) })
            val candidates = response.Page?.media.orEmpty().filter(::isComic)
            val match = candidates.firstOrNull { candidate ->
                candidate.names().any { normalizeTitle(it) == normalizeTitle(title) }
            }
            if (match == null) {
                val state = candidates.mapNotNull { it.toCard() }.distinctBy { it.id }
                    .takeIf { it.isNotEmpty() }?.let { RelatedMangaState.Choose(it) } ?: RelatedMangaState.Empty
                save(key, state)
                return state
            }
            match.id
        }
        val media = request(RELATED_QUERY, buildJsonObject { put("id", id) }).Media
            ?.takeIf(::isComic) ?: return RelatedMangaState.Empty
        var cards = media.recommendations?.nodes.orEmpty()
            .sortedByDescending { it.rating }
            .mapNotNull { it.mediaRecommendation }
            .filter { it.id != id && isComic(it) && !it.isAdult }
            .mapNotNull { it.toCard() }.distinctBy { it.id }.take(18)
        var sameGenre = false
        if (cards.isEmpty() && media.genres.isNotEmpty()) {
            val genres = media.genres.take(2)
            cards = request(GENRE_QUERY, buildJsonObject {
                put("id", id)
                put("genres", buildJsonArray { genres.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            }).Page?.media.orEmpty()
                .filter { it.id != id && isComic(it) && !it.isAdult && it.genres.any(genres::contains) }
                .mapNotNull { it.toCard() }.distinctBy { it.id }.take(12)
            sameGenre = true
        }
        val state = if (cards.isEmpty()) RelatedMangaState.Empty else RelatedMangaState.Ready(cards, sameGenre)
        save(key, state)
        return state
    }

    private suspend fun request(query: String, variables: JsonObject): Data {
        val response = json.decodeFromString<Response>(fetch(query, variables))
        if (response.errors.isNotEmpty() || response.data == null) throw IOException("Recommendation catalogue unavailable")
        return response.data
    }

    @Synchronized
    private fun cached(key: String): RelatedMangaState? = cache[key]
        ?.takeIf { System.nanoTime() - it.time < TimeUnit.HOURS.toNanos(6) }?.state

    @Synchronized
    private fun save(key: String, state: RelatedMangaState) {
        if (state is RelatedMangaState.Empty) return // A later retry may resolve a newly added catalogue title.
        cache[key] = Cached(System.nanoTime(), state)
        while (cache.size > 48) cache.remove(cache.keys.first())
    }

    companion object {
        val shared by lazy { RelatedMangaRepository() }

        fun normalizeTitle(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFKC)
            .replace(Regex("[\\[(](완결|연재|단행본|컬러|웹툰|개정판|완전판)[\\])]"), "")
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

private val recommendationClient by lazy {
    Injekt.get<NetworkHelper>().client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .rateLimit(permits = 30, period = 1.minutes)
        .build()
}

private suspend fun fetchPublicRecommendations(query: String, variables: JsonObject): String {
    val payload = buildJsonObject { put("query", query); put("variables", variables) }
    return recommendationClient.newCall(POST("https://graphql.anilist.co", body = payload.toString().toRequestBody(jsonMime)))
        .awaitSuccess().use { it.body.string() }
}
