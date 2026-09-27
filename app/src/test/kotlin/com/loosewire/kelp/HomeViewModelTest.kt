package com.loosewire.kelp

import com.loosewire.kelp.protocol.ArtistDetail
import com.loosewire.kelp.protocol.ArtistSummary
import com.loosewire.kelp.protocol.AuthSnapshot
import com.loosewire.kelp.protocol.AuthState
import com.loosewire.kelp.protocol.Page
import com.loosewire.kelp.protocol.HomeFeed
import com.loosewire.kelp.protocol.PlaylistSummary
import com.loosewire.kelp.protocol.ReleaseSummary
import com.loosewire.kelp.protocol.ReleaseType
import com.loosewire.kelp.protocol.SearchResults
import com.loosewire.kelp.protocol.ServerActivity
import com.loosewire.kelp.protocol.TrackSummary
import com.loosewire.kelp.protocol.SearchSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun authenticatedRefreshLoadsHomeMixesAndPlaylistsByDefault() = runTest(dispatcher) {
        val feed = HomeFeed(
            mixes = listOf(PlaylistSummary("mix", "My Mix 1")),
            playlists = listOf(PlaylistSummary("playlist", "Favorites")),
        )
        val client = HomeFakeTideClient(
            auth = AuthSnapshot(AuthState.Authenticated),
            homeFeed = feed,
        )
        val viewModel = HomeViewModel(client)

        viewModel.refreshAuth()
        runCurrent()

        assertEquals(KelpTab.Home, viewModel.state.value.selectedTab)
        assertEquals(feed, viewModel.state.value.home.items.single())
        assertFalse(viewModel.state.value.home.loading)
        assertEquals(1, client.homeCalls)
    }

    @Test
    fun selectingAlbumsLoadsAlbumTabOnce() = runTest(dispatcher) {
        val client = HomeFakeTideClient(
            auth = AuthSnapshot(AuthState.Authenticated),
            savedAlbums = listOf(release("album")),
        )
        val viewModel = HomeViewModel(client)
        viewModel.refreshAuth()
        runCurrent()

        viewModel.selectTab(KelpTab.Albums)
        runCurrent()
        viewModel.selectTab(KelpTab.Home)
        viewModel.selectTab(KelpTab.Albums)
        runCurrent()

        assertEquals(1, client.albumCalls)
        assertEquals(listOf("album"), viewModel.state.value.albums.items.map { it.id })
    }

    @Test
    fun searchQueryIsTrimmedBeforeShowingResultSections() = runTest(dispatcher) {
        val viewModel = SearchViewModel()

        viewModel.setQuery("  Jon Batiste  ")

        assertEquals("Jon Batiste", viewModel.query.value)
    }

    @Test
    fun unauthenticatedRefreshDoesNotLoadCatalog() = runTest(dispatcher) {
        val client = HomeFakeTideClient(auth = AuthSnapshot(AuthState.Unauthenticated))
        val viewModel = HomeViewModel(client)

        viewModel.refreshAuth()
        runCurrent()

        assertEquals(0, client.artistCalls)
        assertEquals(0, client.albumCalls)
    }

    @Test
    fun switchingTabsDuringPaginationPreservesAlbumsAndAllowsRetry() = runTest(dispatcher) {
        val pending = CompletableDeferred<KelpClientResult<Page<ReleaseSummary>>>()
        var moreCalls = 0
        val client = object : KelpClient by HomeFakeTideClient(AuthSnapshot(AuthState.Authenticated)) {
            override suspend fun collection(cursor: String?): KelpClientResult<Page<ReleaseSummary>> =
                if (cursor == null) {
                    KelpClientResult.Success(Page(listOf(release("saved")), "next"))
                } else if (++moreCalls == 1) {
                    withContext(NonCancellable) { pending.await() }
                } else {
                    KelpClientResult.Success(Page(listOf(release("next")), null))
                }
        }
        val viewModel = HomeViewModel(client)
        viewModel.refreshAuth()
        runCurrent()
        viewModel.selectTab(KelpTab.Albums)
        runCurrent()
        viewModel.loadMoreSelectedTab()
        runCurrent()
        assertTrue(viewModel.state.value.albums.loadingMore)

        viewModel.selectTab(KelpTab.Home)
        viewModel.selectTab(KelpTab.Albums)
        assertFalse(viewModel.state.value.albums.loadingMore)
        assertEquals(listOf("saved"), viewModel.state.value.albums.items.map { it.id })
        viewModel.loadMoreSelectedTab()
        runCurrent()
        pending.complete(KelpClientResult.Success(Page(listOf(release("stale")), null)))
        runCurrent()

        assertEquals(listOf("saved", "next"), viewModel.state.value.albums.items.map { it.id })
        assertFalse(viewModel.state.value.albums.loadingMore)
        assertEquals(2, moreCalls)
    }

    @Test
    fun staleSearchPageCannotReplaceRefreshedResults() = runTest(dispatcher) {
        val pending = CompletableDeferred<KelpClientResult<SearchResults>>()
        var calls = 0
        val client = object : KelpClient by HomeFakeTideClient(AuthSnapshot(AuthState.Authenticated)) {
            override suspend fun searchPage(
                query: String,
                section: SearchSection,
                cursor: String?,
            ): KelpClientResult<SearchResults> = when (++calls) {
                1 -> KelpClientResult.Success(
                    SearchResults(emptyList(), listOf(release("old")), emptyList(), nextCursor = "next"),
                )
                2 -> withContext(NonCancellable) { pending.await() }
                else -> KelpClientResult.Success(
                    SearchResults(emptyList(), listOf(release("fresh")), emptyList()),
                )
            }
        }
        val viewModel = SearchResultsViewModel("Artist", SearchSection.Albums, client)
        viewModel.loadFirstPage()
        runCurrent()
        viewModel.loadMore()
        runCurrent()
        viewModel.loadFirstPage()
        runCurrent()
        pending.complete(
            KelpClientResult.Success(SearchResults(emptyList(), listOf(release("stale")), emptyList())),
        )
        runCurrent()

        assertEquals(listOf("fresh"), viewModel.state.value.results.releases.map { it.id })
        assertFalse(viewModel.state.value.loadingMore)
    }

    private fun release(id: String) = ReleaseSummary(
        id = id,
        title = "Release $id",
        artistName = "Artist",
        type = ReleaseType.Album,
        itemCount = 1,
    )
}

