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

1. **Home** — SDK home icon; TIDAL Home mixes and a short saved-playlists section.
2. **Playlists** — SDK large-list icon; the user's saved playlists.
3. **Artists** — SDK contacts icon; saved artists lead to albums, EPs and singles, and songs.
4. **Albums** — SDK media icon; saved albums.
5. **Songs** — SDK circle icon; saved tracks.

Settings uses the top-right gear. Search is immediately to its left and opens grouped artist, album, EP/single, and song results. Queue uses the SDK audio-waveform icon at top-left. The selected bottom icon uses full ink; inactive icons use reduced opacity.

## Playback

The Now Playing screen should prioritize:

- track title and artist;
- elapsed and total time;
- previous, play/pause, and next;
- shuffle, repeat, and queue as secondary actions;
- a clear quality label when the stream is lossless or hi-res;
- detached Light SDK audio so playback continues outside Kelp.

Album and playlist screens should put `PLAY` and `SHUFFLE` in the bottom action bar, followed by a numbered text track list. Artwork is optional context, never the primary navigation surface.

## Settings

Playback preferences are plain rows with visible values:

- Wi-Fi quality: Low, High, Lossless, or Max;
- mobile quality: Low, High, Lossless, or Max;
- download quality: Low, High, Lossless, or Max;
- volume normalization: on or off;
- explicit content: allowed or hidden;
- continuous playback: continue with similar music when the chosen queue ends;
- background playback: always continues through the SDK's detached player;
- theme: always follows LightOS.

Future settings include download-over-Wi-Fi-only, storage usage, crossfade if the TIDAL and Light audio layers support it reliably, sign out, and clear playback history.

## Delivery sequence

1. **Shell:** authentication, five-icon catalog navigation, search entry, persistent local playback settings, and Light-native styling.
2. **Catalog:** album details and tracks, playlists, artists, saved tracks, and live search results.
3. **Player:** TIDAL stream resolution through an SDK-owned detached queue, Now Playing, queue controls, and media-state reconnection. Quality application remains separate work.
4. **Offline:** downloads and storage management, only after online playback is reliable.

The current implementation includes the shell, paginated collection reads, artist/album/playlist details, grouped search, and SDK-owned online playback from song lists. The audio service owns background track transitions and continuous playback. Offline controls must only appear as active actions when their backing behavior is connected.
