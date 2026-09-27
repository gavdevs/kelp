package com.loosewire.kelp.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class ReleaseType {
    Album,
    Ep,
    Single,
}

@Serializable
data class ArtistSummary(
    val id: String,
    val name: String,
)

@Serializable
data class ReleaseSummary(
    val id: String,
    val title: String,
    val artistName: String,
    val type: ReleaseType,
    val itemCount: Int,
)

@Serializable
data class TrackSummary(
    val id: String,
    val title: String,
    val artistName: String,
    val durationMs: Long,
    val explicit: Boolean,
    val albumTitle: String? = null,
)

@Serializable
data class PlaylistSummary(
    val id: String,
    val name: String,
    val description: String? = null,
    val itemCount: Int = 0,
)

@Serializable
data class HomeFeed(
    val mixes: List<PlaylistSummary>,
    val playlists: List<PlaylistSummary>,
    val recentTracks: List<TrackSummary> = emptyList(),
    val favoriteTracks: List<TrackSummary> = emptyList(),
)

@Serializable
enum class ArtistReleaseSection {
    Albums,
    EpsAndSingles,
}

@Serializable
data class ArtistReleasesRequest(
    val artist: ArtistSummary,
    val section: ArtistReleaseSection,
    val cursor: String? = null,
)

@Serializable
data class ArtistTracksRequest(
    val artist: ArtistSummary,
    val cursor: String? = null,
)

@Serializable
data class SearchResults(
    val artists: List<ArtistSummary>,
    val releases: List<ReleaseSummary>,
    val tracks: List<TrackSummary>,
    val playlists: List<PlaylistSummary> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
enum class SearchSection {
    Artists,
    Songs,
    Albums,
    Playlists,
}

@Serializable
data class SearchPageRequest(
    val query: String,
    val section: SearchSection,
    val cursor: String? = null,
)

@Serializable
data class ArtistDetail(
    val artist: ArtistSummary,
    val releases: List<ReleaseSummary>,
    val tracks: List<TrackSummary>,
)

@Serializable
data class AlbumDetail(
    val album: ReleaseSummary,
    val tracks: List<TrackSummary>,
)

@Serializable
data class Page<T>(
    val items: List<T>,
    val nextCursor: String?,
) {
    val hasMore: Boolean
        get() = nextCursor != null
}

@Serializable
data class CollectionRequest(
    val cursor: String? = null,
)

@Serializable
data class SearchRequest(
    val query: String,
)

@Serializable
data class ArtistRequest(
    val artist: ArtistSummary,
)

@Serializable
data class AlbumRequest(
    val album: ReleaseSummary,
)

@Serializable
data class PlaylistRequest(
    val playlist: PlaylistSummary,
)

@Serializable
data class PlaylistDetail(
    val playlist: PlaylistSummary,
    val tracks: List<TrackSummary>,
)

@Serializable
enum class RepeatMode {
    Off,
    All,
    One,
}

@Serializable
data class PlaybackSnapshot(
    val current: TrackSummary? = null,
    val sourceName: String? = null,
    val queue: List<TrackSummary> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.Off,
    val isLoading: Boolean = false,
    val playWhenReady: Boolean = isPlaying,
    val error: KelpError? = null,
)

@Serializable
data class StartPlaybackRequest(
    val tracks: List<TrackSummary>,
    val startIndex: Int = 0,
    val sourceName: String? = null,
    val continuousPlayback: Boolean = true,
)

@Serializable
enum class PlayerCommand {
    TogglePlayPause,
    Previous,
    Next,
    ToggleShuffle,
    CycleRepeat,
    Seek,
}

@Serializable
data class PlayerCommandRequest(
    val command: PlayerCommand,
    val positionMs: Long? = null,
)
