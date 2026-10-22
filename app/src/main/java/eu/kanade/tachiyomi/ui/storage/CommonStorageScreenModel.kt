package eu.kanade.tachiyomi.ui.storage

import androidx.compose.ui.graphics.Color
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.more.storage.StorageItem
import eu.kanade.presentation.more.storage.StorageScreenState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random

abstract class CommonStorageScreenModel<T>(
    private val downloadCacheChanges: SharedFlow<Unit>,
    private val downloadCacheIsInitializing: StateFlow<Boolean>,
    private val libraries: Flow<List<T>>,
    private val categories: (Boolean) -> Flow<List<Category>>,
    private val getDownloadSize: T.() -> Long,
    private val getDownloadCount: T.() -> Int,
    private val getId: T.() -> Long,
    private val getCategoryId: T.() -> Long,
    private val getTitle: T.() -> String,
    private val getThumbnail: T.() -> String?,
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) : StateScreenModel<StorageScreenState>(StorageScreenState.Loading) {

    private val selectedCategory = MutableStateFlow(AllCategory)

    init {
        screenModelScope.launchIO {
            val hideHiddenCategories = libraryPreferences.hideHiddenCategoriesSettings().get()

            combine(
                flow = downloadCacheChanges,
                flow2 = downloadCacheIsInitializing,
                flow3 = libraries,
                flow4 = categories(hideHiddenCategories),
                flow5 = selectedCategory,
                transform = { _, _, libraries, categories, selectedCategory ->
                    Triple(libraries, categories, selectedCategory)
                },
            ).collectLatest { (libraries, categories, category) ->
                fun publish(items: List<StorageItem>) {
                    // A new selection can arrive before combine emits its next value.
                    if (selectedCategory.value != category) return
                    mutableState.value = StorageScreenState.Success(
                        selectedCategory = category,
                        categories = listOf(AllCategory, *categories.toTypedArray()),
                        items = items,
                    )
                }
                publish(emptyList())
                val visibleIds = categories.map { it.id }.toSet()
                val selected = libraries.distinctBy { it.getId() }.filter {
                    if (category == AllCategory) it.getCategoryId() in visibleIds else it.getCategoryId() == category.id
                }
                val items = StorageCalculation.calculate(selected, sizeOf = { it.getDownloadSize() }) { library, size ->
                    val random = Random(library.getId())
                    StorageItem(
                        id = library.getId(), title = library.getTitle(), size = size,
                        thumbnail = library.getThumbnail(), entriesCount = library.getDownloadCount(),
                        color = Color(random.nextInt(255), random.nextInt(255), random.nextInt(255)),
                    )
                }
                currentCoroutineContext().ensureActive()
                publish(items)
            }
        }
    }

    fun setSelectedCategory(category: Category) {
        selectedCategory.update { category }
    }

    abstract fun deleteEntry(id: Long)

    companion object {
        /**
         * A dummy category used to display all entries irrespective of the category.
         */
        private val AllCategory = Category(
            id = -1L,
            name = "All",
            order = 0L,
            flags = 0L,
            hidden = false,
        )
    }
}
