package eu.kanade.tachiyomi.data.recommendations

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.jsonMime
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.minutes
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun createRelatedMangaRepository() = RelatedMangaRepository(
    enabled = { Injekt.get<eu.kanade.tachiyomi.network.NetworkPreferences>().externalRecommendations().get() },
)

private val recommendationClient by lazy {
    Injekt.get<NetworkHelper>().client.newBuilder()
        .callTimeout(15, TimeUnit.SECONDS)
        .rateLimit(permits = 30, period = 1.minutes)
        .build()
}

internal suspend fun fetchPublicRecommendations(query: String, variables: JsonObject): String {
    val payload = buildJsonObject { put("query", query); put("variables", variables) }
    return recommendationClient.newCall(POST("https://graphql.anilist.co", body = payload.toString().toRequestBody(jsonMime)))
        .awaitSuccess().use { it.body.string() }
}
