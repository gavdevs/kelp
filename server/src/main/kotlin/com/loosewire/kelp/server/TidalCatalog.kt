package com.loosewire.kelp.server

import com.loosewire.kelp.protocol.AlbumDetail
import com.loosewire.kelp.protocol.ArtistDetail
import com.loosewire.kelp.protocol.ArtistReleaseSection
import com.loosewire.kelp.protocol.ArtistSummary
import com.loosewire.kelp.protocol.HomeFeed
import com.loosewire.kelp.protocol.Page
import com.loosewire.kelp.protocol.PlaylistDetail
import com.loosewire.kelp.protocol.PlaylistSummary
import com.loosewire.kelp.protocol.ReleaseSummary
import com.loosewire.kelp.protocol.ReleaseType
import com.loosewire.kelp.protocol.SearchResults
import com.loosewire.kelp.protocol.SearchSection
import com.loosewire.kelp.protocol.TrackSummary
import com.loosewire.kelp.protocol.KelpErrorCategory
import com.tidal.sdk.auth.CredentialsProvider
import com.tidal.sdk.tidalapi.generated.TidalApiClient
import com.tidal.sdk.tidalapi.generated.apis.Artists.CollapseByArtistsIdRelationshipsTracksGet
import com.tidal.sdk.tidalapi.generated.apis.UserCollectionPlaylists.CollectionViewUserCollectionPlaylistsIdRelationshipsItemsGet
import com.tidal.sdk.tidalapi.generated.models.AlbumsItemsMultiRelationshipDataDocument
import com.tidal.sdk.tidalapi.generated.models.AlbumsResourceObject
import com.tidal.sdk.tidalapi.generated.models.ArtistsResourceObject
import com.tidal.sdk.tidalapi.generated.models.IncludedInner
import com.tidal.sdk.tidalapi.generated.models.PlaylistsItemsMultiRelationshipDataDocument
import com.tidal.sdk.tidalapi.generated.models.PlaylistsResourceObject
import com.tidal.sdk.tidalapi.generated.models.UserCollectionAlbumsItemsMultiRelationshipDataDocument
import com.tidal.sdk.tidalapi.generated.models.ArtistsMultiRelationshipDataDocument
import com.tidal.sdk.tidalapi.generated.models.TracksResourceObject
import com.tidal.sdk.tidalapi.generated.models.TracksMultiRelationshipDataDocument
import com.tidal.sdk.tidalapi.generated.models.UserCollectionArtistsItemsMultiRelationshipDataDocument
import com.tidal.sdk.tidalapi.generated.models.UserCollectionTracksItemsMultiRelationshipDataDocument
import com.tidal.sdk.tidalapi.networking.RetrofitProvider
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Server-side catalog repository. Fetches saved releases from the TIDAL v2 API
 * and maps the JSON:API response to protocol-owned [ReleaseSummary] models.
 */
