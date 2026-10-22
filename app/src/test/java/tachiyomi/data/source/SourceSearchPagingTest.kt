package tachiyomi.data.source

import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import tachiyomi.data.source.manga.SourceSearchPagingSource
import tachiyomi.data.source.anime.AnimeSourceSearchPagingSource
import java.io.IOException

class SourceSearchPagingTest {
    private fun manga(page: Int) = SManga.create().apply { url = "/$page"; title = "result $page" }
    private fun anime(page: Int) = SAnime.create().apply { url = "/$page"; title = "result $page" }
    private suspend fun <T:Any> all(paging: PagingSource<Long,T>): List<T> {
        val result = mutableListOf<T>(); var key: Long? = null
        do {
            val params: PagingSource.LoadParams<Long> = key?.let { PagingSource.LoadParams.Append(it, 25, false) } ?: PagingSource.LoadParams.Refresh(null, 25, false)
            val page = paging.load(params) as PagingSource.LoadResult.Page<Long,T>
            result += page.data; key = page.nextKey
        } while (key != null)
        return result
    }
    @Test fun `manga and anime search follow every source page without a fixed result cap`() = runTest {
        val mangaCalls = mutableListOf<Int>(); val animeCalls = mutableListOf<Int>()
        val mangaSource = mockk<CatalogueSource>(); val animeSource = mockk<AnimeSource>()
        coEvery { mangaSource.getSearchManga(any(), "query", any()) } answers {
            val p = firstArg<Int>(); mangaCalls += p; MangasPage((1..25).map { manga((p-1)*25+it) }, p < 4)
        }
        coEvery { animeSource.getSearchAnime(any(), "query", any()) } answers {
            val p = firstArg<Int>(); animeCalls += p; AnimesPage((1..25).map { anime((p-1)*25+it) }, p < 4)
        }
        val mangas = all(SourceSearchPagingSource(mangaSource,"query",FilterList()))
        val animes = all(AnimeSourceSearchPagingSource(animeSource,"query",AnimeFilterList()))
        assertEquals((1..100).map { "/$it" }, mangas.map { it.url }); assertEquals((1..100).map { "/$it" }, animes.map { it.url })
        assertEquals(listOf(1,2,3,4),mangaCalls); assertEquals(listOf(1,2,3,4),animeCalls)
    }
    @Test fun `empty search is a successful empty result and an empty intermediate page preserves the next page`() = runTest {
        val m = mockk<CatalogueSource>(); val a = mockk<AnimeSource>()
        coEvery { m.getSearchManga(any(), any(), any()) } answers { val p=firstArg<Int>(); MangasPage(if(p==2) listOf(manga(2)) else emptyList(),p==1) }
        coEvery { a.getSearchAnime(any(), any(), any()) } returns AnimesPage(emptyList(),false)
        assertEquals(listOf("/2"),all(SourceSearchPagingSource(m,"q",FilterList())).map { it.url })
        assertTrue(all(AnimeSourceSearchPagingSource(a,"q",AnimeFilterList())).isEmpty())
    }
    @Test fun `refresh starts from the first page instead of losing earlier search matches`() = runTest {
        val m=SourceSearchPagingSource(mockk(),"q",FilterList()); val a=AnimeSourceSearchPagingSource(mockk(),"q",AnimeFilterList())
        assertNull(m.getRefreshKey(PagingState(listOf(PagingSource.LoadResult.Page(listOf(manga(1)),null,2L)),0,PagingConfig(25),0)))
        assertNull(a.getRefreshKey(PagingState(listOf(PagingSource.LoadResult.Page(listOf(anime(1)),null,2L)),0,PagingConfig(25),0)))
    }
    @Test fun `cancellation propagates while real source errors remain retryable load errors`() = runTest {
        val m = mockk<CatalogueSource>(); val a = mockk<AnimeSource>()
        coEvery { m.getSearchManga(any(),any(),any()) } throws CancellationException("query changed")
        coEvery { a.getSearchAnime(any(),any(),any()) } throws IOException("offline")
        try { SourceSearchPagingSource(m,"q",FilterList()).load(PagingSource.LoadParams.Refresh(null,25,false)); fail<Unit>("Cancellation was swallowed") }
        catch (_: CancellationException) { }
        assertTrue(AnimeSourceSearchPagingSource(a,"q",AnimeFilterList()).load(PagingSource.LoadParams.Refresh(null,25,false)) is PagingSource.LoadResult.Error)
    }
}
