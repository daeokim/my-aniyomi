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

    fun rank(seed: Set<String>, candidates: List<RelatedManga>): List<RelatedManga> {
        val groups = candidates.map { it.copy(commonGenres = seed intersect it.genres) }
            .filter { it.commonGenres.isNotEmpty() }
            .groupBy { it.commonGenres.size to (seed union it.genres).size }
            .toList().sortedWith { a, b ->
                val overlap = b.first.first.compareTo(a.first.first)
                if (overlap != 0) overlap else a.first.second.compareTo(b.first.second)
            }
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
