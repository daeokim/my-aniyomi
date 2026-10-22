package eu.kanade.presentation.browse.catalog

import eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch.MangaSearchItemResult
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.AnimeSearchItemResult
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.anime.model.Anime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GlobalSearchCompletionTest {
    @Test fun `one preview result is not treated as the entire search if another page exists`() {
        val manga = Manga.create(); val anime = Anime.create()
        assertNull(MangaSearchItemResult.Success(listOf(manga), true).singleCompleteResult)
        assertNull(AnimeSearchItemResult.Success(listOf(anime), true).singleCompleteResult)
        assertEquals(manga, MangaSearchItemResult.Success(listOf(manga), false).singleCompleteResult)
        assertEquals(anime, AnimeSearchItemResult.Success(listOf(anime), false).singleCompleteResult)
        assertNull(MangaSearchItemResult.Success(listOf(manga, manga), false).singleCompleteResult)
        assertNull(AnimeSearchItemResult.Success(emptyList(), false).singleCompleteResult)
        assertTrue(MangaSearchItemResult.Success(emptyList(), true).isVisible(true))
        assertTrue(AnimeSearchItemResult.Success(emptyList(), true).isVisible(true))
    }
}
