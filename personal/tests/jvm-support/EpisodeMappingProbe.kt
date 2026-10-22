package dtoon.validation

import eu.kanade.tachiyomi.data.personal.EpisodeMapping
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object EpisodeMappingProbe {
    @Serializable data class Input(val action: String, val works: List<EpisodeMapping.Work>, val preferences: Map<String, String> = emptyMap(), val chapter: String = "")
    @Serializable data class Output(val preferences: Map<String, String>, val target: String? = null)
    @JvmStatic fun main(args: Array<String>) {
        val input = Json.decodeFromString<Input>(generateSequence(::readLine).joinToString("\n"))
        val prefs = input.preferences.toMutableMap()
        val target = when (input.action) {
            "legacy" -> { prefs[EpisodeMapping.legacyKey(input.works[0], input.works[1], input.chapter)] = input.works[1].chapters.first(); null }
            "backup" -> { EpisodeMapping.prepareBackup(input.works, prefs.toMap()) { key, value -> prefs[key] = value }; null }
            "restore" -> EpisodeMapping.read(input.works[0], input.works[1], input.chapter, prefs::get,
                { key, value -> prefs[key] = value }, EpisodeMapping.registry(prefs[EpisodeMapping.REGISTRY_KEY]))
            else -> error("Unknown action")
        }
        println(Json.encodeToString(Output(prefs, target)))
    }
}