class TidalCatalog(
    credentialsProvider: CredentialsProvider,
) : Catalog {
    private val retrofitProvider = RetrofitProvider()
    private val retrofit = retrofitProvider.provideRetrofit(
        TidalApiClient.DEFAULT_BASE_URL,
        credentialsProvider,
    )
    private val client = TidalApiClient(
        credentialsProvider = credentialsProvider,
        retrofitProvider = retrofitProvider,
    )
    private val albumsApi = client.createUserCollectionAlbums()
    private val artistsApi = client.createUserCollectionArtists()
    private val tracksApi = client.createUserCollectionTracks()
    private val searchApi = retrofit.create(CurrentSearchApi::class.java)
    private val currentTracksApi = retrofit.create(CurrentTracksApi::class.java)
    private val artistApi = client.createArtists()
    private val albumApi = client.createAlbums()
    private val playlistCollectionApi = client.createUserCollectionPlaylists()
    private val playlistsApi = client.createPlaylists()
    private val dailyMixesApi = client.createUserDailyMixes()
    private val discoveryMixesApi = client.createUserDiscoveryMixes()
    private val newReleaseMixesApi = client.createUserNewReleaseMixes()
    private val trackApi = client.createTracks()

    override suspend fun collection(cursor: String?): Page<ReleaseSummary> {
        val response = albumsApi.userCollectionAlbumsIdRelationshipsItemsGet(
            id = "me",
            pageCursor = cursor,
            sort = listOf(SortByDateAddedDescending),
            include = listOf("items"),
        )
        val body = response.successBody("collection")
        return TidalCatalogMapper.mapResponse(body)
    }

    override suspend fun artists(cursor: String?): Page<ArtistSummary> {
        val response = artistsApi.userCollectionArtistsIdRelationshipsItemsGet(
            id = "me",
            pageCursor = cursor,
            sort = listOf(SortByNameAscending),
            include = listOf("items"),
        )
        val body = response.successBody("artists")
        return TidalCatalogMapper.mapArtists(body)
    }

    override suspend fun tracks(cursor: String?): Page<TrackSummary> {
        val response = tracksApi.userCollectionTracksIdRelationshipsItemsGet(
            id = "me",
            pageCursor = cursor,
            sort = listOf(SortByDateAddedDescending),
        )
        val body = response.successBody("songs")
        return Page(
            items = enrichedTracks(body.data.orEmpty().map { it.id }),
            nextCursor = body.links.next?.let(TidalCatalogMapper::extractCursor),
        )
    }

    override suspend fun search(query: String): SearchResults {
        val response = searchApi.searchResultsGet(
            query = query,
            include = "artists,albums.artists,tracks,playlists",
        )
        val body = response.successBody("search results")
        val results = TidalCatalogMapper.mapSearchJson(body)
        return results.copy(tracks = enrichedTracks(results.tracks.map { it.id }))
    }

    override suspend fun searchPage(
        query: String,
        section: SearchSection,
        cursor: String?,
    ): SearchResults {
        val relationship = section.relationshipName
        val include = if (section == SearchSection.Albums) "$relationship.artists" else relationship
        var combined = SearchResults(emptyList(), emptyList(), emptyList())
        val seenCursors = mutableSetOf<String?>()
        var nextCursor = cursor
        var requests = 0
        do {
            if (!seenCursors.add(nextCursor)) break
            val continuation = nextCursor?.let(SearchContinuation::decode)
            val response = if (continuation == null) {
                searchApi.searchResultsGet(query = query, include = include)
            } else {
                searchApi.searchRelationshipGet(
                    id = continuation.searchId,
                    relationship = relationship,
                    include = include,
                    pageCursor = continuation.pageCursor,
                )
            }
            val page = TidalCatalogMapper.mapSearchJson(
                response.successBody("search results"),
                section,
                continuation?.searchId,
            )
            combined = combined.merge(page)
            nextCursor = page.nextCursor
            requests += 1
        } while (
            nextCursor != null &&
            combined.itemCount(section) < LogicalPageSize &&
            requests < MaximumLogicalPageRequests
        )
        combined = combined.copy(nextCursor = nextCursor)
        return if (section == SearchSection.Songs) {
            combined.copy(tracks = enrichedTracks(combined.tracks.map { it.id }))
        } else {
            combined
        }
    }

    override suspend fun home(): HomeFeed {
        val daily = dailyMixesApi.userDailyMixesIdRelationshipsItemsGet(
            id = "me",
            include = listOf("items"),
        ).successBody("daily mixes")
        val discovery = discoveryMixesApi.userDiscoveryMixesIdRelationshipsItemsGet(
            id = "me",
            include = listOf("items"),
        ).successBody("discovery mixes")
        val newReleases = newReleaseMixesApi.userNewReleaseMixesIdRelationshipsItemsGet(
            id = "me",
            include = listOf("items"),
        ).successBody("new release mixes")
        val playlists = playlistCollectionApi.userCollectionPlaylistsIdRelationshipsItemsGet(
            id = "me",
            collectionView = CollectionViewUserCollectionPlaylistsIdRelationshipsItemsGet.FLAT,
            sort = listOf(SortByLastModifiedDescending),
            include = listOf("items"),
        ).successBody("playlists")
        val favorites = tracks(cursor = null).items.take(HomeTrackLimit)

        return HomeFeed(
            mixes = (
                TidalCatalogMapper.mapPlaylists(daily.data.orEmpty().map { it.id }, daily.included.orEmpty()) +
                    TidalCatalogMapper.mapPlaylists(
                        discovery.data.orEmpty().map { it.id },
                        discovery.included.orEmpty(),
                    ) +
                    TidalCatalogMapper.mapPlaylists(
                        newReleases.data.orEmpty().map { it.id },
                        newReleases.included.orEmpty(),
                    )
                ).distinctBy { it.id },
            playlists = TidalCatalogMapper.mapPlaylists(
                playlists.data.orEmpty().map { it.id },
                playlists.included.orEmpty(),
            ),
            recentTracks = RecentTracksStore.tracks().take(HomeTrackLimit),
            favoriteTracks = favorites,
        )
    }

    override suspend fun playlistDetail(playlist: PlaylistSummary): PlaylistDetail {
        val response = playlistsApi.playlistsIdRelationshipsItemsGet(
            id = playlist.id,
            sort = listOf("itemIndex"),
        )
        val body = response.successBody("playlist songs")
        return PlaylistDetail(
            playlist = playlist,
            tracks = enrichedTracks(body.data.orEmpty().map { it.id }),
        )
    }

    override suspend fun similarTracks(track: TrackSummary): List<TrackSummary> {
        val response = trackApi.tracksIdRelationshipsSimilarTracksGet(
            id = track.id,
        )
        val body = response.successBody("continuous playback songs")
        return enrichedTracks(body.data.orEmpty().map { it.id })
    }

    private suspend fun enrichedTracks(ids: List<String>): List<TrackSummary> {
        if (ids.isEmpty()) return emptyList()
        val response = currentTracksApi.tracksGet(
            include = listOf("artists", "albums"),
            filterId = ids,
        )
        return TidalCatalogMapper.mapTrackResourcesJson(
            ids,
            response.successBody("song details"),
        )
    }

    override suspend fun artistDetail(artist: ArtistSummary): ArtistDetail {
        val releasesResponse = artistApi.artistsIdRelationshipsAlbumsGet(
            id = artist.id,
            include = listOf("albums"),
        )
        val tracksResponse = artistApi.artistsIdRelationshipsTracksGet(
            id = artist.id,
            collapseBy = CollapseByArtistsIdRelationshipsTracksGet.NONE,
        )
        val releasesBody = releasesResponse.successBody("artist albums")
        val tracksBody = tracksResponse.successBody("artist songs")
        val detail = TidalCatalogMapper.mapArtistDetail(artist, releasesBody, tracksBody)
        return detail.copy(tracks = enrichedTracks(tracksBody.data.orEmpty().map { it.id }))
    }

    override suspend fun artistReleases(
        artist: ArtistSummary,
        section: ArtistReleaseSection,
        cursor: String?,
    ): Page<ReleaseSummary> {
        val releases = mutableListOf<ReleaseSummary>()
        val seenCursors = mutableSetOf<String?>()
        var nextCursor = cursor
        var requests = 0

        do {
            if (!seenCursors.add(nextCursor)) break
            val body = artistApi.artistsIdRelationshipsAlbumsGet(
                id = artist.id,
                pageCursor = nextCursor,
                include = listOf("albums"),
            ).successBody("artist releases")
            val page = TidalCatalogMapper.mapArtistReleases(artist, body)
            releases += page.items.filter { release ->
                when (section) {
                    ArtistReleaseSection.Albums -> release.type == ReleaseType.Album
                    ArtistReleaseSection.EpsAndSingles -> release.type != ReleaseType.Album
                }
            }
            nextCursor = page.nextCursor
            requests += 1
        } while (
            nextCursor != null &&
            releases.size < ArtistReleaseBatchSize &&
            requests < MaximumArtistReleaseRequests
        )

        return Page(
            items = releases.distinctBy(ReleaseSummary::id),
            nextCursor = nextCursor,
        )
    }

    override suspend fun artistTracks(artist: ArtistSummary, cursor: String?): Page<TrackSummary> {
        val ids = mutableListOf<String>()
        val seenCursors = mutableSetOf<String?>()
        var nextCursor = cursor
        var requests = 0
        do {
            if (!seenCursors.add(nextCursor)) break
            val body = artistApi.artistsIdRelationshipsTracksGet(
                id = artist.id,
                collapseBy = CollapseByArtistsIdRelationshipsTracksGet.NONE,
                pageCursor = nextCursor,
            ).successBody("artist songs")
            ids += body.data.orEmpty().map { it.id }
            nextCursor = body.links.next?.let(TidalCatalogMapper::extractCursor)
            requests += 1
        } while (
            nextCursor != null &&
            ids.size < LogicalPageSize &&
            requests < MaximumLogicalPageRequests
        )
        return Page(
            items = enrichedTracks(ids.distinct()),
            nextCursor = nextCursor,
        )
    }

    private fun SearchResults.merge(next: SearchResults): SearchResults = SearchResults(
        artists = (artists + next.artists).distinctBy(ArtistSummary::id),
        releases = (releases + next.releases).distinctBy(ReleaseSummary::id),
        tracks = (tracks + next.tracks).distinctBy(TrackSummary::id),
        playlists = (playlists + next.playlists).distinctBy(PlaylistSummary::id),
        nextCursor = next.nextCursor,
    )

    private fun SearchResults.itemCount(section: SearchSection): Int = when (section) {
        SearchSection.Artists -> artists.size
        SearchSection.Songs -> tracks.size
        SearchSection.Albums -> releases.size
        SearchSection.Playlists -> playlists.size
    }

    override suspend fun albumDetail(album: ReleaseSummary): AlbumDetail {
        val response = albumApi.albumsIdRelationshipsItemsGet(
            id = album.id,
        )
        val body = response.successBody("album songs")
        return AlbumDetail(
            album = album,
            tracks = enrichedTracks(body.data.orEmpty().map { it.id }),
        )
    }

    private fun <T> retrofit2.Response<T>.successBody(label: String): T {
        if (!isSuccessful) {
            val status = code()
            if (status == 401) KelpRuntime.authenticationFailed()
            throw when (status) {
                401 -> TidalCatalogException(
                    KelpErrorCategory.Authentication,
                    "Your TIDAL session expired. Please sign in again.",
                    status,
                )
                403 -> TidalCatalogException(
                    KelpErrorCategory.Authentication,
                    "Kelp does not have permission to load $label.",
                    status,
                )
                400 -> TidalCatalogException(
                    KelpErrorCategory.Protocol,
                    "TIDAL rejected the $label request.",
                    status,
                )
                429 -> TidalCatalogException(
                    KelpErrorCategory.Unavailable,
                    "TIDAL is receiving requests too quickly. Wait a moment and retry.",
                    status,
                )
                else -> TidalCatalogException(
                    KelpErrorCategory.Network,
                    "TIDAL could not load $label. Please retry.",
                    status,
                )
            }
        }
        return body() ?: throw TidalCatalogException(
            KelpErrorCategory.Protocol,
            "TIDAL returned invalid $label.",
            code(),
        )
    }

    companion object {
        private const val SortByDateAddedDescending = "-addedAt"
        private const val SortByLastModifiedDescending = "-lastModifiedAt"
        private const val SortByNameAscending = "name"
        private const val HomeTrackLimit = 6
        private const val ArtistReleaseBatchSize = 8
        private const val MaximumArtistReleaseRequests = 12
        private const val LogicalPageSize = 8
        private const val MaximumLogicalPageRequests = 12
    }
}

