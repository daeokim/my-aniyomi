package eu.kanade.tachiyomi.data.recommendations

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class RelatedMangaRepositoryTest {
    private fun media(id: Int, title: String, type: String = "MANGA", format: String = "MANGA", adult: Boolean = false) =
        """{"id":$id,"type":"$type","format":"$format","isAdult":$adult,"title":{"english":"$title"},"genres":["Action"]}"""

    @Test
    fun `a linked id bypasses ambiguous title search and filters unsuitable recommendations`() = runTest {
        val repo = RelatedMangaRepository { query, variables ->
            assertTrue(query.contains("DtoonRelated"))
            assertEquals(42, variables["id"]!!.jsonPrimitive.int)
            """{"data":{"Media":{"id":42,"type":"MANGA","title":{"english":"Base"},"recommendations":{"nodes":[
                {"rating":1,"mediaRecommendation":${media(7, "Lower")}},
                {"rating":9,"mediaRecommendation":${media(8, "Higher")}},
                {"rating":8,"mediaRecommendation":${media(8, "Duplicate")}},
                {"rating":99,"mediaRecommendation":${media(42, "Self")}},
                {"rating":99,"mediaRecommendation":${media(9, "Anime", type = "ANIME")}},
                {"rating":99,"mediaRecommendation":${media(10, "Novel", format = "NOVEL")}},
                {"rating":99,"mediaRecommendation":${media(11, "Adult", adult = true)}},
                {"rating":99,"mediaRecommendation":null}
            ]}}}}"""
        }
        val state = repo.load("Completely different local title", 42) as RelatedMangaState.Ready
        assertEquals(listOf(8, 7), state.items.map { it.id })
        assertFalse(state.sameGenre)
    }

    @Test
    fun `a similarly named sequel is offered for confirmation instead of silently matched`() = runTest {
        var calls = 0
        val repo = RelatedMangaRepository { _, _ ->
            calls++
            """{"data":{"Page":{"media":[${media(2, "One Piece 2")}]}}}"""
        }
        val state = repo.load("One Piece") as RelatedMangaState.Choose
        assertEquals(listOf(2), state.candidates.map { it.id })
        assertEquals(1, calls)
    }

    @Test
    fun `a Korean synonym matches without requiring an English source title`() = runTest {
        val calls = mutableListOf<String>()
        val repo = RelatedMangaRepository { query, variables ->
            calls.add(query)
            if (query.contains("DtoonMatch")) {
                assertEquals("나 혼자만 레벨업!", variables["search"]!!.jsonPrimitive.content)
                """{"data":{"Page":{"media":[{"id":42,"type":"MANGA","title":{"english":"Solo Leveling"},"synonyms":["나 혼자만 레벨업"]}]}}}"""
            } else {
                assertEquals(42, variables["id"]!!.jsonPrimitive.int)
                """{"data":{"Media":{"id":42,"type":"MANGA","recommendations":{"nodes":[]}}}}"""
            }
        }
        assertEquals(RelatedMangaState.Empty, repo.load("[완결] 나 혼자만 레벨업!"))
        assertEquals(2, calls.size)
    }

    @Test
    fun `genre fallback is labelled and excludes works outside the seed genres`() = runTest {
        val repo = RelatedMangaRepository { query, _ ->
            if (query.contains("DtoonRelated")) {
                """{"data":{"Media":{"id":1,"type":"MANGA","genres":["Action"],"recommendations":{"nodes":[]}}}}"""
            } else {
                """{"data":{"Page":{"media":[${media(2, "Action work")},{"id":3,"type":"MANGA","title":{"english":"Unrelated"},"genres":["Romance"]}]}}}"""
            }
        }
        val state = repo.load("Base", 1) as RelatedMangaState.Ready
        assertEquals(listOf(2), state.items.map { it.id })
        assertTrue(state.sameGenre)
    }

    @Test
    fun `cache is reused on navigation but explicit refresh makes another request`() = runTest {
        var calls = 0
        val repo = RelatedMangaRepository { _, _ ->
            calls++
            """{"data":{"Media":{"id":1,"type":"MANGA","recommendations":{"nodes":[{"mediaRecommendation":${media(2, "Other")}}]}}}}"""
        }
        repo.load("Base", 1)
        repo.load("Base", 1)
        assertEquals(1, calls)
        repo.load("Base", 1, forceRefresh = true)
        assertEquals(2, calls)
    }

    @Test
    fun `GraphQL errors are not cached as an empty recommendation list`() = runTest {
        var calls = 0
        val repo = RelatedMangaRepository { _, _ -> calls++; """{"errors":[{"message":"Rate limited"}]}""" }
        repeat(2) {
            try {
                repo.load("Base", 1)
                throw AssertionError("Expected an API error")
            } catch (_: IOException) {
                // The UI can retry; no successful empty result was cached.
            }
        }
        assertEquals(2, calls)
    }

    @Test
    fun `normalization preserves sequel numbers and full width letters`() {
        assertEquals("abc2", RelatedMangaRepository.normalizeTitle("ＡＢＣ ２!"))
        assertEquals("나혼자만레벨업", RelatedMangaRepository.normalizeTitle("[완결] 나 혼자만 레벨업"))
        assertFalse(RelatedMangaRepository.normalizeTitle("Title") == RelatedMangaRepository.normalizeTitle("Title 2"))
    }
}