private class HomeFakeTideClient(
    private val auth: AuthSnapshot,
    private val savedArtists: List<ArtistSummary> = emptyList(),
    private val savedAlbums: List<ReleaseSummary> = emptyList(),
    private val savedTracks: List<TrackSummary> = emptyList(),
    private val homeFeed: HomeFeed = HomeFeed(emptyList(), emptyList()),
    private val searchResult: SearchResults = SearchResults(emptyList(), emptyList(), emptyList()),
) : KelpClient {
    var artistCalls = 0
        private set
    var albumCalls = 0
        private set
    var searchCalls = 0
        private set
    var homeCalls = 0
        private set

    override suspend fun authSnapshot(): KelpClientResult<AuthSnapshot> =
        KelpClientResult.Success(auth)

    override suspend fun loginActivity(): KelpClientResult<ServerActivity> =
        KelpClientResult.Success(ServerActivity("com.loosewire.kelp/.server.LoginActivity"))

    override suspend fun collection(cursor: String?): KelpClientResult<Page<ReleaseSummary>> {
        albumCalls += 1
        return KelpClientResult.Success(Page(savedAlbums, nextCursor = null))
    }

    override suspend fun artists(cursor: String?): KelpClientResult<Page<ArtistSummary>> {
        artistCalls += 1
        return KelpClientResult.Success(Page(savedArtists, nextCursor = null))
    }

    override suspend fun tracks(cursor: String?): KelpClientResult<Page<TrackSummary>> =
        KelpClientResult.Success(Page(savedTracks, nextCursor = null))

    override suspend fun home(): KelpClientResult<HomeFeed> {
        homeCalls += 1
        return KelpClientResult.Success(homeFeed)
    }

    override suspend fun search(query: String): KelpClientResult<SearchResults> {
        searchCalls += 1
        return KelpClientResult.Success(searchResult)
    }

    override suspend fun artistDetail(artist: ArtistSummary): KelpClientResult<ArtistDetail> =
        KelpClientResult.Success(ArtistDetail(artist, emptyList(), emptyList()))
}
