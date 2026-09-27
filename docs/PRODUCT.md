# Kelp product direction

Kelp is a focused TIDAL player for Light Phone III. Its job is to help someone choose music, start listening, and put the phone away. It is not a miniature version of TIDAL's promotional home feed.

## Design language

- **Palette:** LightOS ink (`#000000`), paper (`#FFFFFF`), dark-theme secondary (`#BBBBBB`), and light-theme secondary (`#666666`). Kelp follows the active LightOS theme and introduces no brand color.
- **Type:** the Light SDK's system typeface and type scale. Titles identify music; superfine text identifies sections and playback state.
- **Layout:** one reading column, generous tap rows, no cards, no album-art grid, and no hidden gestures.
- **Icons:** only icons bundled with the Light SDK. Kelp does not ship custom navigation glyphs.
- **Top bar:** no app or tab title on the main screen. The SDK waveform opens Queue at top-left; SDK Search and Settings icons sit together at top-right.

## Navigation

Five recurring destinations occupy the Light bottom bar as icon-only actions:

1. **Home** — SDK home icon; TIDAL Home mixes with artist subtext and a short saved-playlists section.
2. **Playlists** — SDK large-list icon; the user's saved playlists.
3. **Artists** — SDK contacts icon; saved artists lead to albums, EPs and singles, and songs.
4. **Albums** — SDK media icon; saved albums.
5. **Songs** — SDK circle icon; saved tracks.

Settings uses the top-right gear. Search is immediately to its left and opens grouped artist, album, EP/single, and song results. Queue uses the SDK audio-waveform icon at top-left. The selected bottom icon uses full ink; inactive icons use reduced opacity.

The five tabs and the full player's five transport actions use equal-width, full-height touch areas across the screen. Browsing has no mini-player: the top-left waveform opens Now Playing, leaving the space above the tabs for catalog content. Catalog titles use the smaller native subheading scale, while mix descriptions may span two lines. The full player uses bounded multiline metadata rather than a one-line marquee.

Search preserves the catalog's ranked relationship order and all returned hits, rather than treating enrichment metadata as search results or dropping hits after the first eight. Search continuations belong to the selected result category.

## Playback

The Now Playing screen should prioritize:

- track title and artist;
- elapsed and total time;
- previous, play/pause, and next;
- shuffle, repeat, and queue as secondary actions;
- truthful loading, playback, and failure states;
- a service-owned media session so playback continues outside Kelp and system transports control the same queue.

Album and playlist screens should put `PLAY` and `SHUFFLE` in the bottom action bar, followed by a numbered text track list. Artwork is optional context, never the primary navigation surface.

## Settings

Settings contains only implemented behavior:

- continuous playback: continue with similar music when the chosen queue ends, or stop at its end;
- streaming policy (read-only): lossless preferred, with High/Low fallback when unavailable;
- background playback and LightOS theme behavior (read-only);
- sign out, with pending/failure feedback and retry.

Wi-Fi/mobile quality selection, normalization, explicit-content filtering, and downloads are not currently applied by the player; their former preference-only controls were removed rather than implying they worked. No stored account data is cleared by that UI cleanup.

Following Lightious's lifecycle lessons, browse screens retain loaded results on return, reject superseded requests, and reset canceled pagination so a tab remains usable. Now Playing retains the current track through a temporary RPC failure instead of replacing the player with an empty screen.

## Delivery sequence

1. **Shell:** authentication, five-icon catalog navigation, search entry, persistent local playback settings, and Light-native styling.
2. **Catalog:** album details and tracks, playlists, artists, saved tracks, and live search results.
3. **Player:** TIDAL stream resolution, service-owned media queue, Now Playing, native system transports, and media-state reconnection. Per-network quality selection remains unimplemented.
4. **Offline:** downloads and storage management, only after online playback is reliable.

The current implementation includes the shell, paginated collection reads, artist/album/playlist details, grouped search, and basic online playback from every song list. Offline controls must only appear as active actions when their backing behavior is connected.
