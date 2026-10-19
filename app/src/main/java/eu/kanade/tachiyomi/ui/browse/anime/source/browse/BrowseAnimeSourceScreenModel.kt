package eu.kanade.tachiyomi.ui.browse.anime.source.browse

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
import eu.kanade.domain.entries.anime.interactor.UpdateAnime
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.anime.interactor.AddAnimeTracks
import eu.kanade.presentation.util.ioCoroutineScope
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.data.cache.AnimeBackgroundCache
import eu.kanade.tachiyomi.data.cache.AnimeCoverCache
import eu.kanade.tachiyomi.util.removeBackgrounds
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
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.category.anime.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.toAnimeUpdate
import tachiyomi.domain.items.episode.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.anime.interactor.GetRemoteAnime
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import eu.kanade.presentation.browse.catalog.*
import eu.kanade.tachiyomi.data.personal.PersonalStore
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import eu.kanade.tachiyomi.animesource.model.AnimeFilter as AnimeSourceModelFilter

class BrowseAnimeSourceScreenModel(
    private val sourceId: Long,
    listingQuery: String?,
    sourceManager: AnimeSourceManager = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: AnimeCoverCache = Injekt.get(),
    private val backgroundCache: AnimeBackgroundCache = Injekt.get(),
    private val getRemoteAnime: GetRemoteAnime = Injekt.get(),
    private val getDuplicateAnimelibAnime: GetDuplicateLibraryAnime = Injekt.get(),
    private val getCategories: GetAnimeCategories = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val setAnimeDefaultEpisodeFlags: SetAnimeDefaultEpisodeFlags = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val updateAnime: UpdateAnime = Injekt.get(),
    private val addTracks: AddAnimeTracks = Injekt.get(),
    private val getIncognitoState: GetAnimeIncognitoState = Injekt.get(),
) : StateScreenModel<BrowseAnimeSourceScreenModel.State>(State(Listing.valueOf(listingQuery))) {

    var displayMode by sourcePreferences.sourceDisplayMode().asState(screenModelScope)

    val source = sourceManager.getOrStub(sourceId)

    private val catalogMutex = Mutex()
    var catalogRevision by mutableStateOf(0)
        private set
    var catalogTab by mutableStateOf(PersonalStore.number("catalog_tab:anime:$sourceId", if (listingQuery == GetRemoteAnime.QUERY_LATEST) 1 else 0).get())
        private set
    var catalogDay by mutableStateOf(PersonalStore.number("catalog_day:anime:$sourceId", CatalogPolicy.today()).get())
        private set
    val catalogSignature get() = PersonalStore.key(AnimeCatalogFilters.snapshot(state.value.filters) + (state.value.listing as? Listing.Search)?.query.orEmpty())
    private var catalogScope = kotlinx.coroutines.CoroutineScope(screenModelScope.coroutineContext + kotlinx.coroutines.SupervisorJob(screenModelScope.coroutineContext[kotlinx.coroutines.Job]))
    private var catalogContext: Pair<String, String>? = null
    private val catalogFlows = linkedMapOf<CatalogRequest, Flow<PagingData<StateFlow<Anime>>>>()
    val catalogCapability get() = AnimeCatalogFilters.capabilities(source.getFilterList(), source.supportsLatest)
    fun selectCatalogTab(tab: Int) { catalogTab = tab; PersonalStore.number("catalog_tab:anime:$sourceId").set(tab) }
    fun selectCatalogDay(day: Int) { catalogDay = day; PersonalStore.number("catalog_day:anime:$sourceId").set(day) }
    fun catalogItems(tab: CatalogTab, day: Int): Flow<PagingData<StateFlow<Anime>>> {
        val query = (state.value.listing as? Listing.Search)?.query.orEmpty()
        val snapshot = AnimeCatalogFilters.snapshot(state.value.filters)
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
                val fresh = AnimeCatalogFilters.apply(source.getFilterList(), request)
                val defaults = AnimeCatalogFilters.snapshot(source.getFilterList())
                val native = query.isBlank() && snapshot == defaults
                val remoteQuery = when {
                    tab == CatalogTab.POPULAR && native -> GetRemoteAnime.QUERY_POPULAR
                    tab == CatalogTab.LATEST && native -> GetRemoteAnime.QUERY_LATEST
                    else -> query
                }
                getRemoteAnime.subscribe(sourceId, remoteQuery, fresh)
            }.flow.map { data ->
                data.map { remote ->
                    val local = catalogMutex.withLock { networkToLocalAnime.await(remote.toDomainAnime(sourceId)) }
                    getAnime.subscribe(local.url, local.source).filterNotNull().stateIn(scope)
                }.filter { !hideInLibraryItems || !it.value.favorite }
            }.cachedIn(scope)
        }
    }

    init {
        mutableState.update {
            var query: String? = null
            var listing = it.listing

            if (listing is Listing.Search) {
                query = listing.query
                listing = Listing.Search(query, source.getFilterList())
            }

            it.copy(
                listing = listing,
                filters = AnimeCatalogFilters.restore(source.getFilterList(), PersonalStore.text("catalog_filters:anime:$sourceId").get()),
                toolbarQuery = query,
            )
        }

        if (!getIncognitoState.await(source.id)) {
            sourcePreferences.lastUsedAnimeSource().set(source.id)
        }
    }

    /**
     * Flow of Pager flow tied to [State.listing]
     */
    private val hideInLibraryItems = sourcePreferences.hideInAnimeLibraryItems().get()
    val animePagerFlowFlow = state.map { it.listing }
        .distinctUntilChanged()
        .map { listing ->
            Pager(PagingConfig(pageSize = 25)) {
                getRemoteAnime.subscribe(sourceId, listing.query ?: "", listing.filters)
            }.flow.map { pagingData ->
                pagingData.map {
                    networkToLocalAnime.await(it.toDomainAnime(sourceId))
                        .let { localAnime -> getAnime.subscribe(localAnime.url, localAnime.source) }
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
            libraryPreferences.animeLandscapeColumns()
        } else {
            libraryPreferences.animePortraitColumns()
        }.get()
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    // returns the number from the size slider
    fun getColumnsPreferenceForCurrentOrientation(orientation: Int): Int {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        return if (isLandscape) {
            libraryPreferences.animeLandscapeColumns()
        } else {
            libraryPreferences.animePortraitColumns()
        }.get()
    }

    fun resetFilters() {
        mutableState.update { it.copy(filters = source.getFilterList()) }
    }

    fun setListing(listing: Listing) {
        catalogRevision++
        mutableState.update { it.copy(listing = listing, toolbarQuery = null) }
    }

    fun setFilters(filters: AnimeFilterList) {
        mutableState.update {
            it.copy(
                filters = filters,
            )
        }
    }

    fun search(query: String? = null, filters: AnimeFilterList? = null) {
        catalogRevision++
        if (filters != null) PersonalStore.text("catalog_filters:anime:$sourceId").set(AnimeCatalogFilters.snapshot(filters))
        val input = state.value.listing as? Listing.Search
            ?: Listing.Search(query = null, filters = source.getFilterList())

        mutableState.update {
            it.copy(
                listing = input.copy(
                    query = query ?: input.query,
                    filters = filters ?: input.filters,
                ),
                toolbarQuery = query ?: input.query,
            )
        }
    }

    fun searchGenre(genreName: String) {
        catalogRevision++
        val defaultFilters = source.getFilterList()
        var genreExists = false

        filter@ for (sourceFilter in defaultFilters) {
            if (sourceFilter is AnimeSourceModelFilter.Group<*>) {
                for (filter in sourceFilter.state) {
                    if (filter is AnimeSourceModelFilter<*> && filter.name.equals(genreName, true)) {
                        when (filter) {
                            is AnimeSourceModelFilter.TriState -> filter.state = 1
                            is AnimeSourceModelFilter.CheckBox -> filter.state = true
                            else -> {}
                        }
                        genreExists = true
                        break@filter
                    }
                }
            } else if (sourceFilter is AnimeSourceModelFilter.Select<*>) {
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
                listing = listing,
                toolbarQuery = listing.query,
            )
        }
    }

    /**
     * Adds or removes an anime from the library.
     *
     * @param anime the anime to update.
     */
    fun changeAnimeFavorite(anime: Anime) {
        screenModelScope.launch {
            var new = anime.copy(
                favorite = !anime.favorite,
                dateAdded = when (anime.favorite) {
                    true -> 0
                    false -> Instant.now().toEpochMilli()
                },
            )

            if (!new.favorite) {
                new = new.removeCovers(coverCache)
                new = new.removeBackgrounds(backgroundCache)
            } else {
                setAnimeDefaultEpisodeFlags.await(anime)
                addTracks.bindEnhancedTrackers(anime, source)
            }

            updateAnime.await(new.toAnimeUpdate())
        }
    }

    fun addFavorite(anime: Anime) {
        screenModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultAnimeCategory().get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    moveAnimeToCategories(anime, defaultCategory)

                    changeAnimeFavorite(anime)
                }
                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    moveAnimeToCategories(anime)

                    changeAnimeFavorite(anime)
                }

                // Choose a category
                else -> {
                    val preselectedIds = getCategories.await(anime.id).map { it.id }
                    setDialog(
                        Dialog.ChangeAnimeCategory(
                            anime,
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

    suspend fun getDuplicateAnimelibAnime(anime: Anime): Anime? {
        return getDuplicateAnimelibAnime.await(anime).getOrNull(0)
    }

    private fun moveAnimeToCategories(anime: Anime, vararg categories: Category) {
        moveAnimeToCategories(anime, categories.filter { it.id != 0L }.map { it.id })
    }

    fun moveAnimeToCategories(anime: Anime, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setAnimeCategories.await(
                animeId = anime.id,
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
        mutableState.update { it.copy(toolbarQuery = query) }
    }

    sealed class Listing(open val query: String?, open val filters: AnimeFilterList) {
        data object Popular : Listing(
            query = GetRemoteAnime.QUERY_POPULAR,
            filters = AnimeFilterList(),
        )
        data object Latest : Listing(
            query = GetRemoteAnime.QUERY_LATEST,
            filters = AnimeFilterList(),
        )
        data class Search(override val query: String?, override val filters: AnimeFilterList) : Listing(
            query = query,
            filters = filters,
        )

        companion object {
            fun valueOf(query: String?): Listing {
                return when (query) {
                    GetRemoteAnime.QUERY_POPULAR -> Popular
                    GetRemoteAnime.QUERY_LATEST -> Latest
                    else -> Search(query = query, filters = AnimeFilterList()) // filters are filled in later
                }
            }
        }
    }

    sealed interface Dialog {
        data object Filter : Dialog
        data class RemoveAnime(val anime: Anime) : Dialog
        data class AddDuplicateAnime(val anime: Anime, val duplicate: Anime) : Dialog
        data class ChangeAnimeCategory(
            val anime: Anime,
            val initialSelection: ImmutableList<CheckboxState.State<Category>>,
        ) : Dialog
        data class Migrate(val newAnime: Anime, val oldAnime: Anime) : Dialog
    }

    @Immutable
    data class State(
        val listing: Listing,
        val filters: AnimeFilterList = AnimeFilterList(),
        val toolbarQuery: String? = null,
        val dialog: Dialog? = null,
    ) {
        val isUserQuery get() = listing is Listing.Search && !listing.query.isNullOrEmpty()
    }
}
