package eu.kanade.tachiyomi.ui.reader

import kotlinx.coroutines.CancellationException
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.repository.MangaRepository

/** Saves the work, independently of the reader's current chapter, page, or viewer. */
class ReaderLibraryManager(
    private val repository: MangaRepository,
    private val getCategories: GetMangaCategories,
    private val defaultCategory: () -> Long,
) {
    suspend fun add(
        mangaId: Long,
        categoryIds: List<Long>? = null,
        checkDuplicate: Boolean = true,
    ): Result {
        return try {
            val manga = repository.getMangaById(mangaId)
            if (manga.favorite) return Result.Saved(manga)
            if (checkDuplicate && repository.getDuplicateLibraryManga(manga.id, manga.title.lowercase()).isNotEmpty()) {
                return Result.Duplicate
            }
            val categories = getCategories.await().filterNot { it.isSystemCategory }
            val defaultId = defaultCategory()
            val selected = categoryIds ?: when {
                categories.any { it.id == defaultId } -> listOf(defaultId)
                defaultId == 0L || categories.isEmpty() -> emptyList()
                else -> return Result.ChooseCategory(
                    categories,
                    getCategories.await(mangaId).map { it.id },
                )
            }
            // Category membership and favorite status are persisted in one transaction.
            Result.Saved(repository.addMangaToLibrary(mangaId, selected))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Failed(e)
        }
    }

    sealed interface Result {
        data class Saved(val manga: Manga) : Result
        data object Duplicate : Result
        data class ChooseCategory(val categories: List<Category>, val selected: List<Long>) : Result
        data class Failed(val error: Exception) : Result
    }
}
