# TIDAL integration architecture

This document records the TIDAL Developer Platform constraints that shape Kelp. The canonical sources are the [Developer overview](https://developer.tidal.com/documentation/overview), the [OpenAPI reference](https://tidal-music.github.io/tidal-api-reference/tidal-api-oas.json), the [Android SDK](https://github.com/tidal-music/tidal-sdk-android), and TIDAL's [design guidelines](https://developer.tidal.com/documentation/guidelines/guidelines-design-guidelines).

## Boundaries

- The Light SDK owns the tool lifecycle, UI primitives, binder transport, and detached audio service. Its service owns the player, queue, media session, and playback jobs.
- The official TIDAL Android Auth module owns developer-app OAuth PKCE credentials and refresh for catalog calls. Kelp requests credentials from its `CredentialsProvider` for API work; it never copies tokens into the app protocol or preferences. A second, phono-style first-party PKCE sign-in (`TidalStreamingAuth`) stores separate streaming tokens in a private SharedPreferences file on the server side only.
- The official TIDAL API module owns generated catalog calls. Kelp maps generated JSON:API resource objects into small protocol-owned models before they cross the Light binder. A narrow Retrofit adapter covers only the current Search route while the latest Android release still carries the preceding generated shape.
- Kelp resolves full-length streams through the private `playbackinfopostpaywall` endpoint with first-party credentials. Developer-app tokens only resolve previews. The SDK plays these sources. The official TIDAL Player module is not used.
- The app module never imports TIDAL SDK types. The merged server module is the integration boundary and exposes typed, versioned Kelp RPC methods.

## Public API map

| Kelp feature | TIDAL resource | Scope | Access | Notes |
| --- | --- | --- | --- | --- |
| Saved albums | `userCollectionAlbums/me/relationships/items` | `collection.read` | Third party | Sort by `-addedAt`; documented include is `items`. |
| Saved artists | `userCollectionArtists/me/relationships/items` | `collection.read` | Third party | Sort by `name`; documented include is `items`. |
| Saved songs | `userCollectionTracks/me/relationships/items` | `collection.read` | Third party | Sort by `-addedAt`; documented include is `items`. |
| Search | `searchResults?filter[query]=…` | `search.read` | Third party | Android API 0.3.53 was generated from schema 1.10.86 and still exposes `searchResults/{id}`. Kelp's narrow compatibility adapter implements the current 1.10.115 route and multi-resource response. |
| Artist albums | `artists/{id}/relationships/albums` | none beyond valid credentials | Third party | Required include is `albums`. |
| Artist songs | `artists/{id}/relationships/tracks` | none beyond valid credentials | Third party | Requires `collapseBy`; include is `tracks`. |
| Album songs | `albums/{id}/relationships/items` | none beyond valid credentials | Third party | Include is `items`; Kelp enriches returned tracks with their artist and album relationships. |
| Playlist songs | `playlists/{id}/relationships/items` | none beyond valid credentials | Third party | Sort by `itemIndex`; include is `items`. |
| Home mixes | `userDailyMixes`, `userDiscoveryMixes`, `userNewReleaseMixes` | `recommendations.read` | Third party | Displayed with a short saved-playlists section on Home. |
| Continuous playback | `tracks/{id}/relationships/similarTracks` | none beyond valid credentials | Third party | Seed from the final selected track and append before the queue ends. |
| Stream resolution | `v1/tracks/{id}/playbackinfopostpaywall` (private `api.tidal.com/v1`) | first-party Android client (`r_usr w_usr w_sub`) | First party | `assetpresentation=FULL`; BTS progressive FLAC preferred, clear DASH sanitized for Media3. Requested at `LOSSLESS` with fallback to `HIGH`/`LOW`. |

TIDAL accepts `me` for the authenticated user's collection and mix resources. Pagination is cursor-based. Kelp keeps the opaque cursor and never derives page numbers.

Only documented include paths belong in requests. A relationship endpoint's include is relative to that endpoint's root: collection item endpoints use `items`, artist album relationships use `albums`, and artist track relationships use `tracks`. TIDAL can reject unsupported include, sort, or filter values with HTTP 400.

## Home

The public API does not expose a third-party playback-history resource, and `playQueues` is internal-access only. Kelp's Home therefore contains the public daily, discovery, and new-release mix resources plus a short saved-playlists section. The dedicated Playlists tab displays the saved playlist collection.

## Playback and quality

Stream entitlement depends on the OAuth client. Developer-app tokens only resolve 30-second previews. Kelp therefore uses a second PKCE sign-in with TIDAL's first-party Android client ID. This client supports clear streams without a Widevine CDM. The login screen chains catalog and playback sign-ins in one WebView.

`TidalStreamResolver` resolves each track when the SDK requests it. BTS manifests provide signed progressive URLs. Clear DASH manifests become local MPD files after invalid `group="main"` attributes are removed. Kelp supplies the file and its MIME type to the SDK. The SDK provides the DASH decoder.

`KelpPlayerController` is a server-side adapter to `LightAudioQueueSessions`, not an audio player. The SDK service owns resolution jobs, shuffle, repeat, seek, source-error recovery, and queue transitions. A continuation callback asks Kelp's catalog for similar tracks. Neither callback depends on a screen ViewModel.

The SDK publishes the logical queue and track metadata through a platform media session. Temporary controllers release after commands, but the started foreground service retains playback. During source resolution, the session reports buffering and holds a partial wake lock. ExoPlayer manages playback wake locks and audio focus.

The `detached-audio` capability generates the media service, foreground-service permissions, and wake-lock permission. The declaration alone does not attach another player to that service.

Sign-out stops the SDK queue before Kelp clears either authentication session. Generation checks reject stale resolution and recommendation results. Reopening the UI reads the live SDK snapshot. An abandoned paused service stops after 60 seconds. Queue state does not survive service or process destruction.

Continuous playback is queue policy, not a looping toggle. At the end of the chosen queue, the SDK asks Kelp for `similarTracks` based on the last seed track. Duplicate tracks are removed before the returned page extends the queue.

## Offline downloads

Downloads are deferred and have no navigation destination. The download-quality preference remains staged for future offline support, but offline media must not be presented as functional until the public Android Player/DRM APIs support a complete third-party download, license, renewal, and deletion flow. Kelp will not implement downloads by caching stream URLs or manifests.

## Errors and request pacing

- `400`: protocol/request mismatch; log the operation and status without credentials or response bodies.
- `401`: credentials expired; invalidate the local auth snapshot and require sign-in.
- `403`: the app or token lacks the required scope/access; do not automatically erase otherwise valid credentials.
- `429`: show a wait-and-retry message and avoid immediate fan-out retries.
- `5xx` and I/O failures: show a retriable network/service error.

Home must reuse already-loaded collection state. Personalized sections should load sequentially and cache successful responses for the screen session so tab changes do not create API bursts.

## Attribution and links

TIDAL's design guidelines require attribution when displaying TIDAL metadata and require links back to TIDAL content where applicable. Kelp will keep that attribution monochrome and text-first to fit LightOS. Before a public release, every artist, album, and track detail surface needs a compliant TIDAL attribution/link treatment; removing Kelp's own screen title does not remove this requirement.
