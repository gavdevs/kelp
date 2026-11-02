package com.loosewire.kelp

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.loosewire.kelp.protocol.ArtistSummary
import com.loosewire.kelp.protocol.PlaylistSummary
import com.loosewire.kelp.protocol.ReleaseSummary
import com.loosewire.kelp.protocol.SearchResults
import com.loosewire.kelp.protocol.SearchSection
import com.loosewire.kelp.protocol.StartPlaybackRequest
import com.loosewire.kelp.protocol.KelpError
import com.loosewire.kelp.protocol.TrackSummary
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val SearchSection.title: String
    get() = when (this) {
        SearchSection.Artists -> "Artists"
        SearchSection.Songs -> "Songs"
        SearchSection.Albums -> "Albums"
        SearchSection.Playlists -> "Playlists"
    }

class SearchViewModel : LightViewModel<Unit>() {
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    fun setQuery(query: String) {
        _query.value = query.trim()
    }
}

class SearchScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, SearchViewModel>(sealedActivity) {
    override val viewModelClass: Class<SearchViewModel>
        get() = SearchViewModel::class.java

    override fun createViewModel(): SearchViewModel = SearchViewModel()

    @Composable
    override fun Content() {
        val query by viewModel.query.collectAsState()
        val colors by LightThemeController.colors.collectAsState()

        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back",
                    ),
                    center = LightTopBarCenter.Text("Search"),
                )
                SearchContent(query)
            }
        }
    }

    @Composable
    private fun SearchContent(query: String) {
        LightScrollView(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            LightTextField(
                label = "SEARCH TIDAL",
                value = query,
                placeholder = "Artist, album, playlist, or song",
                onClick = {
                    navigateTo(
                        screenFactory = { SearchEditorScreen(it, initialQuery = query) },
                        resultCallback = viewModel::setQuery,
                    )
                },
            )
            if (query.isBlank()) {
                LightText(
                    text = "Enter an artist, album, playlist, or song.",
                    variant = LightTextVariant.Copy,
                    modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp()),
                )
            } else {
                SearchSection.entries.forEach { section -> SectionRow(query, section) }
            }
        }
    }

    @Composable
    private fun SectionRow(query: String, section: SearchSection) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.5f.gridUnitsAsDp())
                .lightClickable {
                    navigateTo(screenFactory = { SearchResultsScreen(it, query, section) })
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightText(text = section.title, variant = LightTextVariant.Subheading)
            LightIcon(
                icon = LightIcons.ARROW_RIGHT,
                size = 1.5f,
                contentDescription = "Open ${section.title}",
            )
        }
    }
}

internal class SearchResultsViewModel(
    private val query: String,
    private val section: SearchSection,
    private val kelpClient: KelpClient = BinderTideClient,
    preferences: KelpPreferences? = null,
) : LightViewModel<Unit>() {
    data class UiState(
        val results: SearchResults = SearchResults(emptyList(), emptyList(), emptyList()),
        val loading: Boolean = true,
        val loaded: Boolean = false,
        val loadingMore: Boolean = false,
        val error: KelpError? = null,
        val loadMoreError: KelpError? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var requestJob: Job? = null
    private var requestGeneration = 0L
    private val playbackPreferences = preferences?.playback?.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = KelpPlaybackPreferences(),
    )

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        if (!_state.value.loaded && requestJob?.isActive != true) loadFirstPage()
    }

    fun loadFirstPage() {
        val generation = ++requestGeneration
        requestJob?.cancel()
        _state.value = UiState()
        requestJob = viewModelScope.launch { loadPage(null, append = false, generation = generation) }
    }

    fun loadMore() {
        val current = _state.value
        val cursor = current.results.nextCursor ?: return
        if (current.loading || current.loadingMore) return
        val generation = ++requestGeneration
        _state.value = current.copy(loadingMore = true, loadMoreError = null)
        requestJob?.cancel()
        requestJob = viewModelScope.launch { loadPage(cursor, append = true, generation = generation) }
    }

    private suspend fun loadPage(cursor: String?, append: Boolean, generation: Long) {
        val before = _state.value
        val result = kelpClient.searchPage(query, section, cursor)
        if (generation != requestGeneration) return
        when (result) {
            is KelpClientResult.Success -> _state.value = UiState(
                results = if (append) before.results.append(result.data) else result.data,
                loading = false,
                loaded = true,
            )
            is KelpClientResult.Failure -> _state.value = if (append) {
                before.copy(loadingMore = false, loadMoreError = result.error)
            } else {
                UiState(loading = false, loaded = true, error = result.error)
            }
        }
    }

    private fun SearchResults.append(next: SearchResults) = SearchResults(
        artists = (artists + next.artists).distinctBy(ArtistSummary::id),
        releases = (releases + next.releases).distinctBy(ReleaseSummary::id),
        tracks = (tracks + next.tracks).distinctBy(TrackSummary::id),
        playlists = (playlists + next.playlists).distinctBy(PlaylistSummary::id),
        nextCursor = next.nextCursor,
    )

    fun play(index: Int, onStarted: () -> Unit) {
        val tracks = _state.value.results.tracks
        if (index !in tracks.indices) return
        viewModelScope.launch {
            val result = kelpClient.startPlayback(
                StartPlaybackRequest(
                    tracks = tracks,
                    startIndex = index,
                    sourceName = "Search: $query",
                    continuousPlayback = playbackPreferences?.value?.continuousPlayback ?: true,
                ),
            )
            if (result is KelpClientResult.Success) onStarted()
        }
    }
}

