package com.loosewire.kelp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.loosewire.kelp.protocol.ArtistSummary
import com.loosewire.kelp.protocol.AuthSnapshot
import com.loosewire.kelp.protocol.AuthState
import com.loosewire.kelp.protocol.HomeFeed
import com.loosewire.kelp.protocol.PlaylistSummary
import com.loosewire.kelp.protocol.ReleaseSummary
import com.loosewire.kelp.protocol.KelpError
import com.loosewire.kelp.protocol.TrackSummary
import com.loosewire.kelp.protocol.StartPlaybackRequest
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIconConfiguration
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class KelpTab(val label: String) {
    Home("Home"),
    Playlists("Playlists"),
    Artists("Artists"),
    Albums("Albums"),
    Songs("Songs"),
}

data class CatalogState<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: KelpError? = null,
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val loadMoreError: KelpError? = null,
)

class HomeViewModel(
    private val kelpClient: KelpClient = BinderTideClient,
    preferences: KelpPreferences? = null,
) : LightViewModel<Unit>() {
    data class UiState(
        val snapshot: AuthSnapshot? = null,
        val authError: KelpError? = null,
        val authLoading: Boolean = true,
        val selectedTab: KelpTab = KelpTab.Home,
        val artists: CatalogState<ArtistSummary> = CatalogState(),
        val albums: CatalogState<ReleaseSummary> = CatalogState(),
        val songs: CatalogState<TrackSummary> = CatalogState(),
        val playlists: CatalogState<PlaylistSummary> = CatalogState(),
        val home: CatalogState<HomeFeed> = CatalogState(),
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var authJob: Job? = null
    private var catalogJob: Job? = null
    private val playbackPreferences = preferences?.playback?.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = KelpPlaybackPreferences(),
    )

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        refreshAuth()
    }

    fun selectTab(tab: KelpTab) {
        if (_state.value.selectedTab == tab) return
        cancelCatalogLoad()
        _state.value = _state.value.copy(selectedTab = tab)
        loadSelectedTab()
    }

    fun retrySelectedTab() {
        loadSelectedTab(force = true)
    }

    fun loadMoreSelectedTab() {
        when (_state.value.selectedTab) {
            KelpTab.Artists -> loadMoreArtists()
            KelpTab.Albums -> loadMoreAlbums()
            KelpTab.Songs -> loadMoreSongs()
            KelpTab.Home,
            KelpTab.Playlists,
            -> Unit
        }
    }

    fun startPlayback(
        tracks: List<TrackSummary>,
        index: Int,
        sourceName: String,
        onStarted: () -> Unit,
    ) {
        viewModelScope.launch {
            val request = StartPlaybackRequest(
                tracks = tracks,
                startIndex = index,
                sourceName = sourceName,
                continuousPlayback = playbackPreferences?.value?.continuousPlayback ?: true,
            )
            if (kelpClient.startPlayback(request) is KelpClientResult.Success) onStarted()
        }
    }

    fun refreshAuth() {
        authJob?.cancel()
        authJob = viewModelScope.launch {
            _state.value = _state.value.copy(authLoading = true, authError = null)
            when (val result = kelpClient.authSnapshot()) {
                is KelpClientResult.Failure -> _state.value = _state.value.copy(
                    authError = result.error,
                    authLoading = false,
                )

                is KelpClientResult.Success -> {
                    _state.value = _state.value.copy(
                        snapshot = result.data,
                        authError = null,
                        authLoading = false,
                    )
                    if (result.data.state == AuthState.Authenticated) loadSelectedTab()
                }
            }
        }
    }

    fun loginActivityComponent(onComponent: (String) -> Unit) {
        viewModelScope.launch {
            when (val result = kelpClient.loginActivity()) {
                is KelpClientResult.Success -> onComponent(result.data.componentName)
                is KelpClientResult.Failure -> _state.value = _state.value.copy(authError = result.error)
            }
        }
    }

    private fun loadSelectedTab(force: Boolean = false) {
        if (_state.value.snapshot?.state != AuthState.Authenticated) return
        when (_state.value.selectedTab) {
            KelpTab.Artists -> loadArtists(force)
            KelpTab.Albums -> loadAlbums(force)
            KelpTab.Home -> loadHome(force)
            KelpTab.Playlists -> loadPlaylists(force)
            KelpTab.Songs -> loadSongs(force)
        }
    }

    private fun cancelCatalogLoad() {
        catalogJob?.cancel()
        catalogJob = null
        val current = _state.value
        _state.value = current.copy(
            artists = current.artists.copy(loading = false, loadingMore = false),
            albums = current.albums.copy(loading = false, loadingMore = false),
            songs = current.songs.copy(loading = false, loadingMore = false),
            playlists = current.playlists.copy(loading = false, loadingMore = false),
            home = current.home.copy(loading = false, loadingMore = false),
        )
    }

    private fun loadArtists(force: Boolean) {
        if (!force && (_state.value.artists.loaded || _state.value.artists.loading)) return
        catalogJob?.cancel()
        _state.value = _state.value.copy(
            artists = _state.value.artists.copy(loading = true, error = null),
        )
        catalogJob = viewModelScope.launch {
            val result = kelpClient.artists()
            currentCoroutineContext().ensureActive()
            when (result) {
                is KelpClientResult.Success -> _state.value = _state.value.copy(
                    artists = CatalogState(
                        items = result.data.items.distinctBy { it.id },
                        loaded = true,
                        nextCursor = result.data.nextCursor,
                    ),
                )
                is KelpClientResult.Failure -> _state.value = _state.value.copy(
                    artists = CatalogState(error = result.error, loaded = true),
                )
            }
        }
    }

    private fun loadAlbums(force: Boolean) {
        if (!force && (_state.value.albums.loaded || _state.value.albums.loading)) return
        catalogJob?.cancel()
        _state.value = _state.value.copy(
            albums = _state.value.albums.copy(loading = true, error = null),
        )
        catalogJob = viewModelScope.launch {
            val result = kelpClient.collection()
            currentCoroutineContext().ensureActive()
            when (result) {
                is KelpClientResult.Success -> _state.value = _state.value.copy(
                    albums = CatalogState(
                        items = result.data.items.distinctBy { it.id },
                        loaded = true,
                        nextCursor = result.data.nextCursor,
                    ),
                )
                is KelpClientResult.Failure -> _state.value = _state.value.copy(
                    albums = CatalogState(error = result.error, loaded = true),
                )
            }
        }
    }

    private fun loadSongs(force: Boolean) {
        if (!force && (_state.value.songs.loaded || _state.value.songs.loading)) return
        catalogJob?.cancel()
        _state.value = _state.value.copy(
            songs = _state.value.songs.copy(loading = true, error = null),
        )
        catalogJob = viewModelScope.launch {
            val result = kelpClient.tracks()
            currentCoroutineContext().ensureActive()
            when (result) {
                is KelpClientResult.Success -> _state.value = _state.value.copy(
                    songs = CatalogState(
                        items = result.data.items.distinctBy { it.id },
                        loaded = true,
                        nextCursor = result.data.nextCursor,
                    ),
                )
                is KelpClientResult.Failure -> _state.value = _state.value.copy(
                    songs = CatalogState(error = result.error, loaded = true),
                )
            }
        }
    }

    private fun loadHome(force: Boolean) {
        if (!force && (_state.value.home.loaded || _state.value.home.loading)) return
        catalogJob?.cancel()
        _state.value = _state.value.copy(home = _state.value.home.copy(loading = true, error = null))
        catalogJob = viewModelScope.launch {
            val result = kelpClient.home()
            currentCoroutineContext().ensureActive()
            when (result) {
                is KelpClientResult.Success -> _state.value = _state.value.copy(
                    home = CatalogState(items = listOf(result.data), loaded = true),
                    playlists = CatalogState(items = result.data.playlists, loaded = true),
                )
                is KelpClientResult.Failure -> _state.value = _state.value.copy(
                    home = CatalogState(error = result.error, loaded = true),
                )
            }
        }
    }

    private fun loadPlaylists(force: Boolean) {
        if (!force && (_state.value.playlists.loaded || _state.value.playlists.loading)) return
        catalogJob?.cancel()
        _state.value = _state.value.copy(
            playlists = _state.value.playlists.copy(loading = true, error = null),
        )
        catalogJob = viewModelScope.launch {
            val result = kelpClient.home()
            currentCoroutineContext().ensureActive()
            when (result) {
                is KelpClientResult.Success -> _state.value = _state.value.copy(
                    playlists = CatalogState(items = result.data.playlists, loaded = true),
                )
                is KelpClientResult.Failure -> _state.value = _state.value.copy(
                    playlists = CatalogState(error = result.error, loaded = true),
                )
            }
        }
    }

    private fun loadMoreArtists() {
        val current = _state.value.artists
        val cursor = current.nextCursor ?: return
        if (current.loading || current.loadingMore) return
        _state.value = _state.value.copy(artists = current.copy(loadingMore = true, loadMoreError = null))
        catalogJob = viewModelScope.launch {
            val result = kelpClient.artists(cursor)
            currentCoroutineContext().ensureActive()
            when (result) {
                is KelpClientResult.Success -> _state.value = _state.value.copy(
                    artists = current.copy(
                        items = (current.items + result.data.items).distinctBy { it.id },
                        nextCursor = result.data.nextCursor,
                        loadingMore = false,
                    ),
                )
                is KelpClientResult.Failure -> _state.value = _state.value.copy(
                    artists = current.copy(loadingMore = false, loadMoreError = result.error),
                )
            }
        }
    }

    private fun loadMoreAlbums() {
        val current = _state.value.albums
        val cursor = current.nextCursor ?: return
        if (current.loading || current.loadingMore) return
        _state.value = _state.value.copy(albums = current.copy(loadingMore = true, loadMoreError = null))
        catalogJob = viewModelScope.launch {
            val result = kelpClient.collection(cursor)
            currentCoroutineContext().ensureActive()
            when (result) {
                is KelpClientResult.Success -> _state.value = _state.value.copy(
                    albums = current.copy(
                        items = (current.items + result.data.items).distinctBy { it.id },
                        nextCursor = result.data.nextCursor,
                        loadingMore = false,
                    ),
                )
                is KelpClientResult.Failure -> _state.value = _state.value.copy(
                    albums = current.copy(loadingMore = false, loadMoreError = result.error),
                )
            }
        }
    }

    private fun loadMoreSongs() {
        val current = _state.value.songs
        val cursor = current.nextCursor ?: return
        if (current.loading || current.loadingMore) return
        _state.value = _state.value.copy(songs = current.copy(loadingMore = true, loadMoreError = null))
        catalogJob = viewModelScope.launch {
            val result = kelpClient.tracks(cursor)
            currentCoroutineContext().ensureActive()
            when (result) {
                is KelpClientResult.Success -> _state.value = _state.value.copy(
                    songs = current.copy(
                        items = (current.items + result.data.items).distinctBy { it.id },
                        nextCursor = result.data.nextCursor,
                        loadingMore = false,
                    ),
                )
                is KelpClientResult.Failure -> _state.value = _state.value.copy(
                    songs = current.copy(loadingMore = false, loadMoreError = result.error),
                )
            }
        }
    }
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeViewModel>(sealedActivity) {
    override val viewModelClass: Class<HomeViewModel>
        get() = HomeViewModel::class.java

    override fun createViewModel(): HomeViewModel = HomeViewModel(
        preferences = DataStoreTidePreferences(lightContext.dataStore),
    )

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val colors by LightThemeController.colors.collectAsState()
        val authenticated = state.snapshot?.state == AuthState.Authenticated

        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                KelpTopBar(authenticated)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    if (authenticated) TabContent(state) else ConnectionState(state)
                }

                if (authenticated) {
                    KelpBottomBar(state.selectedTab)
                } else if (state.snapshot?.canSignIn == true) {
                    LightBottomBar(
                        items = listOf(
                            LightBarButton.Text(
                                text = "SIGN IN",
                                onClick = {
                                    viewModel.loginActivityComponent(::startServerActivity)
                                },
                            ),
                        ),
                    )
                }
            }
        }
    }

    @Composable
    private fun KelpTopBar(authenticated: Boolean) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(3f.gridUnitsAsDp())
                .padding(horizontal = 1f.gridUnitsAsDp()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (authenticated) {
                TopBarIcon(LightIcons.AUDIO_MESSAGE, "Queue") { navigateTo(::QueueScreen) }
            }
            Spacer(modifier = Modifier.weight(1f))
            if (authenticated) {
                TopBarIcon(LightIcons.SEARCH, "Search") { navigateTo(::SearchScreen) }
                TopBarIcon(LightIcons.SETTINGS, "Settings") { navigateTo(::SettingsScreen) }
            }
        }
    }

    @Composable
    private fun TopBarIcon(
        icon: LightIconConfiguration,
        contentDescription: String,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(min = 3.5f.gridUnitsAsDp())
                .lightClickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            LightIcon(icon = icon, size = 2f, contentDescription = contentDescription)
        }
    }

    @Composable
    private fun KelpBottomBar(selected: KelpTab) {
        val tabs = listOf(
            KelpNavigationItem(KelpTab.Home, LightIcons.HOME),
            KelpNavigationItem(KelpTab.Playlists, LightIcons.LARGE_LIST),
            KelpNavigationItem(KelpTab.Artists, LightIcons.CONTACTS),
            KelpNavigationItem(KelpTab.Albums, LightIcons.MEDIA),
            KelpNavigationItem(KelpTab.Songs, LightIcons.CIRCLE),
        )

        KelpActionBar {
            tabs.forEach { item ->
                val isSelected = item.tab == selected
                KelpActionIcon(
                    icon = item.icon,
                    contentDescription = if (isSelected) {
                        "${item.tab.label}, selected"
                    } else {
                        item.tab.label
                    },
                    alpha = if (isSelected) 1f else InactiveIconAlpha,
                ) {
                    if (!isSelected) viewModel.selectTab(item.tab)
                }
            }
        }
    }

    @Composable
    private fun TabContent(state: HomeViewModel.UiState) {
        when (state.selectedTab) {
            KelpTab.Artists -> CatalogList(
                state = state.artists,
                emptyMessage = "No saved artists.",
                key = ArtistSummary::id,
            ) { artist ->
                ArtistRow(artist) {
                    navigateTo(screenFactory = { ArtistScreen(it, artist) })
                }
            }
            KelpTab.Albums -> CatalogList(
                state = state.albums,
                emptyMessage = "No saved albums.",
                key = ReleaseSummary::id,
            ) { album ->
                ReleaseRow(album) {
                    navigateTo(screenFactory = { AlbumScreen(it, album) })
                }
            }
            KelpTab.Home -> HomeContent(state.home)
            KelpTab.Playlists -> CatalogList(
                state = state.playlists,
                emptyMessage = "No saved playlists.",
                key = PlaylistSummary::id,
            ) { HomePlaylistRow(it) }
            KelpTab.Songs -> CatalogList(
                state = state.songs,
                emptyMessage = "No saved songs.",
                key = TrackSummary::id,
            ) { track ->
                TrackRow(track) {
                    viewModel.startPlayback(
                        tracks = state.songs.items,
                        index = state.songs.items.indexOfFirst { it.id == track.id },
                        sourceName = "Songs",
                    ) { navigateTo(::QueueScreen) }
                }
            }
        }
    }

    @Composable
    private fun HomeContent(state: CatalogState<HomeFeed>) {
        when {
            state.loading -> CenterMessage("Loading…")
            state.error != null -> RetryMessage(state.error.message)
            else -> LightScrollView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 1f.gridUnitsAsDp()),
            ) {
                val feed = state.items.firstOrNull()
                CatalogSectionLabel("Home mixes")
                if (feed == null || feed.mixes.isEmpty()) {
                    LightText(
                        text = "No mixes available.",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                    )
                } else {
                    feed.mixes.take(HomeSectionLimit).forEach {
                        HomePlaylistRow(it, showSongCount = false)
                    }
                }

                CatalogSectionLabel("Recent songs")
                if (feed == null || feed.recentTracks.isEmpty()) {
                    LightText(
                        text = "Play a song and it will appear here.",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                    )
                } else {
                    feed.recentTracks.take(HomeSectionLimit).forEach { track ->
                        TrackRow(track) {
                            viewModel.startPlayback(
                                tracks = feed.recentTracks,
                                index = feed.recentTracks.indexOfFirst { it.id == track.id },
                                sourceName = "Recent Songs",
                            ) { navigateTo(::QueueScreen) }
                        }
                    }
                }

                CatalogSectionLabel("Favorites")
                if (feed == null || feed.favoriteTracks.isEmpty()) {
                    LightText(
                        text = "No favorite songs.",
                        variant = LightTextVariant.Fine,
                        lighten = true,
                    )
                } else {
                    feed.favoriteTracks.take(HomeSectionLimit).forEach { track ->
                        TrackRow(track) {
                            viewModel.startPlayback(
                                tracks = feed.favoriteTracks,
                                index = feed.favoriteTracks.indexOfFirst { it.id == track.id },
                                sourceName = "Favorites",
                            ) { navigateTo(::QueueScreen) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HomePlaylistRow(
        playlist: PlaylistSummary,
        showSongCount: Boolean = true,
    ) {
        PlaylistRow(playlist, showSongCount = showSongCount) {
            navigateTo(screenFactory = { PlaylistScreen(it, playlist) })
        }
    }

    @Composable
    private fun <T> CatalogList(
        state: CatalogState<T>,
        emptyMessage: String,
        key: (T) -> String,
        row: @Composable (T) -> Unit,
    ) {
        when {
            state.loading -> CenterMessage("Loading…")
            state.error != null -> RetryMessage(state.error.message)
            state.loaded && state.items.isEmpty() -> CenterMessage(emptyMessage)
            else -> LightLazyScrollView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 1f.gridUnitsAsDp()),
                uniformItemHeightGridUnits = 5f,
            ) {
                items(state.items, key = key) { row(it) }
                if (state.nextCursor != null) {
                    item(key = "load-more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5f.gridUnitsAsDp())
                                .lightClickable(onClick = viewModel::loadMoreSelectedTab),
                            contentAlignment = Alignment.Center,
                        ) {
                            LightText(
                                text = when {
                                    state.loadingMore -> "Loading…"
                                    state.loadMoreError != null -> "Could not load more — retry"
                                    else -> "Load more"
                                },
                                variant = LightTextVariant.Copy,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ConnectionState(state: HomeViewModel.UiState) {
        CenterMessage(statusMessage(state))
    }

    @Composable
    private fun CenterMessage(message: String) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = message, variant = LightTextVariant.Copy, align = TextAlign.Center)
        }
    }

    @Composable
    private fun RetryMessage(message: String) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .lightClickable(onClick = viewModel::retrySelectedTab)
                .padding(horizontal = 2f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = "$message\n\nTap to retry.",
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
            )
        }
    }

    private fun statusMessage(state: HomeViewModel.UiState): String {
        state.authError?.let { return it.message }
        if (state.authLoading) return "Checking connection with Kelp server…"

        return when (state.snapshot?.state) {
            null -> "Could not read the TIDAL connection state."
            AuthState.MissingConfiguration ->
                "Add your TIDAL client ID to local.properties, then rebuild Kelp."
            AuthState.Unauthenticated -> "Sign in to connect your TIDAL account."
            AuthState.Authenticating -> "Finishing sign in…"
            AuthState.Authenticated -> "Connected to TIDAL."
            AuthState.Error -> state.snapshot.errorMessage ?: "TIDAL connection failed."
        }
    }

    private data class KelpNavigationItem(
        val tab: KelpTab,
        val icon: LightIconConfiguration,
    )

    private companion object {
        const val InactiveIconAlpha = 0.42f
        const val HomeSectionLimit = 6
    }
}
