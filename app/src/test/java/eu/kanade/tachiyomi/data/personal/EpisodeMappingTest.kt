package eu.kanade.tachiyomi.data.personal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EpisodeMappingTest {
    private val from = EpisodeMapping.Work(true, 1, 11, "/work/a", listOf("/a/1", "/a/2"))
    private val to = EpisodeMapping.Work(true, 2, 22, "/work/b", listOf("/b/1", "/b/2"))
    @Test fun `legacy ID keys fail after reassignment but stable keys survive empty and merged DB restore`() {
        val preferences = mutableMapOf(EpisodeMapping.legacyKey(from, to, "/a/1") to "/b/1")
        val restoredFrom = from.copy(id = 97); val restoredTo = to.copy(id = 31)
        assertNotEquals(EpisodeMapping.legacyKey(from, to, "/a/1"), EpisodeMapping.legacyKey(restoredFrom, restoredTo, "/a/1"))
        assertNull(preferences[EpisodeMapping.legacyKey(restoredFrom, restoredTo, "/a/1")])
        EpisodeMapping.prepareBackup(listOf(from, to), preferences.toMap()) { k, v -> preferences[k] = v }
        listOf(97L to 31L, 900L to 4L).forEach { (a, b) ->
            val backup = preferences.toMutableMap()
            assertEquals("/b/1", EpisodeMapping.read(from.copy(id = a), to.copy(id = b), "/a/1", backup::get, { k, v -> backup[k] = v }, EpisodeMapping.registry(backup[EpisodeMapping.REGISTRY_KEY])))
        }
    }
    @Test fun `legacy lazy migration never overwrites a newer explicit correspondence`() {
        val prefs = mutableMapOf(EpisodeMapping.legacyKey(from, to, "/a/1") to "/b/1")
        assertEquals("/b/1", EpisodeMapping.read(from, to, "/a/1", prefs::get, { k, v -> prefs[k] = v }))
        val key = EpisodeMapping.stableKey(from, to, "/a/1"); prefs[key] = "/b/2"
        EpisodeMapping.prepareBackup(listOf(from, to), prefs.toMap()) { k, v -> prefs[k] = v }
        assertEquals("/b/2", prefs[key]); assertEquals("/b/1", prefs[EpisodeMapping.legacyKey(from, to, "/a/1")])
    }
    @Test fun `unverified targets type source and URL changes never reuse old correspondences`() {
        val prefs = mutableMapOf(EpisodeMapping.legacyKey(from, to, "/a/1") to "/wrong")
        assertNull(EpisodeMapping.read(from, to, "/a/1", prefs::get, { k, v -> prefs[k] = v }))
        val key = EpisodeMapping.stableKey(from, to, "/a/1")
        assertNotEquals(key, EpisodeMapping.stableKey(from.copy(source = 88), to, "/a/1"))
        assertNotEquals(key, EpisodeMapping.stableKey(from.copy(url = "/other"), to, "/a/1"))
        assertNotEquals(key, EpisodeMapping.stableKey(from.copy(manga = false), to.copy(manga = false), "/a/1"))
    }
    @Test fun `migration does not touch read bookmark page playback or unrelated preferences`() {
        val prefs = mutableMapOf<String, Any>(EpisodeMapping.legacyKey(from, to, "/a/1") to "/b/1", "read" to true, "bookmark" to true, "page" to 17L, "position" to 325000L)
        val before = prefs.toMap(); EpisodeMapping.prepareBackup(listOf(from, to), prefs.toMap()) { k, v -> prefs[k] = v }
        before.forEach { (key, value) -> assertEquals(value, prefs[key]) }
        assertEquals(before.size + 1, prefs.size)
    }
}