internal object TidalCatalogMapper {
    internal fun mapResponse(body: UserCollectionAlbumsItemsMultiRelationshipDataDocument): Page<ReleaseSummary> {
        val included = body.included.orEmpty()
        val albumsById = included.filterIsInstance<AlbumsResourceObject>().associateBy { it.id }
        val artistsById = included.filterIsInstance<ArtistsResourceObject>().associateBy { it.id }

        val items = body.data.orEmpty().mapNotNull { identifier ->
            val album = albumsById[identifier.id] ?: return@mapNotNull null
            mapAlbum(album, artistsById)
        }

        val nextCursor = body.links.next?.let(::extractCursor)
        return Page(items = items, nextCursor = nextCursor)
    }

    internal fun mapArtists(
        body: UserCollectionArtistsItemsMultiRelationshipDataDocument,
    ): Page<ArtistSummary> {
        val artistsById = body.included.orEmpty()
            .filterIsInstance<ArtistsResourceObject>()
            .associateBy { it.id }
        val artists = body.data.orEmpty().mapNotNull { identifier ->
            artistsById[identifier.id]?.let(::mapArtist)
        }
        return Page(artists, body.links.next?.let(::extractCursor))
    }

    internal fun mapTracks(
        body: UserCollectionTracksItemsMultiRelationshipDataDocument,
    ): Page<TrackSummary> {
        val included = body.included.orEmpty()
        val artistsById = included.filterIsInstance<ArtistsResourceObject>().associateBy { it.id }
        val albumsById = included.filterIsInstance<AlbumsResourceObject>().associateBy { it.id }
        val tracksById = included.filterIsInstance<TracksResourceObject>().associateBy { it.id }
        val tracks = body.data.orEmpty().mapNotNull { identifier ->
            tracksById[identifier.id]?.let { mapTrack(it, artistsById, albumsById = albumsById) }
        }
        return Page(tracks, body.links.next?.let(::extractCursor))
    }

