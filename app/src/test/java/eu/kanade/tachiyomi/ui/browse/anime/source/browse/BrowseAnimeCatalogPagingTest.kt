package eu.kanade.tachiyomi.ui.browse.anime.source.browse

import android.util.Log
import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.model.ScreenModelStore
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.browse.catalog.CatalogTab
import eu.kanade.tachiyomi.data.personal.PersonalStore
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.source.anime.interactor.GetRemoteAnime
import tachiyomi.domain.source.anime.repository.AnimeSourceRepository
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import java.util.UUID

/** Exercises the production model's Pager/cache scopes, with a local source and no HTTP/DB. */
@OptIn(ExperimentalCoroutinesApi::class, InternalVoyagerApi::class)
class BrowseAnimeCatalogPagingTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun installNavigatorPreference() {
            // Navigator.kt initializes its UI preference when ioCoroutineScope is first used.
            Injekt.addSingleton(UiPreferences(mockk()))
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val modelKeys = mutableMapOf<BrowseAnimeSourceScreenModel, String>()
    private val slowStarted = CompletableDeferred<Unit>()
    private val slowCancelled = CompletableDeferred<Unit>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.isLoggable(any(), any()) } returns false
        mockkObject(PersonalStore)
        every { PersonalStore.number(any(), any()) } answers { preference(secondArg<Int>()) }
        every { PersonalStore.text(any(), any()) } answers { preference(secondArg<String>()) }
    }

    @AfterEach
    fun tearDown() {
        modelKeys.values.forEach(ScreenModelStore::onDisposeNavigator)
        unmockkObject(PersonalStore)
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    private fun <T> preference(value: T): Preference<T> = mockk<Preference<T>>(relaxed = true).also {
        every { it.get() } returns value
        every { it.changes() } returns flowOf(value)
    }

    private fun filters() = AnimeFilterList(
        object : AnimeFilter.Select<String>("요일", arrayOf("전체", "월", "화", "수", "목", "금", "토", "일")) {},
        object : AnimeFilter.Select<String>("상태", arrayOf("전체", "연재", "완결")) {},
    )

    private fun page(title: String) = object : PagingSource<Long, SAnime>() {
        override fun getRefreshKey(state: PagingState<Long, SAnime>): Long? = null

        override suspend fun load(params: LoadParams<Long>): LoadResult<Long, SAnime> {
            if (title == "slow") {
                slowStarted.complete(Unit)
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    slowCancelled.complete(Unit)
                }
            }
            val entries = if (title == "empty") emptyList() else listOf(
                SAnime.create().apply { this.title = title; url = title },
            )
            return LoadResult.Page(entries, prevKey = null, nextKey = null)
        }
    }

    private fun model(hideInLibrary: Boolean = false, favorite: Boolean = false): BrowseAnimeSourceScreenModel {
        val source = mockk<AnimeSource> {
            every { id } returns 1L
            every { getFilterList() } answers { filters() }
        }
        val manager = mockk<AnimeSourceManager> { every { getOrStub(1L) } returns source }
        val preferences = mockk<SourcePreferences> {
            every { sourceDisplayMode() } returns preference(LibraryDisplayMode.default)
            every { hideInAnimeLibraryItems() } returns preference(hideInLibrary)
        }
        val repository = mockk<AnimeSourceRepository> {
            every { getPopularAnime(1L) } answers { page("popular") }
            every { getLatestAnime(1L) } answers { page("latest") }
            every { searchAnime(1L, any(), any()) } answers {
                val query = secondArg<String>()
                val applied = thirdArg<AnimeFilterList>()
                val day = (applied[0] as AnimeFilter.Select<*>).state
                val status = (applied[1] as AnimeFilter.Select<*>).state
                page(query.ifEmpty { if (status == 2) "completed" else "day-$day" })
            }
        }
        val getAnime = mockk<GetAnime> {
            every { subscribe(any<String>(), 1L) } answers {
                val title = firstArg<String>()
                flowOf(Anime.create().copy(id = 1L, source = 1L, title = title, url = title, favorite = favorite))
            }
        }
        val networkToLocal = mockk<NetworkToLocalAnime> {
            coEvery { await(any()) } answers { firstArg<Anime>().copy(id = 1L) }
        }
        val incognito = mockk<GetAnimeIncognitoState> { every { await(1L) } returns true }
        // Register each fixture as a separate screen. Standalone models otherwise share
        // Voyager dependency scopes, so disposing one would cancel later test fixtures.
        val holderKey = "dtoon-paging-test:${UUID.randomUUID()}"
        return ScreenModelStore.getOrPut(holderKey, null) { BrowseAnimeSourceScreenModel(
            sourceId = 1L,
            listingQuery = null,
            sourceManager = manager,
            sourcePreferences = preferences,
            libraryPreferences = mockk(),
            coverCache = mockk(),
            backgroundCache = mockk(),
            getRemoteAnime = GetRemoteAnime(repository),
            getDuplicateAnimelibAnime = mockk(),
            getCategories = mockk(),
            setAnimeCategories = mockk(),
            setAnimeDefaultEpisodeFlags = mockk(),
            getAnime = getAnime,
            networkToLocalAnime = networkToLocal,
            updateAnime = mockk(),
            addTracks = mockk(),
            getIncognitoState = incognito,
        ) }.also { modelKeys[it] = holderKey }
    }

    private fun TestScope.collect(flow: Flow<PagingData<StateFlow<Anime>>>): AsyncPagingDataDiffer<StateFlow<Anime>> {
        val differ = AsyncPagingDataDiffer(
            diffCallback = object : DiffUtil.ItemCallback<StateFlow<Anime>>() {
                override fun areItemsTheSame(oldItem: StateFlow<Anime>, newItem: StateFlow<Anime>) = oldItem.value.url == newItem.value.url
                override fun areContentsTheSame(oldItem: StateFlow<Anime>, newItem: StateFlow<Anime>) = oldItem.value == newItem.value
            },
            updateCallback = mockk<ListUpdateCallback>(relaxed = true),
            mainDispatcher = dispatcher,
            workerDispatcher = dispatcher,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collectLatest { differ.submitData(it) }
        }
        runCurrent()
        return differ
    }

    @Test
    fun `results and empty searches restore every catalog tab after clear or close`() = runTest(dispatcher) {
        for ((tab, expected) in listOf(
            CatalogTab.POPULAR to "popular", CatalogTab.LATEST to "latest",
            CatalogTab.WEEKDAY to "day-3", CatalogTab.COMPLETED to "completed",
        )) {
            for (query in listOf("hit", "empty")) {
                for (clear in listOf<String?>(null, "")) {
                    val model = model()
                    model.selectCatalogTab(tab.ordinal)
                    model.selectCatalogDay(3)
                    val original = model.catalogItems(tab, 3)
                    assertEquals(listOf(expected), collect(original).snapshot().items.map { it.value.title })
                    model.search(query)
                    val searching = collect(model.catalogItems(tab, 3))
                    assertEquals(if (query == "empty") emptyList() else listOf("hit"), searching.snapshot().items.map { it.value.title })
                    model.setToolbarQuery(clear)
                    val restored = model.catalogItems(tab, 3)
                    assertNotSame(original, restored)
                    assertEquals(listOf(expected), collect(restored).snapshot().items.map { it.value.title })
                    assertEquals(tab.ordinal, model.catalogTab)
                    assertEquals(3, model.catalogDay)
                    assertSame(restored, model.catalogItems(tab, 3))
                    ScreenModelStore.onDisposeNavigator(modelKeys.getValue(model))
                }
            }
        }
    }

    @Test
    fun `search includes library matches even when the ordinary catalogue hides them`() = runTest(dispatcher) {
        val model = model(hideInLibrary = true, favorite = true)
        assertTrue(collect(model.catalogItems(CatalogTab.POPULAR, 1)).snapshot().items.isEmpty())
        model.search("hit")
        assertEquals(listOf("hit"), collect(model.catalogItems(CatalogTab.POPULAR, 1)).snapshot().items.map { it.value.title })
        model.setToolbarQuery(null)
        assertTrue(collect(model.catalogItems(CatalogTab.POPULAR, 1)).snapshot().items.isEmpty())
    }

    @Test
    fun `quick cancel cancels an active load and a new collector can restore the list`() = runTest(dispatcher) {
        val model = model()
        model.search("slow")
        collect(model.catalogItems(CatalogTab.POPULAR, 3))
        assertTrue(slowStarted.isCompleted)
        model.setToolbarQuery(null)
        val restored = model.catalogItems(CatalogTab.POPULAR, 3)
        assertEquals(listOf("popular"), collect(restored).snapshot().items.map { it.value.title })
        assertTrue(slowCancelled.isCompleted)
        // A fresh collector, as after screen recreation, receives the live cached generation.
        assertEquals(listOf("popular"), collect(restored).snapshot().items.map { it.value.title })
        model.search("hit")
        assertEquals(listOf("hit"), collect(model.catalogItems(CatalogTab.POPULAR, 3)).snapshot().items.map { it.value.title })
    }
}
