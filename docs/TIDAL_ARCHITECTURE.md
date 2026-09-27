# TIDAL integration architecture

This document records the TIDAL Developer Platform constraints that shape Kelp. The canonical sources are the [Developer overview](https://developer.tidal.com/documentation/overview), the [OpenAPI reference](https://tidal-music.github.io/tidal-api-reference/tidal-api-oas.json), the [Android SDK](https://github.com/tidal-music/tidal-sdk-android), and TIDAL's [design guidelines](https://developer.tidal.com/documentation/guidelines/guidelines-design-guidelines).

## Boundaries

- The Light SDK owns the tool lifecycle, screen stack, system typography, monochrome UI primitives, LightOS icons, binder transport, and detached-audio capability declaration. Declaring that capability alone does not host a player.
- The official TIDAL Android Auth module owns developer-app OAuth PKCE credentials and refresh for catalog calls. Kelp requests credentials from its `CredentialsProvider` for API work; it never copies tokens into the app protocol or preferences. A second, phono-style first-party PKCE sign-in (`TidalStreamingAuth`) stores separate streaming tokens in a private SharedPreferences file on the server side only.
- The official TIDAL API module owns generated catalog calls. Kelp maps generated JSON:API resource objects into small protocol-owned models before they cross the Light binder. A narrow Retrofit adapter covers only the current Search route while the latest Android release still carries the preceding generated shape.
- Kelp resolves streams just-in-time against the private `playbackinfopostpaywall` endpoint with first-party credentials and plays them in `KelpPlaybackService`, a foreground-capable Media3 `MediaSessionService`. Developer-app tokens only resolve previews. The official TIDAL Player module is not used.
- The app module never imports TIDAL SDK types. The merged server module is the integration boundary and exposes typed, versioned Kelp RPC methods.

## Public API map

| Kelp feature | TIDAL resource | Scope | Access | Notes |
| --- | --- | --- | --- | --- |
| Saved albums | `userCollectionAlbums/me/relationships/items` | `collection.read` | Third party | Sort by `-addedAt`; documented include is `items`. |
| Saved artists | `userCollectionArtists/me/relationships/items` | `collection.read` | Third party | Sort by `name`; documented include is `items`. |
| Saved songs | `userCollectionTracks/me/relationships/items` | `collection.read` | Third party | Sort by `-addedAt`; documented include is `items`. |
| Search | `searchResults?filter[query]=…`; then `searchResults/{id}/relationships/{section}` | `search.read` | Third party | Android API 0.3.53 was generated from schema 1.10.86. Kelp's narrow adapter follows the current collection search and relationship-page contract. |
| Artist albums | `artists/{id}/relationships/albums` | none beyond valid credentials | Third party | Required include is `albums`. |
| Artist songs | `artists/{id}/relationships/tracks` | none beyond valid credentials | Third party | Requires `collapseBy`; include is `tracks`. |
| Album songs | `albums/{id}/relationships/items` | none beyond valid credentials | Third party | Include is `items`; Kelp enriches returned tracks with their artist and album relationships. |
| Playlist songs | `playlists/{id}/relationships/items` | none beyond valid credentials | Third party | Sort by `itemIndex`; include is `items`. |
| Home mixes | `userDailyMixes`, `userDiscoveryMixes`, `userNewReleaseMixes` | `recommendations.read` | Third party | Displayed with a short saved-playlists section on Home. |
| Continuous playback | `tracks/{id}/relationships/similarTracks` | none beyond valid credentials | Third party | Seed from the final selected track and append before the queue ends. |
| Stream resolution | `v1/tracks/{id}/playbackinfopostpaywall` (private `api.tidal.com/v1`) | first-party Android client (`r_usr w_usr w_sub`) | First party | `assetpresentation=FULL`; BTS progressive FLAC preferred, clear DASH sanitized for Media3. Requested at `LOSSLESS` with fallback to `HIGH`/`LOW`. |

TIDAL accepts `me` for the authenticated user's collection and mix resources. Pagination is cursor-based. Kelp keeps the opaque cursor and never derives page numbers.

Only documented include paths belong in requests. A relationship endpoint's include is relative to that endpoint's root: collection item endpoints use `items`, artist album relationships use `albums`, and artist track relationships use `tracks`. TIDAL can reject unsupported include, sort, or filter values with HTTP 400.

Search treats JSON:API `included` as an unordered resource lookup, never as a ranked result list. Hits follow typed relationship identifiers in upstream order and are not truncated to eight. Track enrichment preserves that order. The initial request sends the full query unchanged; subsequent requests use the returned opaque search-resource ID and the selected relationship's cursor. The app passes Kelp's opaque continuation through unchanged. Contract reference: [official TIDAL schema 1.10.133](https://raw.githubusercontent.com/tidal-music/tidal-sdk-web/72dab1bd87be745d043a027758a1947aecc4cf41/packages/api/bin/tidal-api-oas.json).

## Home

The public API does not expose a third-party playback-history resource, and `playQueues` is internal-access only. Kelp's Home therefore contains the public daily, discovery, and new-release mix resources plus a short saved-playlists section. The dedicated Playlists tab displays the saved playlist collection.

## Playback and quality

Stream entitlement is keyed on the OAuth client: tokens issued to registered developer apps only resolve 30-second previews, so Kelp performs a second PKCE sign-in with TIDAL's first-party Android client id (the clear hi-res tier; no Widevine CDM needed). The login screen chains both sign-ins on one WebView — the developer-app login for the catalog, then the first-party login for playback — and shared cookies usually carry the session into step two without re-entry.

`KelpPlaybackService` owns one ExoPlayer, its controller, and one MediaSession. It explicitly registers the session with `addSession`: Kelp's Binder RPCs do not create the MediaController connection that registers a session implicitly in Lightious's SDK audio path. MediaSessionService supplies foreground ownership and media notification updates; the published session exposes metadata and the real queue to system controllers. Screen hide/navigation does not release playback. Sign-out clears the queue and stops the service; service destruction releases the player and cancels its work. Polling an empty player does not start a service. The queue is in memory, not restored after process death or reboot; Android may reclaim an inactive service.

This follows the service/session ownership used by Lightious listen mode rather than using its `LightAudioPlayer` wrapper. The current wrapper cannot express Kelp's lazy signed-URL resolution, explicit DASH MIME type, repeat policy, or queue append/replacement. No SDK internals or additional app-side player are used. All Media3/TIDAL types stay in `:server`; `:app` still uses the pure playback protocol.

`TidalMediaSource` prepares an actual ExoPlayer playlist lazily. Only prepared sources resolve through `TidalStreamResolver`: base64 BTS manifests yield progressive signed URLs; clear DASH manifests are sanitized (Media3 rejects TIDAL's non-numeric `AdaptationSet group="main"`) into unique cache MPDs, deleted when their sources release. Source preparation and child timelines use ExoPlayer's playback looper, resolution uses IO, and canceled results cannot install a new source. Play/pause, seek, previous/next, repeat, and shuffle act on the same native queue from both Kelp and system controls.

Queue policy and ExoPlayer access are main-looper confined. Binder snapshot reads use an immutable volatile snapshot; only position is polled while audio is playing. Snapshots distinguish actual playback, desired play state, buffering, and user-safe failure. A CDN 401/403 gets one fresh resolution per selected track while preserving position and desired play state; further failures are visible and explicitly retryable. There is no unconditional retry/autoplay loop.

The Light SDK's `detached-audio` capability supplies foreground-media permissions; the unrestricted server manifest declares Kelp's actual media service and wake-lock permission. The generated SDK service is not instantiated. Media3 remains strictly pinned to 1.5.0 across app/server.

Continuous playback is queue policy, not a looping toggle. With repeat off, reaching the final queued song requests a bounded page of similar tracks, deduplicates it, and appends it without replacing active playback. Superseded queue requests and service destruction cancel that work. Disabling continuous playback stops at the chosen queue's end. Explicit-content filtering is not implemented.

## Offline downloads

Downloads are deferred and have no navigation destination or active settings. Offline media must not be presented as functional without a complete entitlement, license, renewal, and deletion flow. Kelp will not implement downloads by caching stream URLs or manifests.

## Errors and request pacing

- `400`: protocol/request mismatch; log the operation and status without credentials or response bodies.
- `401`: credentials expired; invalidate the local auth snapshot and require sign-in.
- `403`: the app or token lacks the required scope/access; do not automatically erase otherwise valid credentials.
- `429`: show a wait-and-retry message and avoid immediate fan-out retries.
- `5xx` and I/O failures: show a retriable network/service error.

Home must reuse already-loaded collection state. Personalized sections should load sequentially and cache successful responses for the screen session so tab changes do not create API bursts.

## Attribution and links

TIDAL's design guidelines require attribution when displaying TIDAL metadata and require links back to TIDAL content where applicable. Kelp will keep that attribution monochrome and text-first to fit LightOS. Before a public release, every artist, album, and track detail surface needs a compliant TIDAL attribution/link treatment; removing Kelp's own screen title does not remove this requirement.