    internal fun mapSearchJson(
        body: String,
        section: SearchSection? = null,
        searchId: String? = null,
    ): SearchResults {
        val document = rawDocument(body)
        val search = document.data.firstOrNull { it.string("type") == "searchResults" }
        val relationships = search?.get("relationships") as? JsonObject
        val included = document.included
        val artistsById = included.resourcesOfType("artists").associateBy { it.id() }
        val albumsById = included.resourcesOfType("albums").associateBy { it.id() }

        // `included` is an unordered resource lookup, not the ranked result list.
        fun hits(type: String): List<JsonObject> {
            if (section != null && section.relationshipName != type) return emptyList()
            val identifiers = if (searchId != null) {
                document.data
            } else {
                (relationships?.get(type) as? JsonObject)?.resources("data").orEmpty()
            }
            val resourcesById = included.resourcesOfType(type).associateBy { it.id() }
            return identifiers.resourcesOfType(type)
                .mapNotNull { resourcesById[it.id()] }
                .distinctBy { it.id() }
        }

        val nextLink = if (searchId != null) {
            document.nextLink
        } else {
            section?.let { (relationships?.get(it.relationshipName) as? JsonObject)?.nextLink() }
        }
        val id = searchId ?: search?.string("id")
        val nextCursor = nextLink?.let(::extractCursor)?.let { cursor ->
            id?.let { SearchContinuation(it, cursor).encode() }
        }
        return SearchResults(
            artists = hits("artists").map(::mapRawArtist),
            releases = hits("albums").map { mapRawAlbum(it, artistsById) },
            tracks = hits("tracks").map { mapRawTrack(it, artistsById, albumsById) },
            playlists = hits("playlists").map(::mapRawPlaylist),
            nextCursor = nextCursor,
        )
    }