private class SearchResultsScreen(
    sealedActivity: SealedLightActivity,
    private val query: String,
    private val section: SearchSection,
) : LightScreen<Unit, SearchResultsViewModel>(sealedActivity) {
    override val viewModelClass: Class<SearchResultsViewModel>
        get() = SearchResultsViewModel::class.java

    override fun createViewModel(): SearchResultsViewModel = SearchResultsViewModel(
        query = query,
        section = section,
        preferences = DataStoreTidePreferences(lightContext.dataStore),
    )

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val colors by LightThemeController.colors.collectAsState()
        LightTheme(colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    leftButton = LightBarButton.LightIcon(
                        icon = LightIcons.BACK,
                        onClick = { goBack() },
                        contentDescription = "Back",
                    ),
                    center = LightTopBarCenter.Text(section.title),
                )
                ResultsContent(state)
            }
        }
    }

    @Composable
    private fun ResultsContent(state: SearchResultsViewModel.UiState) {
        val results = state.results
        when {
            state.loading -> Message("Searching…")
            state.error != null -> Message("${state.error.message}\n\nTap to retry.", viewModel::loadFirstPage)
            results.isEmpty() && results.nextCursor == null -> Message("No ${section.title.lowercase()} found.")
            else -> LightLazyScrollView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 1f.gridUnitsAsDp()),
                uniformItemHeightGridUnits = 5f,
            ) {
                items(results.artists, key = { "artist-${it.id}" }) { artist ->
                    ArtistRow(artist) {
                        navigateTo(screenFactory = { ArtistScreen(it, artist) })
                    }
                }
                items(results.releases, key = { "release-${it.id}" }) { release ->
                    ReleaseRow(release) {
                        navigateTo(screenFactory = { AlbumScreen(it, release) })
                    }
                }
                items(results.tracks, key = { "track-${it.id}" }) { track ->
                    TrackRow(track) {
                        viewModel.play(results.tracks.indexOfFirst { it.id == track.id }) {
                            navigateTo(::QueueScreen)
                        }
                    }
                }
                items(results.playlists, key = { "playlist-${it.id}" }) { playlist ->
                    PlaylistRow(playlist) {
                        navigateTo(screenFactory = { PlaylistScreen(it, playlist) })
                    }
                }
                if (results.nextCursor != null) {
                    item(key = "load-more") { LoadMoreRow(state) }
                }
            }
        }
    }

    private fun SearchResults.isEmpty(): Boolean =
        artists.isEmpty() && releases.isEmpty() && tracks.isEmpty() && playlists.isEmpty()

    @Composable
    private fun LoadMoreRow(state: SearchResultsViewModel.UiState) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5f.gridUnitsAsDp())
                .lightClickable(onClick = viewModel::loadMore),
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

    @Composable
    private fun Message(message: String, onClick: (() -> Unit)? = null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .let { if (onClick == null) it else it.lightClickable(onClick = onClick) }
                .padding(horizontal = 2f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            LightText(text = message, variant = LightTextVariant.Copy, align = TextAlign.Center)
        }
    }
}
