package eu.kanade.tachiyomi.data.personal

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Keys depend on exact source URLs, never assigned database IDs. No progress values are stored here. */
object EpisodeMapping {
    @Serializable
    data class Work(val manga: Boolean, val id: Long, val source: Long, val url: String, val chapters: List<String> = emptyList())
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    fun legacyKey(from: Work, to: Work, chapter: String) = "dtoon_episode_map:${hash("${from.manga}:${from.id}:${to.id}:$chapter")}"
    fun stableKey(from: Work, to: Work, chapter: String): String {
        require(from.manga == to.manga)
        // Length-prefixing also makes colons and other delimiters in URLs unambiguous.
        val parts = listOf(from.manga.toString(), from.source.toString(), from.url, to.source.toString(), to.url, chapter)
        return "dtoon_episode_map_v2:" + hash(parts.joinToString("") { "${it.length}:$it" })
    }
    fun read(from: Work, to: Work, chapter: String, get: (String) -> String?, put: (String, String) -> Unit, registry: List<Work> = emptyList()): String? {
        val stable = stableKey(from, to, chapter)
        get(stable)?.takeIf { it.isNotBlank() }?.let { return it }
        fun Work.sameIdentity(other: Work) = manga == other.manga && source == other.source && url == other.url
        val oldFrom = if (registry.isEmpty()) listOf(from) else registry.filter { it.sameIdentity(from) }
        val oldTo = if (registry.isEmpty()) listOf(to) else registry.filter { it.sameIdentity(to) }
        val targets = oldFrom.flatMap { a -> oldTo.mapNotNull { b -> get(legacyKey(a, b, chapter))?.takeIf { it in to.chapters } } }.distinct()
        // Conflicting historic correspondences need explicit user selection, never an arbitrary merge.
        val old = targets.singleOrNull() ?: return null
        put(stable, old) // Lazy migration while original database IDs are still known.
        return old
    }
    const val REGISTRY_KEY = "dtoon_episode_map_registry_v1"
    fun registry(raw: String?): List<Work> = if (raw.isNullOrBlank()) emptyList() else
        runCatching { Json.decodeFromString<List<Work>>(raw) }.getOrDefault(emptyList())

    fun prepareBackup(works: List<Work>, values: Map<String, *>, put: (String, String) -> Unit) {
        if (values.keys.none { it.startsWith("dtoon_episode_map:") }) return
        // Hash-only v1 keys cannot be reversed. Preserve ID-to-identity metadata before IDs change,
        // then migrate only the requested correspondence on read. Avoid an O(works² × chapters) scan.
        val identities = (registry(values[REGISTRY_KEY] as? String) + works).map { it.copy(chapters = emptyList()) }
            .distinctBy { listOf(it.manga, it.id, it.source, it.url) }
        put(REGISTRY_KEY, Json.encodeToString(identities))
    }
}
