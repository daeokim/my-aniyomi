package eu.kanade.tachiyomi.data.recommendations

import kotlinx.serialization.json.JsonObject

// Standalone JVM harness only. Tests inject an explicit fixture transport; no real account/network is accessed.
internal fun createRelatedMangaRepository(): RelatedMangaRepository = error("Android transport is outside this harness")
internal suspend fun fetchPublicRecommendations(query: String, variables: JsonObject): String = error("Inject test transport")