    internal fun mapPlaylists(
        ids: List<String>,
        included: List<IncludedInner>,
    ): List<PlaylistSummary> {
        val playlistsById = included.filterIsInstance<PlaylistsResourceObject>().associateBy { it.id }
        return ids.mapNotNull { playlistsById[it]?.let(::mapPlaylist) }
    }

    internal fun mapPlaylist(playlist: PlaylistsResourceObject): PlaylistSummary = PlaylistSummary(
        id = playlist.id,
        name = playlist.attributes?.name ?: "Untitled Playlist",
        description = playlist.attributes?.description?.takeIf(String::isNotBlank),
        itemCount = playlist.attributes?.numberOfItems ?: 0,
    )

    internal fun mapPlaylistDetail(
        playlist: PlaylistSummary,
        body: PlaylistsItemsMultiRelationshipDataDocument,
    ): PlaylistDetail {
        val included = body.included.orEmpty()
        val tracksById = included.filterIsInstance<TracksResourceObject>().associateBy { it.id }
        val artistsById = included.filterIsInstance<ArtistsResourceObject>().associateBy { it.id }
        val albumsById = included.filterIsInstance<AlbumsResourceObject>().associateBy { it.id }
        return PlaylistDetail(
            playlist = playlist,
            tracks = body.data.orEmpty().mapNotNull { identifier ->
                tracksById[identifier.id]?.let { mapTrack(it, artistsById, albumsById = albumsById) }
            },
        )
    }

