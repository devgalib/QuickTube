# Architecture

Multi-module, feature-sliced Android project. Shared build setup lives in Gradle **convention plugins**,
so every module's build file is a few lines long.

```
build-logic/convention   quicktube.android.{application,library,library.compose,feature,room}
app                      Application, MainActivity, ShareActivity, NavHost, Koin wiring
core/
  model                  Plain data classes (MediaItem, DownloadTask, AppSettings…) — no Android deps
  common                 Formatting, URL parsing, LoadState
  designsystem           Theme (colors, typography), shared buttons/headers
  ui                     Shared composables: thumbnails, video cards/rows, empty/error states, dialogs
  navigation             Type-safe @Serializable routes shared by all features
  database               Room: downloads, sites, history, bookmarks, search history
  datastore              DataStore-backed settings
  extractor              MediaExtractor interface + NewPipe implementation, WebMediaFinder (FB/TikTok/X/OG tags)
  data                   Repository interfaces + implementations (the only thing features talk to)
  download               DownloadManager: queue, parallel ranged HTTP, Media3 merge/remux, foreground service
  media                  PlaybackService (Media3 session), PlayerController, chunked YouTube DataSource
  playerui               VideoPlayerBox, seek bar, speed menu, fullscreen helper
  downloadsheet          App-wide "Download video as" sheet (used by every screen + the share popup)
feature/
  home                   Download tab (Search / YouTube / Music / More) + How-to screens
  search                 Search + results
  video                  Video page, channel & playlist pages
  library                Play tab, Downloading screen
  player                 Full player + mini player
  browser                WebView browser with media sniffing, sites, history, saved pages
  settings               Settings + sub-screens
  tools                  Recycle bin, vault, status saver, cleaners, app uninstaller
```

## Rules that keep it scalable

- **Features never depend on other features.** They depend on `core:*` only. Cross-feature navigation goes through
  routes in `core:navigation` and callbacks wired in `app/navigation/AppNavHost.kt`.
- **Features depend on interfaces.** Repositories (`core:data`) and `DownloadManager` (`core:download`) are interfaces bound in Koin,
  so implementations can be swapped (another extractor, fakes for tests) without touching UI code.
- **One source of truth per concern.** Room holds downloads, DataStore holds settings, and `PlayerController` holds playback.
  Screens observe flows from these.
- **DI: Koin.** Each module exposes one Koin module, and the app lists them in `app/di/FeatureModules.kt`. Koin, Room,
  Navigation Compose and DataStore all support Kotlin Multiplatform, which keeps a future iOS port open (see below).

## Adding a feature

1. Create `feature/<name>/build.gradle.kts`:
   ```kotlin
   plugins { alias(libs.plugins.quicktube.android.feature) }
   ```
2. `include(":feature:<name>")` in `settings.gradle.kts`, and add it to `app/build.gradle.kts`.
3. Add a route to `core/navigation/Routes.kt`, a `composable<YourRoute>` in `AppNavHost`, and your Koin module to `featureModules`.

## Key flows

- **Download**: screen → `DownloadSheetController.open(url)` → `DownloadSheetViewModel` resolves formats
  (`MediaRepository` for YouTube etc., `WebMediaFinder` for other pages) → `DownloadManager.enqueue` → Room row →
  `DownloadEngine` worker (video + audio at once, each in 4 MB ranges over up to 8 connections, chunk map in `.part.chunks`,
  re-extract on expired links or unsavable codecs) → `MediaMerger` (Media3 extractors + MP4 muxer in-process, platform muxer for
  WebM / as fallback) on app-private storage → moved to the download folder → media scan → notification.
- **Batch download**: playlist/channel page → `BatchDownloader` (app-wide) resolves each video's formats a few at a time and enqueues
  the closest match to the chosen quality.
- **Share from another app**: `ShareActivity` (translucent, own task) → same sheet → finishes when the sheet closes.
- **Playback**: `PlayerController.play(items)` → `MediaController` → `PlaybackService` (ExoPlayer). Separate video+audio URLs
  are played with a `MergingMediaSource`. YouTube's CDN gets bounded range requests via `ChunkedHttpDataSource`, because open-ended reads are throttled.

## iOS / multiplatform path

The domain is already isolated: `core:model`, `core:common`, `core:data` interfaces, `core:database` (Room KMP) and
`core:datastore` (DataStore KMP) can move to `commonMain` with mostly mechanical changes, and the Compose UI can move to Compose
Multiplatform. Platform-specific pieces would need iOS counterparts: NewPipe Extractor (JVM-only), the download service, MediaMuxer,
the Media3 player and the WebView. Note that Apple doesn't allow YouTube downloading on the App Store.
