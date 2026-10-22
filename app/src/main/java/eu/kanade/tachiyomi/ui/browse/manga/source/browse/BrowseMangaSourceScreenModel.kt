package eu.kanade.tachiyomi.ui.browse.manga.source.browse

// Personal fork: Fold8 replacement app, adaptive catalog and two-page reader changes.

import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.map
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.asState
import eu.kanade.domain.entries.manga.interactor.UpdateManga
import eu.kanade.domain.entries.manga.model.toDomainManga
import eu.kanade.domain.source.manga.interactor.GetMangaIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.manga.interactor.AddMangaTracks
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.data.cache.MangaCoverCache
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.category.manga.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.entries.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.model.toMangaUpdate
import tachiyomi.domain.items.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.manga.interactor.GetRemoteManga
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import eu.kanade.presentation.browse.catalog.*
import eu.kanade.tachiyomi.data.personal.PersonalStore
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import eu.kanade.tachiyomi.source.model.Filter as SourceModelFilter

class BrowseMangaSourceScreenModel(
    private val sourceId: Long,
    listingQuery: String?,
    sourceManager: MangaSourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: MangaCoverCache = Injekt.get(),
    private val getRemoteManga: GetRemoteManga = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getCategories: GetMangaCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val setMangaDefaultChapterFlags: SetMangaDefaultChapterFlags = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val addTracks: AddMangaTracks = Injekt.get(),
    private val getIncognitoState: GetMangaIncognitoState = Injekt.get(),
) : StateScreenModel<BrowseMangaSourceScreenModel.State>(State(Listing.valueOf(listingQuery))) {

    var displayMode by sourcePreferences.sourceDisplayMode().asState(screenModelScope)

    val source = sourceManager.getOrStub(sourceId)

    private val catalogMutex = Mutex()
    var catalogRevision by mutableStateOf(0)
        private set
    var catalogTab by mutableStateOf(PersonalStore.number("catalog_tab:manga:$sourceId", if (listingQuery == GetRemoteManga.QUERY_LATEST) 1 else 0).get())
        private set
    var catalogDay by mutableStateOf(PersonalStore.number("catalog_day:manga:$sourceId", CatalogPolicy.today()).get())
        private set
    val catalogSearchQuery get() = (state.value.listing as? Listing.Search)?.query.orEmpty()
    val catalogSignature get() = PersonalStore.key(state.value.catalogFiltersSnapshot + (state.value.listing as? Listing.Search)?.query.orEmpty())
    private var catalogScope = kotlinx.coroutines.CoroutineScope(screenModelScope.coroutineContext + kotlinx.coroutines.SupervisorJob(screenModelScope.coroutineContext[kotlinx.coroutines.Job]))
    private var catalogContext: Pair<String, String>? = null
    private val catalogFlows = linkedMapOf<CatalogRequest, Flow<PagingData<StateFlow<Manga>>>>()
    val catalogCapability get() = MangaCatalogFilters.capabilities((source as? CatalogueSource)?.getFilterList() ?: FilterList(), (source as? CatalogueSource)?.supportsLatest == true)
    fun catalogSortAllowed(tab: CatalogTab): Boolean {
        val filters = (source as? CatalogueSource)?.getFilterList() ?: return true
        val native = (state.value.listing as? Listing.Search)?.query.isNullOrBlank() &&
            state.value.catalogFiltersSnapshot == MangaCatalogFilters.snapshot(filters)
        return CatalogPolicy.sortCombinationAllowed(tab, native, MangaCatalogFilters.supportsSort(filters, tab))
    }
    fun selectCatalogTab(tab: Int) { catalogTab = tab; PersonalStore.number("catalog_tab:manga:$sourceId").set(tab) }
    fun selectCatalogDay(day: Int) { catalogDay = day; PersonalStore.number("catalog_day:manga:$sourceId").set(day) }
    fun catalogItems(tab: CatalogTab, day: Int): Flow<PagingData<StateFlow<Manga>>> {
        val query = (state.value.listing as? Listing.Search)?.query.orEmpty()
        val snapshot = state.value.catalogFiltersSnapshot
        val request = CatalogRequest(tab, if (tab == CatalogTab.WEEKDAY) day else 0, snapshot, query)
        val context = snapshot to query
        if (catalogContext != context) {
            catalogScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            catalogScope = kotlinx.coroutines.CoroutineScope(screenModelScope.coroutineContext + kotlinx.coroutines.SupervisorJob(screenModelScope.coroutineContext[kotlinx.coroutines.Job]))
            catalogFlows.clear()
            catalogContext = context
        }
        val scope = catalogScope
        return catalogFlows.getOrPut(request) {
            Pager(PagingConfig(pageSize = 25, enablePlaceholders = false)) {
                val fresh = MangaCatalogFilters.apply((source as CatalogueSource).getFilterList(), request)
                val defaults = MangaCatalogFilters.snapshot((source as CatalogueSource).getFilterList())
                val native = query.isBlank() && snapshot == defaults
                val remoteQuery = when {
                    tab == CatalogTab.POPULAR && native -> GetRemoteManga.QUERY_POPULAR
                    tab == CatalogTab.LATEST && native -> GetRemoteManga.QUERY_LATEST
                    else -> query
                }
                getRemoteManga.subscribe(sourceId, remoteQuery, fresh)
            }.flow.map { data ->
                data.map { remote ->
                    val local = catalogMutex.withLock { networkToLocalManga.await(remote.toDomainManga(sourceId)) }
                    getManga.subscribe(local.url, local.source).filterNotNull().stateIn(scope)
                }.filter { query.isNotBlank() || !hideInLibraryItems || !it.value.favorite }
            }.cachedIn(scope)
        }
    }

    init {
        if (source is CatalogueSource) {
            mutableState.update {
                var query: String? = null
                var listing = it.listing

                if (listing is Listing.Search) {
                    query = listing.query
                    listing = Listing.Search(query, source.getFilterList())
                }

                val restoredFilters = MangaCatalogFilters.restore(
                    source.getFilterList(),
                    PersonalStore.text("catalog_filters:manga:$sourceId").get(),
                )
                it.copy(
                    listing = listing,
                    filters = restoredFilters,
                    catalogFiltersSnapshot = MangaCatalogFilters.snapshot(restoredFilters),
                    toolbarQuery = query,
                )
            }
        }

        if (!getIncognitoState.await(source.id)) {
            sourcePreferences.lastUsedMangaSource().set(source.id)
        }
    }

    /**
     * Flow of Pager flow tied to [State.listing]
     */
    private val hideInLibraryItems = sourcePreferences.hideInMangaLibraryItems().get()
    val mangaPagerFlowFlow = state.map { it.listing }
        .distinctUntilChanged()
        .map { listing ->
            Pager(PagingConfig(pageSize = 25)) {
                getRemoteManga.subscribe(sourceId, listing.query ?: "", listing.filters)
            }.flow.map { pagingData ->
                pagingData.map {
                    networkToLocalManga.await(it.toDomainManga(sourceId))
                        .let { localManga -> getManga.subscribe(localManga.url, localManga.source) }
                        .filterNotNull()
                        .stateIn(ioCoroutineScope)
                }
                    .filter { !hideInLibraryItems || !it.value.favorite }
            }
                .cachedIn(ioCoroutineScope)
        }
        .stateIn(ioCoroutineScope, SharingStarted.Lazily, emptyFlow())

    fun getColumnsPreference(orientation: Int): GridCells {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isLandscape) {
            libraryPreferences.mangaLandscapeColumns()
        } else {
            libraryPreferences.mangaPortraitColumns()
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    // returns the number from the size slider
    fun getColumnsPreferenceForCurrentOrientation(orientation: Int): Int {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (isLandscape) {
            libraryPreferences.mangaLandscapeColumns()
        } else {
            libraryPreferences.mangaPortraitColumns()
        }.get()
    }

    fun resetFilters() {
        if (source !is CatalogueSource) return

        mutableState.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        catalogRevision++
        mutableState.update { it.copy(listing = listing, toolbarQuery = null) }
    }

    fun setFilters(filters: FilterList) {
        if (source !is CatalogueSource) return

        mutableState.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: FilterList? = null) {
        catalogRevision++
        if (source !is CatalogueSource) return

        if (filters != null) PersonalStore.text("catalog_filters:manga:$sourceId").set(MangaCatalogFilters.snapshot(filters))
        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = source.getFilterList())

        mutableState.update {
            it.copy(
                listing = input.copy(
                    query = query ?: input.query,
                    filters = filters ?: input.filters,
                ),
                toolbarQuery = query ?: input.query,
                catalogFiltersSnapshot = filters?.let(MangaCatalogFilters::snapshot) ?: it.catalogFiltersSnapshot,
            )
        }
    }

    fun searchGenre(genreName: String) {
        catalogRevision++
        if (source !is CatalogueSource) return

        val defaultFilters = source.getFilterList()
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is SourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is SourceModelFilter<*> && filter.name.equals(genreName, true)) {
                        when (filter) {
                            is SourceModelFilter.TriState -> filter.state = 1
                            is SourceModelFilter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is SourceModelFilter.Select<*>) {
                val index = sourceFilter.values.filterIsInstance<String>()
                    .indexOfFirst { it.equals(genreName, true) }

                if (index != -1) {
                    sourceFilter.state = index
                    genreExists = true
                    break
                }
            }
        }

        mutableState.update {
            val listing = if (genreExists) {
                Listing.Search(query = null, filters = defaultFilters)
            } else {
                Listing.Search(query = genreName, filters = defaultFilters)
            }
            it.copy(
                filters = defaultFilters,
                catalogFiltersSnapshot = MangaCatalogFilters.snapshot(defaultFilters),
                listing = listing,
                toolbarQuery = listing.query,
            )
        }
    }

    /**
     * Adds or removes a manga from the library.
     *
     * @param manga the manga to update.
     */
    fun changeMangaFavorite(manga: Manga) {
        screenModelScope.launch {
            var new = manga.copy(
                favorite = !manga.favorite,
                dateAdded = when (manga.favorite) {
                    true -> 0
                    false -> Instant.now().toEpochMilli()
                },
            )

            if (!new.favorite) {
                new = new.removeCovers(coverCache)
            } else {
                setMangaDefaultChapterFlags.await(manga)
                addTracks.bindEnhancedTrackers(manga, source)
            }

            updateManga.await(new.toMangaUpdate())
        }
    }

    fun addFavorite(manga: Manga) {
        screenModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultMangaCategory().get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    moveMangaToCategories(manga, defaultCategory)

                    changeMangaFavorite(manga)
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    moveMangaToCategories(manga)

                    changeMangaFavorite(manga)
                }

                // Choose a category
                else -> {
                    val preselectedIds = getCategories.await(manga.id).map { it.id }
                    setDialog(
                        Dialog.ChangeMangaCategory(
                            manga,
                            categories.mapAsCheckboxState { it.id in preselectedIds }.toImmutableList(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<Category> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    suspend fun getDuplicateLibraryManga(manga: Manga): Manga? {
        return getDuplicateLibraryManga.await(manga).getOrNull(0)
    }

    private fun moveMangaToCategories(manga: Manga, vararg categories: Category) {
        moveMangaToCategories(manga, categories.filter { it.id != 0L }.map { it.id })
    }

    fun moveMangaToCategories(manga: Manga, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setMangaCategories.await(
                mangaId = manga.id,
                categoryIds = categoryIds.toList(),
            )
        }
    }

    fun openFilterSheet() {
        setDialog(Dialog.Filter)
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    fun setToolbarQuery(query: String?) {
        mutableState.update { it.withToolbarQuery(query) }
    }

    sealed class Listing(open val query: String?, open val filters: FilterList) {
        data object Popular : Listing(query = GetRemoteManga.QUERY_POPULAR, filters = FilterList())
        data object Latest : Listing(query = GetRemoteManga.QUERY_LATEST, filters = FilterList())
        data class Search(override val query: String?, override val filters: FilterList) : Listing(
            query = query,
            filters = filters,
        )

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteManga.QUERY_POPULAR -> Popular
                    GetRemoteManga.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = FilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog
        data class RemoveManga(val manga: Manga) : Dialog
        data class AddDuplicateManga(val manga: Manga, val duplicate: Manga) : Dialog
        data class ChangeMangaCategory(
            val manga: Manga,
            val initialSelection: ImmutableList<CheckboxState.State<Category>>,
        ) : Dialog
        data class Migrate(val newManga: Manga, val oldManga: Manga) : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val filters: FilterList = FilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
        // Immutable applied values keep filter-sheet edits out of active paging requests.
        val catalogFiltersSnapshot: String = "",
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()

        fun withToolbarQuery(query: String?): State {
            // Clearing or closing search must also clear the submitted catalog query.
            val nextListing = if (query.isNullOrEmpty() && listing is Listing.Search && listing.query != null) {
                listing.copy(query = null)
            } else {
                listing
            }
            // FilterList deliberately disables structural equality. Keep repeated UI events
            // idempotent instead of emitting a new state and rebuilding an unchanged listing.
            return if (nextListing === listing && toolbarQuery == query) {
                this
            } else {
                copy(listing = nextListing, toolbarQuery = query)
            }
        }
    }
}
