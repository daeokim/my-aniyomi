package eu.kanade.tachiyomi.data.recommendations

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GenreMatchingTest {
    private fun card(id: Int, vararg genre: String, score: Int = 0, source: String = "direct") = RelatedManga(id, "$id", null, emptyList(), genres = GenreMatching.normalize(genre.toList()), score = score, scoreSource = source)
    @Test fun `verified Korean and English terms normalize without changing source metadata`() {
        val source = listOf(" 액션, 판타지 ", "S c i - F i", "Slice of Life")
        assertEquals(setOf("action", "fantasy", "scifi", "sliceoflife"), GenreMatching.normalize(source))
        assertEquals(" 액션, 판타지 ", source.first())
        assertFalse("romance" in GenreMatching.normalize(listOf("순정")))
        assertFalse("action" in GenreMatching.normalize(listOf("무협")))
    }
    @Test fun `unknown tags remain separate and unverified compound delimiters are not split`() {
        val normalized = GenreMatching.normalize(listOf("Action/Romance", "Action · Fantasy", "무협", "A-B"))
        assertFalse("action" in normalized); assertFalse("romance" in normalized)
        assertTrue("a-b" in normalized); assertFalse("ab" in normalized)
    }
    @Test fun `intersection comes first then Jaccard then comparable rating and stable id`() {
        val seed = GenreMatching.normalize(listOf("액션", "판타지", "로맨스"))
        val candidates = listOf(card(1, "Action", score = 999), card(2, "Action", "Fantasy", "Drama", "Sports"),
            card(3, "Action", "Fantasy"), card(4, "Action", "Fantasy", score = 8), card(5, "Action", "Fantasy", score = 8),
            card(6, "Romance", "Mystery"), card(7, "Mystery", score = 999))
        assertEquals(listOf(4, 5, 3, 2, 1, 6), GenreMatching.rank(seed, candidates).map { it.id })
    }
    @Test fun `library author score without a common genre cannot admit a work`() {
        val result = GenreMatching.rank(setOf("action"), listOf(card(9, "Romance", score = 10, source = "library"), card(2, "액션", source = "library")))
        assertEquals(listOf(2), result.map { it.id })
        assertTrue(GenreMatching.rank(emptySet(), listOf(card(3, "Action"))).isEmpty())
    }
    @Test fun `mixed incomparable scores have deterministic total order independent of input permutation`() {
        val cards = listOf(card(10, "Action", score = 1), card(20, "Action", score = 8), card(15, "Action", score = 99999, source = "genre"))
        val order = GenreMatching.rank(setOf("action"), cards).map { it.id }
        assertEquals(listOf(15, 20, 10), order)
        repeat(100) { assertEquals(order, GenreMatching.rank(setOf("action"), cards.shuffled(kotlin.random.Random(it))).map { it.id }) }
    }
    @Test fun `a known seed country puts same-country works ahead of better genre overlap`() {
        val kr = card(1, "Action").copy(country = "KR")
        val jp = card(2, "Action", "Fantasy").copy(country = "JP")
        assertEquals(listOf(1, 2), GenreMatching.rank(setOf("action", "fantasy"), listOf(jp, kr), "KR").map { it.id })
        assertEquals(listOf(2, 1), GenreMatching.rank(setOf("action", "fantasy"), listOf(jp, kr)).map { it.id })
    }
    @Test fun `a shared theme tag outranks broader genre overlap`() {
        val themed = card(1, "Action").copy(commonTags = setOf("martial arts"))
        val broad = card(2, "Action", "Fantasy")
        assertEquals(listOf(1, 2), GenreMatching.rank(setOf("action", "fantasy"), listOf(broad, themed)).map { it.id })
    }
}