    internal fun mapAlbumDetail(
        album: ReleaseSummary,
        body: AlbumsItemsMultiRelationshipDataDocument,
    ): AlbumDetail {
        val included = body.included.orEmpty()
        val tracksById = included.filterIsInstance<TracksResourceObject>().associateBy { it.id }
        val artistsById = included.filterIsInstance<ArtistsResourceObject>().associateBy { it.id }
        val albumsById = included.filterIsInstance<AlbumsResourceObject>().associateBy { it.id }
        return AlbumDetail(
            album = album,
            tracks = body.data.orEmpty().mapNotNull { identifier ->
                tracksById[identifier.id]?.let {
                    mapTrack(
                        it,
                        artistsById,
                        fallbackArtistName = album.artistName,
                        albumsById = albumsById,
                    )
                }
            },
        )
    }

    internal fun mapSimilarTracks(body: TracksMultiRelationshipDataDocument): List<TrackSummary> {
        val included = body.included.orEmpty()
        val tracksById = included.filterIsInstance<TracksResourceObject>().associateBy { it.id }
        val artistsById = included.filterIsInstance<ArtistsResourceObject>().associateBy { it.id }
        val albumsById = included.filterIsInstance<AlbumsResourceObject>().associateBy { it.id }
        return body.data.orEmpty().mapNotNull { identifier ->
            tracksById[identifier.id]?.let { mapTrack(it, artistsById, albumsById = albumsById) }
        }
    }

    internal fun mapTrackResourcesJson(
        ids: List<String>,
        body: String,
    ): List<TrackSummary> {
        val document = rawDocument(body)
        val tracksById = document.data.resourcesOfType("tracks").associateBy { it.id() }
        val artistsById = document.included.resourcesOfType("artists").associateBy { it.id() }
        val albumsById = document.included.resourcesOfType("albums").associateBy { it.id() }
        return ids.mapNotNull { id ->
            tracksById[id]?.let { mapRawTrack(it, artistsById, albumsById) }
        }
    }

    private fun mapRawArtist(resource: JsonObject): ArtistSummary = ArtistSummary(
        id = resource.id(),
        name = resource.attributes().string("name") ?: "Unknown Artist",
    )

    private fun mapRawAlbum(
        resource: JsonObject,
        artistsById: Map<String, JsonObject>,
    ): ReleaseSummary {
        val attributes = resource.attributes()
        val artistName = resource.relationshipIds("artists")
            .firstNotNullOfOrNull { artistsById[it]?.attributes()?.string("name") }
        return mapRelease(
            id = resource.id(),
            title = attributes.string("title"),
            artistName = artistName,
            typeName = attributes.string("albumType"),
            itemCount = attributes.int("numberOfItems"),
        )
    }

    private fun mapRawPlaylist(resource: JsonObject): PlaylistSummary {
        val attributes = resource.attributes()
        return PlaylistSummary(
            id = resource.id(),
            name = attributes.string("name") ?: "Untitled Playlist",
            description = attributes.string("description")?.takeIf(String::isNotBlank),
            itemCount = attributes.int("numberOfItems")
                ?: attributes.int("numberOfTrackItems")
                ?: 0,
        )
    }

    private fun mapRawTrack(
        resource: JsonObject,
        artistsById: Map<String, JsonObject>,
        albumsById: Map<String, JsonObject>,
    ): TrackSummary {
        val attributes = resource.attributes()
        return TrackSummary(
            id = resource.id(),
            title = attributes.string("title") ?: "Untitled",
            artistName = resource.relationshipIds("artists")
                .firstNotNullOfOrNull { artistsById[it]?.attributes()?.string("name") }
                ?: "Unknown Artist",
            durationMs = parseDurationMs(attributes.string("duration")),
            explicit = attributes.boolean("explicit") ?: false,
            albumTitle = resource.relationshipIds("albums")
                .firstNotNullOfOrNull { albumsById[it]?.attributes()?.string("title") },
        )
    }

