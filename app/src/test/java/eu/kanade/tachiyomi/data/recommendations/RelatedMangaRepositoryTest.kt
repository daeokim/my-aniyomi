package eu.kanade.tachiyomi.data.recommendations

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RelatedMangaRepositoryTest {
    private fun media(id: Int, genres: List<String> = listOf("Action"), title: String = "Title $id", type: String = "MANGA", format: String = "MANGA", adult: Boolean = false, country: String? = null): String = buildJsonObject {
        put("id", id); put("type", type); put("format", format); put("isAdult", adult)
        if (country != null) put("countryOfOrigin", country)
        put("title", buildJsonObject { put("english", title) })
        put("genres", buildJsonArray { genres.forEach { add(JsonPrimitive(it)) } })
    }.toString()
    private fun related(genres: List<String> = listOf("Action"), nodes: List<Pair<Int, String>> = emptyList(), country: String? = null): String {
        val base = Json.parseToJsonElement(media(1, genres, country = country)).toString().dropLast(1)
        return """{"data":{"Media":$base,"recommendations":{"nodes":[${nodes.joinToString { """{"rating":${it.first},"mediaRecommendation":${it.second}}""" }}]}}}}"""
    }
    private fun page(vararg items: String) = """{"data":{"Page":{"media":[${items.joinToString()}]}}}"""
    private fun valid() = related(nodes = listOf(1 to media(2), 9 to media(3), 4 to media(4)))

    @Test fun `linked ID skips identification and removes self duplicates anime novel adult and disjoint genres`() = runTest {
        val calls = mutableListOf<String>()
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { query, vars ->
            calls += query; assertEquals(1, vars["id"]!!.jsonPrimitive.int)
            related(nodes = listOf(1 to media(2), 9 to media(3), 8 to media(3), 4 to media(4),
                99 to media(1), 99 to media(5, type = "ANIME"), 99 to media(6, format = "NOVEL"),
                99 to media(7, adult = true), 99 to media(8, listOf("Romance"))))
        }
        val state = repo.load("different local title", 1) as RelatedMangaState.Ready
        assertEquals(listOf(3, 4, 2), state.items.map { it.id }); assertTrue(state.sameGenre)
        assertEquals(1, calls.size)
        assertTrue(state.items.all { it.commonGenres == setOf("action") })
    }
    @Test fun `a similarly named sequel waits for user selection after just one search`() = runTest {
        var calls = 0
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { _, _ -> calls++; page(media(2, title = "One Piece 2")) }
        val state = repo.load("One Piece") as RelatedMangaState.Choose
        assertEquals(listOf(2), state.candidates.map { it.id }); assertEquals(1, calls)
    }
    @Test fun `multiple exact aliases also require a choice`() = runTest {
        var calls = 0
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { _, _ -> calls++; page(media(2, title = "Same"), media(3, title = "Same")) }
        assertTrue(repo.load("Same") is RelatedMangaState.Choose); assertEquals(1, calls)
    }
    @Test fun `Korean synonym identifies a work and bounds the whole load to three requests`() = runTest {
        val calls = mutableListOf<String>()
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { query, _ ->
            calls += query
            when (query) {
                RelatedMangaRepository.SEARCH_QUERY -> page(media(1, title = "Solo").dropLast(1) + ""","synonyms":["나 혼자만 레벨업"]}""")
                RelatedMangaRepository.RELATED_QUERY -> related(nodes = listOf(9 to media(2, listOf("Romance"))))
                else -> page(media(3))
            }
        }
        val state = repo.load("[완결] 나 혼자만 레벨업!") as RelatedMangaState.Ready
        assertEquals(listOf(3), state.items.map { it.id }); assertEquals(3, calls.size)
        repo.load("[완결] 나 혼자만 레벨업!"); assertEquals(3, calls.size)
    }
    @Test fun `all seed genres validate direct recommendations including a match outside the first two`() = runTest {
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { query, variables ->
            if (query == RelatedMangaRepository.RELATED_QUERY) related(listOf("Action", "Adventure", "Romance"), listOf(9 to media(2, listOf("Romance"))))
            else {
                assertEquals(3, variables["genres"]!!.jsonArray.size)
                page(media(3, listOf("Mystery")), media(4, listOf("Romance")))
            }
        }
        val state = repo.load("Base", 1) as RelatedMangaState.Ready
        assertEquals(setOf(2, 4), state.items.map { it.id }.toSet())
        assertTrue(state.items.all { it.commonGenres == setOf("romance") })
    }
    @Test fun `disjoint direct candidates trigger a single supplement and never leak into the genre list`() = runTest {
        var calls = 0
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { query, _ ->
            calls++
            if (query == RelatedMangaRepository.RELATED_QUERY) related(nodes = listOf(99 to media(2, listOf("Romance"))))
            else page(media(3, listOf("Mystery")))
        }
        assertTrue(repo.load("Base", 1) is RelatedMangaState.NoMatches); assertEquals(2, calls)
    }
    @Test fun `supplementary failure retains already verified results`() = runTest {
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { query, _ ->
            if (query == RelatedMangaRepository.RELATED_QUERY) related(nodes = listOf(5 to media(2)))
            else throw IOException("supplement failed")
        }
        assertEquals(listOf(2), (repo.load("Base", 1) as RelatedMangaState.Ready).items.map { it.id })
    }
    @Test fun `an external failure is never converted to local recommendations or cached`() = runTest {
        var calls = 0
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { _, _ -> calls++; """{"errors":[{"message":"Rate limited"}]}""" }
        repeat(2) {
            try { repo.load("Base", 1); fail<Unit>("Expected an API error") } catch (_: IOException) { /* Explicit retry is allowed. */ }
        }
        assertEquals(2, calls)
    }
    @Test fun `empty seed genres are explicit and do not trigger a genre query`() = runTest {
        var calls = 0
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { _, _ -> calls++; related(emptyList(), listOf(9 to media(2))) }
        assertEquals(RelatedMangaState.GenreUnavailable, repo.load("Base", 1)); assertEquals(1, calls)
    }
    @Test fun `missing candidate genres cannot be called the same genre`() = runTest {
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { query, _ -> if (query == RelatedMangaRepository.RELATED_QUERY) related(nodes = listOf(1 to media(2, emptyList()))) else page() }
        assertTrue(repo.load("Base", 1) is RelatedMangaState.NoMatches)
    }
    @Test fun `valid cache makes zero new calls and force refresh repeats only its bounded load`() = runTest {
        var calls = 0
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { _, _ -> calls++; valid() }
        repo.load("Base", 1); assertEquals(1, calls)
        repo.load("Base", 1); assertEquals(1, calls)
        repo.load("Base", 1, true); assertEquals(2, calls)
        repo.load("Different", 2); assertEquals(3, calls)
    }
    @Test fun `concurrent loads and simultaneous forced refresh join one request`() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { _, _ -> calls++; entered.complete(Unit); release.await(); valid() }
        val first = async { repo.load("Base", 1, true) }; entered.await()
        val second = async { repo.load("Base", 1, true) }; runCurrent()
        assertEquals(1, calls); release.complete(Unit)
        assertEquals(first.await(), second.await()); assertEquals(1, calls)
    }
    @Test fun `leaving the first screen keeps an identical active screen load alive`() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { _, _ -> calls++; entered.complete(Unit); release.await(); valid() }
        val first = async { repo.load("Base", 1) }; entered.await()
        val second = async { repo.load("Base", 1) }; runCurrent(); first.cancelAndJoin()
        release.complete(Unit); assertTrue(second.await() is RelatedMangaState.Ready); assertEquals(1, calls)
    }
    @Test fun `cancellation is propagated and an unfinished request is not cached`() = runTest {
        var calls = 0
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { _, _ -> calls++; if (calls == 1) awaitCancellation() else valid() }
        val job = async { repo.load("Base", 1) }; runCurrent(); job.cancelAndJoin()
        assertTrue(repo.load("Base", 1) is RelatedMangaState.Ready); assertEquals(2, calls)
    }
    @Test fun `disabling external recommendations blocks search related genre and cached results`() = runTest {
        var enabled = false; var calls = 0
        val repo = RelatedMangaRepository(enabled = { enabled }, requestScope = backgroundScope) { _, _ -> calls++; valid() }
        assertEquals(RelatedMangaState.Disabled, repo.load("Base")); assertEquals(0, calls)
        enabled = true; repo.load("Base", 1); assertEquals(1, calls)
        enabled = false; assertEquals(RelatedMangaState.Disabled, repo.load("Base", 1)); assertEquals(1, calls)
    }
    @Test fun `a late response after disabling is cancelled before exposing or caching it`() = runTest {
        var enabled = true; var calls = 0
        val release = CompletableDeferred<Unit>()
        val repo = RelatedMangaRepository(enabled = { enabled }, requestScope = backgroundScope) { _, _ -> calls++; release.await(); valid() }
        val job = async { repo.load("Base", 1) }; runCurrent(); enabled = false; release.complete(Unit)
        runCurrent(); assertTrue(job.isCancelled)
        enabled = true; assertTrue(repo.load("Base", 1) is RelatedMangaState.Ready); assertEquals(2, calls)
    }
    @Test fun `late supplementary cancellation does not expose direct results`() = runTest {
        var enabled = true
        val repo = RelatedMangaRepository(enabled = { enabled }, requestScope = backgroundScope) { query, _ ->
            if (query == RelatedMangaRepository.RELATED_QUERY) related(nodes = listOf(1 to media(2)))
            else { enabled = false; page(media(3)) }
        }
        val job = async { repo.load("Base", 1) }; runCurrent(); assertTrue(job.isCancelled)
    }
    @Test fun `result is capped at eighteen and candidate ID order settles equal scores`() = runTest {
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { _, _ -> related(nodes = (40 downTo 2).map { 1 to media(it) }) }
        assertEquals((2..19).toList(), (repo.load("Base", 1) as RelatedMangaState.Ready).items.map { it.id })
    }
    @Test fun `six hour expiry and forty eight entry cache bound are preserved`() = runTest {
        var now = 0L; var calls = 0
        val repo = RelatedMangaRepository(requestScope = backgroundScope, nowNanos = { now }) { query, vars ->
            calls++
            if (query == RelatedMangaRepository.SEARCH_QUERY) page(media(1, title = vars["search"]!!.jsonPrimitive.content)) else valid()
        }
        repeat(49) { repo.load("Work $it") }
        assertEquals(98, calls)
        repo.load("Work 48"); assertEquals(98, calls)
        repo.load("Work 0"); assertEquals(100, calls)
        now = java.util.concurrent.TimeUnit.HOURS.toNanos(6)
        repo.load("Work 48"); assertEquals(102, calls)
    }
    @Test fun `title normalization preserves sequel identifiers`() {
        assertEquals("abc2", RelatedMangaRepository.normalizeTitle("ＡＢＣ ２!"))
        assertNotEquals(RelatedMangaRepository.normalizeTitle("Title"), RelatedMangaRepository.normalizeTitle("Title 2"))
    }
    @Test fun `a Korean seed fills from Korean works first and skips the global query when enough`() = runTest {
        val calls = mutableListOf<String>()
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { query, vars ->
            calls += query
            when (query) {
                RelatedMangaRepository.RELATED_QUERY -> related(country = "KR")
                RelatedMangaRepository.GENRE_COUNTRY_QUERY -> {
                    assertEquals("KR", vars["country"]!!.jsonPrimitive.content)
                    page(*(10..15).map { media(it, country = "KR") }.toTypedArray())
                }
                else -> fail("The global query is not needed")
            }
        }
        val state = repo.load("Base", 1) as RelatedMangaState.Ready
        assertEquals((10..15).toList(), state.items.map { it.id })
        assertEquals(listOf(RelatedMangaRepository.RELATED_QUERY, RelatedMangaRepository.GENRE_COUNTRY_QUERY), calls)
    }
    @Test fun `a thin Korean list is topped up globally with Korean works ranked first`() = runTest {
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { query, _ ->
            when (query) {
                RelatedMangaRepository.RELATED_QUERY -> related(listOf("Action", "Fantasy"), listOf(9 to media(2, listOf("Action", "Fantasy"), country = "JP")), country = "KR")
                RelatedMangaRepository.GENRE_COUNTRY_QUERY -> page(media(3, country = "KR"))
                else -> page(media(4, listOf("Action", "Fantasy"), country = "JP"), media(3, country = "KR"))
            }
        }
        val state = repo.load("Base", 1) as RelatedMangaState.Ready
        // Korean work 3 shares fewer genres but stays ahead of the Japanese matches.
        assertEquals(listOf(3, 2, 4), state.items.map { it.id })
        assertEquals("KR", state.items.first().country)
    }
    @Test fun `a failed global top-up keeps the Korean fill`() = runTest {
        val repo = RelatedMangaRepository(requestScope = backgroundScope) { query, _ ->
            when (query) {
                RelatedMangaRepository.RELATED_QUERY -> related(country = "KR")
                RelatedMangaRepository.GENRE_COUNTRY_QUERY -> page(media(3, country = "KR"))
                else -> throw IOException("global failed")
            }
        }
        assertEquals(listOf(3), (repo.load("Base", 1) as RelatedMangaState.Ready).items.map { it.id })
    }
}
