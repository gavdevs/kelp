package com.loosewire.kelp.server

import com.loosewire.kelp.protocol.ReleaseType
import com.loosewire.kelp.protocol.SearchSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class TidalCatalogMapperTest {
    @Test
    fun cursorExtractionTreatsBlankAndMissingValuesAsNoNextPage() {
        assertEquals(
            "opaque token",
            TidalCatalogMapper.extractCursor("https://openapi.tidal.com/albums?page%5Bcursor%5D=opaque%20token"),
        )
        assertNull(TidalCatalogMapper.extractCursor("https://openapi.tidal.com/albums?page%5Bcursor%5D="))
        assertNull(TidalCatalogMapper.extractCursor("https://openapi.tidal.com/albums?page%5Blimit%5D=20"))
    }

    @Test
    fun missingAlbumFieldsUseSafeFallbacks() {
        val release = TidalCatalogMapper.mapRelease(
            id = "album-id",
            title = null,
            artistName = null,
            typeName = null,
            itemCount = null,
        )

        assertEquals("Untitled", release.title)
        assertEquals("Unknown Artist", release.artistName)
        assertEquals(ReleaseType.Album, release.type)
        assertEquals(0, release.itemCount)
    }

    @Test
    fun releaseTypesMapToProtocolValues() {
        assertEquals(ReleaseType.Album, TidalCatalogMapper.mapReleaseType("ALBUM"))
        assertEquals(ReleaseType.Ep, TidalCatalogMapper.mapReleaseType("EP"))
        assertEquals(ReleaseType.Single, TidalCatalogMapper.mapReleaseType("SINGLE"))
        assertEquals(ReleaseType.Album, TidalCatalogMapper.mapReleaseType(null))
    }

    @Test
    fun trackResponsesDoNotRequireMusicalAnalysisFields() {
        val body = """
            {
              "data": [{
                "id": "track-1",
                "type": "tracks",
                "attributes": {
                  "title": "A Song",
                  "duration": "PT3M5.5S",
                  "explicit": true
                },
                "relationships": {
                  "artists": {"data": [{"id": "artist-1", "type": "artists"}]},
                  "albums": {"data": [{"id": "album-1", "type": "albums"}]}
                }
              }],
              "included": [
                {"id": "artist-1", "type": "artists", "attributes": {"name": "An Artist"}},
                {"id": "album-1", "type": "albums", "attributes": {"title": "An Album"}}
              ]
            }
        """.trimIndent()

        val track = TidalCatalogMapper.mapTrackResourcesJson(listOf("track-1"), body).single()

        assertEquals("A Song", track.title)
        assertEquals("An Artist", track.artistName)
        assertEquals("An Album", track.albumTitle)
        assertEquals(185_500L, track.durationMs)
        assertEquals(true, track.explicit)
    }

    @Test
    fun searchMapsTrackResponsesWithoutKeyOrKeyScale() {
        val body = """
            {
              "data": [{
                "id": "search-1", "type": "searchResults",
                "relationships": {"tracks": {"data": [{"id": "track-1", "type": "tracks"}]}}
              }],
              "included": [{
                "id": "track-1",
                "type": "tracks",
                "attributes": {"title": "A Song", "duration": "PT1M", "explicit": false},
                "relationships": {}
              }]
            }
        """.trimIndent()

        val track = TidalCatalogMapper.mapSearchJson(body).tracks.single()

        assertEquals("track-1", track.id)
        assertEquals("A Song", track.title)
    }

    @Test
    fun searchSectionUsesItsRelationshipCursor() {
        val body = """
            {
              "data": [{
                "id": "search-1",
                "type": "searchResults",
                "relationships": {
                  "artists": {
                    "data": [{"id": "artist-1", "type": "artists"}],
                    "links": {
                      "next": "https://openapi.tidal.com/v2/searchResults/search-1/relationships/artists?page%5Bcursor%5D=next-artists"
                    }
                  }
                }
              }],
              "included": [{
                "id": "artist-1",
                "type": "artists",
                "attributes": {"name": "An Artist"}
              }]
            }
        """.trimIndent()

        val results = TidalCatalogMapper.mapSearchJson(body, SearchSection.Artists)

        assertEquals(listOf("An Artist"), results.artists.map { it.name })
        assertEquals(
            SearchContinuation("search-1", "next-artists"),
            SearchContinuation.decode(assertNotNull(results.nextCursor)),
        )
    }

    @Test
    fun artistHitsKeepRelationshipRankingAndExcludeUnrelatedIncludedArtists() {
        val body = """
            {
              "data": [{
                "id": "opaque-search", "type": "searchResults",
                "relationships": {"artists": {"data": [
                  {"id": "exact", "type": "artists"},
                  {"id": "other", "type": "artists"}
                ]}}
              }],
              "included": [
                {"id": "other", "type": "artists", "attributes": {"name": "Alphabetically First"}},
                {"id": "unrelated", "type": "artists", "attributes": {"name": "Enrichment Only"}},
                {"id": "exact", "type": "artists", "attributes": {"name": "Trevor Hall"}}
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf("exact", "other"),
            TidalCatalogMapper.mapSearchJson(body, SearchSection.Artists).artists.map { it.id },
        )
    }

    @Test
    fun songsRetainAllRankedHitsBeyondOldPreviewLimitAndUseRelatedMetadata() {
        val ids = (1..12).map { "track-$it" }
        val linkage = ids.joinToString(",") { """{"id":"$it","type":"tracks"}""" }
        val tracks = ids.reversed().joinToString(",") {
            """
                {"id":"$it","type":"tracks","attributes":{"title":"Song $it"},
                 "relationships":{"artists":{"data":[{"id":"artist","type":"artists"}]}}}
            """.trimIndent()
        }
        val body = """
            {
              "data": [{
                "id": "search-songs", "type": "searchResults",
                "relationships": {"tracks": {"data": [$linkage]}}
              }],
              "included": [
                $tracks,
                {"id":"artist","type":"artists","attributes":{"name":"Song Artist"}},
                {"id":"not-a-hit","type":"tracks","attributes":{"title":"Unrelated Song"}}
              ]
            }
        """.trimIndent()

        val results = TidalCatalogMapper.mapSearchJson(body, SearchSection.Songs)

        assertEquals(ids, results.tracks.map { it.id })
        assertEquals(List(12) { "Song Artist" }, results.tracks.map { it.artistName })
        assertEquals(emptyList(), results.artists)
    }

    @Test
    fun relationshipPageUsesPrimaryLinkageAndPreservesSearchIdentityAcrossPages() {
        val body = """
            {
              "data": [
                {"id":"second","type":"tracks"},
                {"id":"first","type":"tracks"}
              ],
              "links":{"next":{"href":"https://openapi.tidal.com/v2/searchResults/opaque/relationships/tracks?page%5Bcursor%5D=a%2Bb%2F%3D%2525"}},
              "included": [
                {"id":"first","type":"tracks","attributes":{"title":"First"}},
                {"id":"extra","type":"tracks","attributes":{"title":"Not a hit"}},
                {"id":"second","type":"tracks","attributes":{"title":"Second"}}
              ]
            }
        """.trimIndent()

        val results = TidalCatalogMapper.mapSearchJson(body, SearchSection.Songs, "opaque / search")

        assertEquals(listOf("second", "first"), results.tracks.map { it.id })
        assertEquals(
            SearchContinuation("opaque / search", "a+b/=%25"),
            SearchContinuation.decode(assertNotNull(results.nextCursor)),
        )
    }

    @Test
    fun emptyRelationshipDoesNotPromoteIncludedResourcesToHits() {
        val body = """
            {
              "data": [{
                "id":"search-empty","type":"searchResults",
                "relationships":{"artists":{"data":[]}}
              }],
              "included":[
                {"id":"artist","type":"artists","attributes":{"name":"Not a hit"}}
              ]
            }
        """.trimIndent()

        val results = TidalCatalogMapper.mapSearchJson(body, SearchSection.Artists)

        assertEquals(emptyList(), results.artists)
        assertNull(results.nextCursor)
    }

    @Test
    fun trackEnrichmentRetainsSearchOrderRatherThanResponseOrder() {
        val body = """
            {
              "data": [
                {"id":"other","type":"tracks","attributes":{"title":"Other Song"}},
                {"id":"exact","type":"tracks","attributes":{"title":"Everything is Music"},
                 "relationships":{"artists":{"data":[{"id":"artist","type":"artists"}]}}}
              ],
              "included":[
                {"id":"artist","type":"artists","attributes":{"name":"Tubby Love"}}
              ]
            }
        """.trimIndent()

        val tracks = TidalCatalogMapper.mapTrackResourcesJson(listOf("exact", "other"), body)

        assertEquals(listOf("exact", "other"), tracks.map { it.id })
        assertEquals("Everything is Music", tracks.first().title)
        assertEquals("Tubby Love", tracks.first().artistName)
    }
}
