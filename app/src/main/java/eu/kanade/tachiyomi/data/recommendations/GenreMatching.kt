package eu.kanade.tachiyomi.data.recommendations

import java.text.Normalizer
import java.util.Locale

/** Recommendation-only vocabulary. Unknown tags remain distinct; source metadata is untouched. */
object GenreMatching {
    private val labels = linkedMapOf(
        "action" to "액션", "adventure" to "모험", "comedy" to "코미디", "drama" to "드라마",
        "ecchi" to "에치", "fantasy" to "판타지", "horror" to "공포", "mahoushoujo" to "마법소녀",
        "mecha" to "메카", "music" to "음악", "mystery" to "미스터리", "psychological" to "심리",
        "romance" to "로맨스", "scifi" to "SF", "sliceoflife" to "일상", "sports" to "스포츠",
        "supernatural" to "초자연", "thriller" to "스릴러",
    )
    private fun key(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filterNot { it.isWhitespace() }
    private val aliases = labels.flatMap { (en, ko) -> listOf(key(en) to en, key(ko) to en) }.toMap() +
        mapOf("sciencefiction" to "scifi", "sci-fi" to "scifi", "slice-of-life" to "sliceoflife", "미스테리" to "mystery")

    // A comma is the established source genre delimiter. Slash, ampersand and middle dot may be part of a tag.
    fun normalize(values: List<String>): Set<String> = values.flatMap { it.split(',') }
        .map(::key).filter { it.isNotEmpty() }.map { aliases[it] ?: it }.toSortedSet()

    fun label(genre: String): String = labels[genre] ?: genre

    // AniList theme tags (lowercase) and genres that make a work heavy; a light seed should not lead with them.
    private val darkTags = setOf(
        "suicide", "tragedy", "gore", "torture", "self-harm", "bullying", "death game", "cannibalism",
        "body horror", "rape", "abuse", "domestic abuse", "terrorism", "war",
    )
    private val darkGenres = setOf("horror", "thriller", "psychological")

    /** True for works whose genres or strong theme tags are heavy (tragedy, suicide, horror ...). */
    fun dark(genres: Set<String>, tags: Set<String>) = genres.any { it in darkGenres } || tags.any { it in darkTags }

    private data class Group(val otherCountry: Boolean, val moodClash: Boolean, val sharedThemes: Int, val sharedGenres: Int, val genreUnion: Int)

    /**
     * With a known [seedCountry], same-country works come first (they are what installed sources can find).
     * A light seed (see [dark], judged from [seedTags]) then pushes heavy works back: a cheerful daily-life
     * webtoon should not open with a suicide story just because both are "Drama".
     * Shared theme tags (precomputed in [RelatedManga.commonTags]) then outrank broad genre overlap.
     */
    fun rank(seed: Set<String>, candidates: List<RelatedManga>, seedCountry: String? = null, seedTags: Set<String> = emptySet()): List<RelatedManga> {
        val lightSeed = !dark(seed, seedTags)
        val groups = candidates.map { it.copy(commonGenres = seed intersect it.genres) }
            .filter { it.commonGenres.isNotEmpty() }
            .groupBy {
                Group(
                    seedCountry != null && it.country != seedCountry, lightSeed && dark(it.genres, it.tags),
                    it.commonTags.size, it.commonGenres.size, (seed union it.genres).size,
                )
            }
            .toList().sortedWith(
                compareBy<Pair<Group, List<RelatedManga>>> { it.first.otherCountry }
                    .thenBy { it.first.moodClash }
                    .thenByDescending { it.first.sharedThemes }
                    .thenByDescending { it.first.sharedGenres }
                    .thenBy { it.first.genreUnion },
            )
        return groups.flatMap { (_, tied) ->
            // Preserve each origin's score order, then deterministically merge the incomparable lists
            // by their next stable ID. This avoids a non-transitive mixed-score comparator.
            val queues = tied.groupBy { it.scoreSource }.values.map { items ->
                ArrayDeque(items.sortedWith(compareByDescending<RelatedManga> { it.score }.thenBy { stableId(it) }))
            }
            buildList {
                while (true) {
                    val next = queues.filter { it.isNotEmpty() }.minByOrNull { stableId(it.first()) } ?: break
                    add(next.removeFirst())
                }
            }
        }
    }
    private fun stableId(item: RelatedManga) = item.localId ?: item.id.toLong()
}