    private fun rawDocument(body: String): RawJsonApiDocument {
        val root = Json.parseToJsonElement(body) as? JsonObject
            ?: throw IllegalArgumentException("TIDAL response was not a JSON object")
        return RawJsonApiDocument(
            data = root.resources("data"),
            included = root.resources("included"),
            nextLink = root.nextLink(),
        )
    }

    private fun JsonObject.nextLink(): String? {
        val links = this["links"] as? JsonObject ?: return null
        return when (val next = links["next"]) {
            is JsonPrimitive -> next.contentOrNull
            is JsonObject -> next.string("href")
            else -> null
        }
    }

    private fun JsonObject.resources(name: String): List<JsonObject> =
        (this[name] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun List<JsonObject>.resourcesOfType(type: String): List<JsonObject> =
        filter { it.string("type") == type }

    private fun JsonObject.id(): String = string("id").orEmpty()

    private fun JsonObject.attributes(): JsonObject = this["attributes"] as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.relationshipIds(name: String): List<String> {
        val relationships = this["relationships"] as? JsonObject ?: return emptyList()
        val relationship = relationships[name] as? JsonObject ?: return emptyList()
        return when (val data = relationship["data"]) {
            is JsonArray -> data.mapNotNull { (it as? JsonObject)?.string("id") }
            is JsonObject -> listOfNotNull(data.string("id"))
            else -> emptyList()
        }
    }

    private fun JsonObject.string(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(name: String): Int? =
        (this[name] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.boolean(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.booleanOrNull

    private data class RawJsonApiDocument(
        val data: List<JsonObject>,
        val included: List<JsonObject>,
        val nextLink: String?,
    )

    internal fun mapArtistDetail(
        artist: ArtistSummary,
        releasesBody: ArtistsMultiRelationshipDataDocument,
        tracksBody: ArtistsMultiRelationshipDataDocument,
    ): ArtistDetail {
        val included = releasesBody.included.orEmpty() + tracksBody.included.orEmpty()
        val artistsById = included.filterIsInstance<ArtistsResourceObject>().associateBy { it.id }
        return ArtistDetail(
            artist = artist,
            releases = releasesBody.included.orEmpty()
                .filterIsInstance<AlbumsResourceObject>()
                .map { mapAlbum(it, artistsById, artist.name) },
            tracks = tracksBody.included.orEmpty()
                .filterIsInstance<TracksResourceObject>()
                .map { mapTrack(it, artistsById, artist.name) },
        )
    }

    internal fun mapArtistReleases(
        artist: ArtistSummary,
        body: ArtistsMultiRelationshipDataDocument,
    ): Page<ReleaseSummary> {
        val included = body.included.orEmpty()
        val albumsById = included.filterIsInstance<AlbumsResourceObject>().associateBy { it.id }
        val artistsById = included.filterIsInstance<ArtistsResourceObject>().associateBy { it.id }
        return Page(
            items = body.data.orEmpty().mapNotNull { identifier ->
                albumsById[identifier.id]?.let { mapAlbum(it, artistsById, artist.name) }
            },
            nextCursor = body.links.next?.let(::extractCursor),
        )
    }

    internal fun mapArtist(artist: ArtistsResourceObject): ArtistSummary = ArtistSummary(
        id = artist.id,
        name = artist.attributes?.name ?: "Unknown Artist",
    )

    internal fun mapTrack(
        track: TracksResourceObject,
        artistsById: Map<String, ArtistsResourceObject>,
        fallbackArtistName: String? = null,
        albumsById: Map<String, AlbumsResourceObject> = emptyMap(),
    ): TrackSummary {
        val artistName = track.relationships?.artists?.data.orEmpty()
            .mapNotNull { artistsById[it.id]?.attributes?.name }
            .firstOrNull()
            ?: fallbackArtistName
            ?: "Unknown Artist"
        return TrackSummary(
            id = track.id,
            title = track.attributes?.title ?: "Untitled",
            artistName = artistName,
            durationMs = parseDurationMs(track.attributes?.duration),
            explicit = track.attributes?.explicit ?: false,
            albumTitle = track.relationships?.albums?.data.orEmpty()
                .firstNotNullOfOrNull { albumsById[it.id]?.attributes?.title },
        )
    }

    internal fun parseDurationMs(value: String?): Long {
        val match = DurationPattern.matchEntire(value.orEmpty()) ?: return 0L
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: 0L
        val seconds = match.groupValues[3].toDoubleOrNull() ?: 0.0
        return ((hours * 3_600 + minutes * 60) * 1_000 + seconds * 1_000).toLong()
    }

    internal fun mapAlbum(
        album: AlbumsResourceObject,
        artistsById: Map<String, ArtistsResourceObject>,
        fallbackArtistName: String? = null,
    ): ReleaseSummary {
        val attrs = album.attributes
        val artistIds = album.relationships?.artists?.data.orEmpty().map { it.id }
        val artistName = artistIds
            .mapNotNull { artistsById[it]?.attributes?.name }
            .firstOrNull()

        return mapRelease(
            id = album.id,
            title = attrs?.title,
            artistName = artistName ?: fallbackArtistName,
            typeName = attrs?.albumType?.name,
            itemCount = attrs?.numberOfItems,
        )
    }

    internal fun mapRelease(
        id: String,
        title: String?,
        artistName: String?,
        typeName: String?,
        itemCount: Int?,
    ): ReleaseSummary = ReleaseSummary(
        id = id,
        title = title ?: "Untitled",
        artistName = artistName ?: "Unknown Artist",
        type = mapReleaseType(typeName),
        itemCount = itemCount ?: 0,
    )

    internal fun mapReleaseType(typeName: String?): ReleaseType = when (typeName) {
        "EP" -> ReleaseType.Ep
        "SINGLE" -> ReleaseType.Single
        else -> ReleaseType.Album
    }

    internal fun extractCursor(nextUrl: String): String? = runCatching {
        URI(nextUrl).rawQuery
            ?.split('&')
            ?.firstNotNullOfOrNull { parameter ->
                val key = parameter.substringBefore('=')
                if (URLDecoder.decode(key, StandardCharsets.UTF_8.name()) != "page[cursor]") {
                    return@firstNotNullOfOrNull null
                }
                URLDecoder.decode(
                    parameter.substringAfter('=', missingDelimiterValue = ""),
                    StandardCharsets.UTF_8.name(),
                ).takeIf(String::isNotBlank)
            }
    }.getOrNull()

    private val DurationPattern = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?")

}

private val SearchSection.relationshipName: String
    get() = when (this) {
        SearchSection.Artists -> "artists"
        SearchSection.Songs -> "tracks"
        SearchSection.Albums -> "albums"
        SearchSection.Playlists -> "playlists"
    }

interface Catalog {
    suspend fun collection(cursor: String?): Page<ReleaseSummary>
    suspend fun artists(cursor: String?): Page<ArtistSummary> = error("Artists are not implemented")
    suspend fun tracks(cursor: String?): Page<TrackSummary> = error("Tracks are not implemented")
    suspend fun home(): HomeFeed = error("Home is not implemented")
    suspend fun search(query: String): SearchResults = error("Search is not implemented")
    suspend fun searchPage(query: String, section: SearchSection, cursor: String?): SearchResults =
        error("Search pages are not implemented")
    suspend fun artistDetail(artist: ArtistSummary): ArtistDetail = error("Artist details are not implemented")
    suspend fun artistReleases(
        artist: ArtistSummary,
        section: ArtistReleaseSection,
        cursor: String?,
    ): Page<ReleaseSummary> = error("Artist releases are not implemented")
    suspend fun artistTracks(artist: ArtistSummary, cursor: String?): Page<TrackSummary> =
        error("Artist tracks are not implemented")
    suspend fun albumDetail(album: ReleaseSummary): AlbumDetail = error("Album details are not implemented")
    suspend fun playlistDetail(playlist: PlaylistSummary): PlaylistDetail =
        error("Playlist details are not implemented")
    suspend fun similarTracks(track: TrackSummary): List<TrackSummary> =
        error("Similar tracks are not implemented")
}

class TidalCatalogException(
    val category: KelpErrorCategory,
    val safeMessage: String,
    val statusCode: Int? = null,
) : Exception(safeMessage)
