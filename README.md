<p align="center">
  <img src="docs/assets/icon.png" width="160" alt="Kelp">
</p>

<h1 align="center">Kelp</h1>

<p align="center">
  A small, e-ink-friendly TIDAL client for the Light Phone III.<br>
  Full-length streaming, monochrome UI, no clutter.
</p>

<p align="center">
  <img src="docs/screenshots/home.png" width="200" alt="Home">
  <img src="docs/screenshots/library.png" width="200" alt="Library">
  <img src="docs/screenshots/now-playing.png" width="200" alt="Now playing">
  <img src="docs/screenshots/settings.png" width="200" alt="Settings">
</p>

## What is it?

Kelp provides saved songs, albums, artists, playlists, daily mixes, and search in a monochrome interface. Kelp resolves full-length TIDAL streams with first-party credentials, using the approach of [phono](https://github.com/jonathancaudill/phono). The Light SDK owns the audio player and its background service.

**You need a paid TIDAL subscription.** Like phono, kelp never works around that and never will.

## Install

1. Grab the latest APK from [**Releases**](https://github.com/LooseWireDev/kelp/releases).
2. Enable USB debugging on your LP3 (developer options), then:
   ```bash
   adb install -r path/to/kelp-vX.Y.Z-vcN.apk
   ```
   Keep `-r` when upgrading an existing Kelp install so Android replaces the app while preserving its data. A separate old Tide entry is not replaced because Tide used a different package ID.
3. Open Kelp and sign in. The login runs in two steps back-to-back with the same TIDAL account (one for the catalog, one for playback) — complete them both.

Kelp is **sideload-only** today. It can't go through Light's hosted tool builder, because the builder ignores companion modules and doesn't allowlist the TIDAL SDK — so the TIDAL code lives in a runtime-only server module inside the APK. If that ever changes upstream, this section changes first.

## Features

- Saved songs, albums, artists, playlists (your whole collection, paginated)
- Home mixes (daily / discovery / new-release) with artist subtext
- Full catalog search preserving TIDAL relevance order, with complete results and per-section paging
- Queue with shuffle, repeat, seek, and "keep playing similar songs" continuous playback
- Foreground-service playback outside Kelp, with track metadata and system play/pause/previous/next controls
- Full-width navigation and transport touch targets, with Now Playing accessible from the top-left waveform
- LOSSLESS streaming by default (falls back to lower quality if needed)
- SDK-owned background playback, track transitions, and system media controls
- Follows LightOS theming and hardware-button navigation

Loading audio is distinct from playing; preparation failures remain visible in Now Playing. Returning from playback keeps loaded browse results. Settings exposes continuous playback and sign-out, with read-only information about the actual streaming policy. Nonfunctional quality, normalization, explicit-filter, and offline controls are not presented as working features.

Background playback is not persistent playback recovery: force-stop, reboot, or process loss clears the in-memory queue. Leaving Kelp does not stop an active session; signing out does.

## Build it yourself

Builds require JDK 17, Android SDK platform 36, and the companion Light SDK fork at `../light-sdk`. The fork must include both the `codex/kelp-official-sdk` compatibility changes and the `LightAudioQueueSessions` streaming API. Kelp and its companion SDK changes must ship together.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For release-quality builds (runs the tests, R8, signed, into `dist/`):

```bash
scripts/build-release.sh
```

You'll also want your own TIDAL developer credentials in `local.properties` (see `local.properties.example`) if you're hacking rather than installing releases.

Local builds default to one worker, two JVM-visible processors, a 1.5 GiB heap, and in-process Kotlin compilation, following Lightious's low-resource build setup. The playback migration was built against adjacent SDK `52fbc5a8aedbd3c4c88037580709e53540086229`; existing local SDK changes were preserved.

CI runs tests + assemble on every push; pushing a matching `v*` tag publishes a signed release automatically.

### Background playback

`detached-audio` declares the SDK media service and its required permissions. Kelp starts a service-owned streaming queue through `LightAudioQueueSessions`. The service resolves each track when needed and loads recommendations without a screen or controller.

Closing Kelp disconnects the UI without stopping playback. Reopening Kelp reads the existing queue and position. Sign-out clears the queue and cancels pending playback work. An abandoned paused session stops after 60 seconds. Queue restoration after service or process destruction is not supported.

The SDK owns Media3 and the DASH decoder dependency. Kelp no longer pins the older Media3 runtime used by its separate player.

For device verification, play several tracks with the screen off for at least 15 minutes. Verify automatic transitions, system pause/resume and next controls, reconnection, and sign-out. JVM tests cannot establish LightOS power-management behavior.

## Legal / disclaimers

Unofficial project, unaffiliated with TIDAL or Light. A paid TIDAL subscription is required for everything to work. If TIDAL rotates their first-party client id (you'll notice as playback suddenly failing), the fix procedure is documented in [docs/TIDAL_ARCHITECTURE.md](docs/TIDAL_ARCHITECTURE.md).

## More reading

- [docs/TIDAL_ARCHITECTURE.md](docs/TIDAL_ARCHITECTURE.md) — why the two-sign-in model exists, stream resolution, API map
- [docs/PRODUCT.md](docs/PRODUCT.md) — product decisions
- [AGENTS.md](AGENTS.md) — repo map and rules for working on the codebase

Apache-2.0.
