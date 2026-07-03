# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

NuvioTV is an Android TV media player (Kotlin + Jetpack Compose for TV) that acts as a
client for the Stremio addon ecosystem plus CloudStream-style plugins. It is leanback-only
(`android.software.leanback`, `LEANBACK_LAUNCHER`), focused on catalog browsing, source
resolution, and playback via Media3/ExoPlayer (with an optional libmpv path).

App namespace/applicationId: `com.nuvio.tv` (`playstore` flavor overrides to `com.nuvio.app`).

## Build & Test Commands

Gradle wrapper (`./gradlew`) is the entry point. There are two product flavors on the
`distribution` dimension — `full` (sideload/CI: plugins, in-app updates, in-app trailers
enabled) and `playstore` (those features disabled). Most build/test tasks are flavor- and
build-type-specific, so task names combine them (e.g. `assembleFullDebug`,
`testFullDebugUnitTest`).

```bash
# Compile / build the primary (full debug) variant
./gradlew :app:compileFullDebugKotlin
./gradlew :app:assembleFullDebug

# Unit tests (JVM) — run the matching variant's test task
./gradlew :app:testFullDebugUnitTest

# Run a single test class or method
./gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.ui.screens.player.PostPlayModeTest"
./gradlew :app:testFullDebugUnitTest --tests "*.PostPlayModeTest.someTestMethod"

# Lint
./gradlew :app:lintFullDebug

# Install + launch on a connected device/emulator
./gradlew :app:assembleFullDebug
adb shell am start -n com.nuviodebug.com/com.nuvio.tv.MainActivity
```

Note: the `debug` build type sets `isDebuggable = false` and is signed with the release
signing config, so a "debug" build behaves close to release. The debug `applicationId`
suffix yields `com.nuviodebug.com`.

### Performance / baseline profiles

`scripts/perf_baseline.sh` wraps the macrobenchmark + baseline-profile tasks
(`generate-profile`, `benchmark`, `release-with-profile`). These require a connected device.

### Release

`scripts/release_beta.py` bumps `versionName`/`versionCode` in `app/build.gradle.kts`,
builds the per-ABI release APKs, and prepares GitHub Release assets. CI release is
`.github/workflows/beta-release.yml`.

## Configuration / Secrets

Build config is injected from `local.properties` (release/shared) and `local.dev.properties`
(debug-only dev environment), falling back to environment variables. See
`local.example.properties`. Keys flow into `BuildConfig` fields (Supabase, Trakt, TMDB,
parental guide / introdb / trailer APIs, donations, Premiumize, etc.). When adding a new
external endpoint or key, wire it through `app/build.gradle.kts` `buildConfigField` rather
than hardcoding.

Optional native components are gated by properties:
- **DV7 / Dolby Vision Profile 7→8.1**: `DOVI_NATIVE_ENABLED`, `DOVI_ENABLE_REAL_LINK`,
  `DOVI_LIBDOVI_PREBUILT_ROOT` (prebuilt libdovi under `DV7/`). Native bridge is
  `app/src/main/cpp/dovi_bridge.cpp` built via CMake; only compiled when enabled.
- **ffmpeg downmix decoder** (`:ffmpeg-decoder-downmix` module): `USE_LOCAL_FFMPEG_DECODER`
  plus `FFMPEG_SOURCE_DIR`/`FFMPEG_BUILD_DIR`.

## Architecture

Single Android app module (`:app`) plus support modules `:baselineprofile` and
`:ffmpeg-decoder-downmix`. DI is Hilt (`@HiltAndroidApp NuvioApplication`, single
`MainActivity` hosting Compose). Code under `app/src/main/java/com/nuvio/tv/`:

- **`ui/`** — Compose UI. `ui/navigation/` (`Screen.kt` routes, `NuvioNavHost.kt`),
  `ui/screens/<feature>/` each typically a `*Screen` + `*ViewModel` + `*UiState`
  (MVVM, StateFlow), `ui/components/`, `ui/theme/`. TV Material3 + focus/remote navigation
  is a first-class concern (RTL key handling, drawer focus, etc.).
- **`domain/`** — `model/` domain types and `repository/` interfaces
  (`CatalogRepository`, `MetaRepository`, `StreamRepository`, `LibraryRepository`,
  `SubtitleRepository`, `SyncRepository`, `WatchProgressRepository`, `AddonRepository`).
- **`data/`** — `repository/` implementations (`*Impl`), `remote/` Retrofit services,
  `mapper/`, and **`local/` DataStore-based preference stores** (one `*DataStore` per
  feature area — settings, profiles, layout, library, watch progress caches, etc.).
- **`core/`** — cross-cutting subsystems: `network/`, `player/` (ExoPlayer/Media3
  integration, track selection), `streams/` (badge rules/presentation), `plugin/`,
  `debrid/`, `torrent/`, `trakt/`, `sync/` (Supabase-backed cloud sync), `cloud/`,
  `tmdb/`, `auth/`, `profile/` (multi-profile + PIN locking), `qr/` (TV QR sign-in),
  `recommendations/`, `server/`, `build/`, `di/` (Hilt modules: `NetworkModule`,
  `RepositoryModule`, `ProfileModule`, `TorrentModule`).

Data flow: installed Stremio addons / plugins provide catalogs + metadata; detail screens
trigger stream resolution; resolved sources play through the Media3 player. Trakt and a
Supabase backend provide scrobbling, watch progress, and cross-device sync.

### Flavor-specific source sets

`app/src/full/` contains the plugin/updater code excluded from `playstore`. Notably it
vendors a CloudStream-compatible plugin layer under
`app/src/full/java/com/lagradost/cloudstream3/` plus the in-app updater under
`com/nuvio/tv/updater/`. Feature flags (`FEATURE_PLUGINS_ENABLED`,
`FEATURE_IN_APP_UPDATES_ENABLED`, `FEATURE_IN_APP_TRAILERS_ENABLED`,
`FEATURE_EXTERNAL_TRAILERS_ENABLED`) are `BuildConfig` booleans set per flavor — gate
plugin/updater/trailer code behind them so the `playstore` flavor stays compliant.

### Experience modes

The app has an "Essential" vs "Advanced" experience mode (see `docs/essential-mode.md`,
`ExperienceModeDataStore`, `ExperienceModeSelectionScreen`). Essential mode hides advanced
settings/plugins/diagnostics but keeps every feature reachable; don't remove capabilities,
gate their visibility.

## Tests

Unit tests live in `app/src/test/` (JVM, JUnit) — ~57 classes, concentrated in player rules,
data-source routing (custom Media3 `androidx.media3.datasource` tests), and UI logic
helpers. Instrumented tests are in `app/src/androidTest/`. Many tests encode behavioral
rules (autoplay/post-play/still-watching gating, next-episode logic); prefer extending these
when changing playback behavior.

## Contribution Policy (important)

`CONTRIBUTING.md` enforces a strict, narrow PR policy during stabilization: only bug fixes
with a linked issue, translations, and small doc accuracy fixes are accepted. Do **not**
introduce new features, UI/cosmetic redesigns, refactors, dependency additions, or
architecture changes unless tied to a linked, approved issue. Keep changes minimal and
scoped to a single problem.
