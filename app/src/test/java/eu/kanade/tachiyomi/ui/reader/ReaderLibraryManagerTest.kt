package eu.kanade.tachiyomi.ui.reader

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.repository.MangaRepository
import java.io.IOException

class ReaderLibraryManagerTest {
    private val repository = mockk<MangaRepository>()
    private val categories = mockk<GetMangaCategories>()
    private val manga = mockk<Manga> {
        every { id } returns 42L
        every { title } returns "Test Manga"
        every { favorite } returns false
    }
    private val saved = mockk<Manga> { every { favorite } returns true }
    private val category = Category(7L, "읽는 중", 0L, 0L, false)

    private fun manager(default: Long = 0L): ReaderLibraryManager {
        coEvery { repository.getMangaById(42L) } returns manga
        coEvery { repository.getDuplicateLibraryManga(42L, "test manga") } returns emptyList()
        coEvery { categories.await() } returns listOf(category)
        coEvery { categories.await(42L) } returns emptyList()
        coEvery { repository.addMangaToLibrary(42L, any()) } returns saved
        return ReaderLibraryManager(repository, categories) { default }
    }

    @Test fun `any chapter saves its work by manga ID with automatic default category`() = runTest {
        val result = manager().add(42L) as ReaderLibraryManager.Result.Saved
        assertSame(saved, result.manga)
        coVerify(exactly = 1) { repository.addMangaToLibrary(42L, emptyList()) }
    }

    @Test fun `configured category is respected`() = runTest {
        manager(7L).add(42L)
        coVerify(exactly = 1) { repository.addMangaToLibrary(42L, listOf(7L)) }
    }

    @Test fun `ask each time and deleted default category wait for user without saving`() = runTest {
        for (default in listOf(-1L, 999L)) {
            val result = manager(default).add(42L) as ReaderLibraryManager.Result.ChooseCategory
            assertEquals(listOf(category), result.categories)
        }
        coVerify(exactly = 0) { repository.addMangaToLibrary(any(), any()) }
    }

    @Test fun `category chooser preserves prior selections`() = runTest {
        val manager = manager(-1L)
        coEvery { categories.await(42L) } returns listOf(category)
        assertEquals(listOf(7L), (manager.add(42L) as ReaderLibraryManager.Result.ChooseCategory).selected)
    }

    @Test fun `confirmed selection saves all chosen categories without asking again`() = runTest {
        manager(-1L).add(42L, listOf(7L), checkDuplicate = false)
        coVerify(exactly = 1) { repository.addMangaToLibrary(42L, listOf(7L)) }
    }

    @Test fun `no user categories falls back to uncategorized`() = runTest {
        val manager = manager(-1L)
        coEvery { categories.await() } returns listOf(Category(0L, "Default", 0L, 0L, false))
        assertTrue(manager.add(42L) is ReaderLibraryManager.Result.Saved)
        coVerify { repository.addMangaToLibrary(42L, emptyList()) }
    }

    @Test fun `existing favorite never changes categories added date or removes the work`() = runTest {
        val manager = manager()
        every { manga.favorite } returns true
        repeat(2) { assertSame(manga, (manager.add(42L) as ReaderLibraryManager.Result.Saved).manga) }
        coVerify(exactly = 0) { repository.addMangaToLibrary(any(), any()) }
        coVerify(exactly = 0) { repository.getDuplicateLibraryManga(any(), any()) }
    }

    @Test fun `duplicate title waits for confirmation and confirmation allows adding`() = runTest {
        val manager = manager()
        coEvery { repository.getDuplicateLibraryManga(42L, "test manga") } returns listOf(saved)
        assertSame(ReaderLibraryManager.Result.Duplicate, manager.add(42L))
        coVerify(exactly = 0) { repository.addMangaToLibrary(any(), any()) }
        assertTrue(manager.add(42L, checkDuplicate = false) is ReaderLibraryManager.Result.Saved)
    }

    @Test fun `persistence failure reports failure and a later retry can succeed`() = runTest {
        val manager = manager()
        coEvery { repository.addMangaToLibrary(42L, any()) } throws IOException("disk full")
        assertTrue(manager.add(42L) is ReaderLibraryManager.Result.Failed)
        coEvery { repository.addMangaToLibrary(42L, any()) } returns saved
        assertTrue(manager.add(42L) is ReaderLibraryManager.Result.Saved)
    }

    @Test fun `cancellation propagates instead of reporting a save failure`() = runTest {
        val manager = manager()
        coEvery { repository.addMangaToLibrary(42L, any()) } throws CancellationException()
        var cancelled = false
        try { manager.add(42L) } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
    }
}
