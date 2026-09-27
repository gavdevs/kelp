# Kelp – agent notes

Light Phone III TIDAL client built on `lightphone/light-sdk` (composite build from `../light-sdk`, branch `codex/kelp-official-sdk`, based on official v0.1.1). Kotlin + Compose throughout.

## Module map

| Module | Kind | Owns | Key files |
| --- | --- | --- | --- |
| `:app` | Light SDK tool module | Light UI screens, `KelpClient` binder RPC client | `app/src/main/kotlin/com/loosewire/kelp/*.kt`, `app/lighttool.toml` |
| `:protocol` | Pure Kotlin/JVM | Serializable RPC contracts + domain models (`KelpRemoteMethod`, `AuthSnapshot`, `TrackSummary`, `PlaybackSnapshot`) | `protocol/src/main/kotlin/com/loosewire/kelp/protocol/` |
| `:server` | Unrestricted Android library, merged runtime-only into the APK | Everything TIDAL: catalog API, auth sessions, playback, RPC dispatch | `server/src/main/kotlin/com/loosewire/kelp/server/` |

Distribution lane: **sideload-only, experimental/local.** Not eligible for Light's hosted builder (it ignores sibling modules and doesn't allowlist the TIDAL SDK). Never describe an APK as builder/distribution compatible — the hosted-builder compat story is documented in README "Light SDK status".

## Invariants — read before editing

- **`:app` never imports TIDAL SDK, okhttp, Media3, or `:server` types.** Everything crosses the Light binder via `KelpRemoteMethod` IDs (`com.loosewire.kelp.*`). Add a feature by extending `:protocol` contracts, dispatch in `KelpServiceMethods`, and a client method in `KelpClient.kt`.
- **`:protocol` stays Android/TIDAL-free.** Serializable models only, no platform imports.
- **Two TIDAL sessions** (`KelpRuntime`): developer-app auth3 login for the v2 Public API (catalog) + `TidalStreamingAuth` first-party PKCE for playback. Catalog endpoints only accept the developer token; full-length streams only resolve with the first-party token. Do not collapse them.
- **Playback is service-owned**: `KelpPlaybackService` owns the MediaSession and `KelpPlayerController`/ExoPlayer. It explicitly calls `addSession` because Binder RPCs start playback without a MediaController connection. `TidalMediaSource` lazily resolves BTS/clear-DASH through `TidalStreamResolver`. Never create a second player from a screen or reintroduce the official TIDAL Player SDK.
- **Playback threading**: queue mutations and ExoPlayer calls are main-looper confined; Binder reads an immutable `@Volatile` snapshot. MediaSource lifecycle uses ExoPlayer's playback looper and resolution runs on IO. Cancellation must prevent stale stream/continuous-queue results; screen hide must not release playback.
- **Media3 is strict-pinned to 1.5.0** in both `app` and `server` (Light SDK resolves a newer version). Keep both pins in sync.
- **Only implemented settings**: continuous playback is applied at queue start. Quality selection, normalization, explicit filtering, and offline playback are not implemented; do not reintroduce preference-only controls.
- **`lighttool.toml` is the identity source** (app id, version, capabilities, `serverPackage = "com.loosewire.kelp"`). Do not hand-write what the plugin generates.
- **Secrets**: `local.properties` holds the developer-app client id and is git-ignored. Never embed it. The first-party client id in `TidalStreamingAuth` is intentionally embedded (public, shared with orpheusdl/phono; rotation procedure in `docs/TIDAL_ARCHITECTURE.md` and README).

## Building & verifying

JDK 17 and an Android SDK (platform 36) are required; this machine may only have them at `/tmp/kelp-jdk17` and `/tmp/kelp-android-sdk`:

```bash
JAVA_HOME=/tmp/kelp-jdk17 ANDROID_HOME=/tmp/kelp-android-sdk ./gradlew :server:assembleDebug :app:assembleDebug
scripts/build-release.sh          # tests + minified release APK in dist/ (handles env detection)
```

Verification for any change: the narrowest useful unit tests first (`:protocol:test`, `:app:testDebugUnitTest`, `:server:testDebugUnitTest`), then `:app:assembleDebug`; run `:app:assembleRelease` for anything touching server code (R8 path) — or just run `scripts/build-release.sh`. Install: `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`; pick an explicit serial when devices are ambiguous.

## CI / releases

- `.github/workflows/build.yml`: tests + debug assemble on push/PR. CI checks out the companion `LooseWireDev/light-sdk` (branch `codex/kelp-official-sdk`) next to the repo; locally that checkout must sit at `../light-sdk` (override with `-Ptide.sdkPath=...`).
- `.github/workflows/release.yml`: tag-triggered (`v*`) signed release. Tag must match `versionName` in `app/lighttool.toml`; bump `versionCode` each release. Signing uses Actions secrets `TIDE_RELEASE_*`; the keystore backup lives at `~/.tide-keys/tide-release.jks` (storepass in `tide-release.storepass`, 600), outside the repo.
- GitHub Release APKs are signed with the release key; dev-key-signed installs must be uninstalled first (signature mismatch on upgrade).

Emulator/device work follows the `lightos-emulator` skill (never run system-partition commands on retail hardware; none are needed here).

## Commits & style

- Lowercase conventional commits (`feat:`, `chore:`, `docs:`), one logical change per commit, matching `git log`.
- Existing code style: no trailing braces-only lines, `java.util.logging` in server, protocol errors mapped to `KelpError` with user-safe messages.
- Docs that must stay truthful: `README.md` (setup/lane/deps), `docs/TIDAL_ARCHITECTURE.md` (TIDAL decisions), `docs/PRODUCT.md`.
