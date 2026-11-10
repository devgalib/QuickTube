# QuickTube for desktop (Linux, Windows, macOS)

The desktop app lives in `desktopApp/` and is built with **Compose Multiplatform** on the JVM. It shares the core
modules and the Home, Search, Bulk and download-sheet screens with the Android app — one codebase, two apps.

## Run it

```bash
./gradlew :desktopApp:run
```

Needs, on the computer: **ffmpeg** (merging HD video + audio, MP3/FLAC, cuts, HLS) and **VLC 3** (playback through
libVLC). yt-dlp is downloaded into the app's data folder on first use and kept up to date.

| OS | Install the tools |
|---|---|
| Fedora | `sudo dnf install ffmpeg vlc` (RPM Fusion) |
| Ubuntu / Mint / Debian | `sudo apt install ffmpeg vlc` |
| Arch / Manjaro | `sudo pacman -S ffmpeg vlc` |
| Windows | ffmpeg is bundled in the installer; install VLC from videolan.org |

## Build installers

```bash
./gradlew :desktopApp:createDistributable   # app folder with its own Java runtime (build/compose/binaries/main/app)
./gradlew :desktopApp:packageDeb            # Ubuntu, Debian, Mint (needs dpkg)
./gradlew :desktopApp:packageRpm            # Fedora, openSUSE (needs rpm-build)
./gradlew :desktopApp:packageMsi            # Windows (run on Windows)
```

Each format has to be built on its own OS, so `.github/workflows/desktop.yml` builds them on GitHub's Linux and
Windows machines (Actions → Desktop → Run workflow, or push a `desktop-v1.0.0` tag to attach them to a release).
Packaging uses JDK 21 (Gradle's toolchain), because the IDE's bundled runtime has no `jpackage`.

Publishing: GitHub Releases (all formats), Flathub (every Linux distro), the AUR (`quicktube-bin` pointing at the
release's `.deb`), and winget for Windows.

## How the code is shared

```
core/model, common, navigation, designsystem, ui      commonMain (Android + desktop)
core/database (Room KMP), datastore (DataStore KMP)    commonMain; platform files open the database/settings
core/extractor (NewPipe), data, downloadsheet          commonMain
core/download     DownloadManager interface in commonMain; androidMain = the Android engine (service, Media3);
                  desktopMain = DesktopDownloadEngine (4 MB ranged parallel HTTP, ffmpeg for merge/convert/HLS/cuts)
core/ytdlp        androidMain = bundled Python yt-dlp; desktopMain = official standalone yt-dlp binary
core/analytics    androidMain = Firebase; desktopMain = no analytics
feature/home, feature/search                           commonMain (the same screens on both)
feature/video, library, player, settings, browser, tools, gallery, core/media, core/playerui
                  Android only (Media3, WebView, MediaStore…); the desktop app has its own video page, library,
                  player (libVLC) and settings in desktopApp/
```

Both platforms are JVMs, so `commonMain` may use JDK APIs (`java.io.File`, `java.net`). Android-only calls go behind
`expect`/`actual` (e.g. `QtLog`, `MediaProbe`, `DeviceCodecs`, `rememberToaster`, `rememberStorageAccess`,
`QtBackHandler`, `rememberClipboardReader`, `rememberTextFilePicker`) or an interface bound in each platform's Koin
module (`platformDataModule`, `downloadModule`, `ytDlpModule`, `analyticsModule`).

Desktop files: database and settings in `~/.local/share/QuickTube` (Linux), `%APPDATA%\QuickTube` (Windows),
`~/Library/Application Support/QuickTube` (macOS); downloads in `~/Downloads/QuickTube`.

## Responsive layout

The same rules apply on phones, tablets and desktop windows:

- `WidthClass.of(width)` → `COMPACT` (< 600dp, phones), `MEDIUM` (< 840dp), `EXPANDED`; provided as `LocalWidthClass`.
- Wide windows get `QtNavRail` (side navigation, labels shown when `EXPANDED`); narrow ones the bottom bar.
- Lists of rows and settings use `Modifier.readableWidth()` (centred, at most ~760dp); feeds and libraries use
  `GridCells.Adaptive(300–340dp)` so cards gain columns as the window grows.
- The desktop video page shows the player and details beside "Up next" from 980dp; narrower, they stack and the
  player never takes more than about two thirds of the height.

## Testing layouts without a window

The packaged app can render itself off-screen and save a picture, at any size:

```bash
QT_SNAPSHOT=/tmp/home.png QT_SIZE=400x820 QT_START=youtube \
  desktopApp/build/compose/binaries/main/app/QuickTube/bin/QuickTube
```

`QT_START`: `video:<url>`, `search:<query>`, `channel:<url>`, `sheet:<url>`, `library`, `settings`, `youtube`,
`music`, and `fetch:<url>` / `fetch-audio:<url>` (downloads that video at about 720p / its best audio — an end-to-end
download test). Playback is muted in this mode.

## Keyboard

Space/K play-pause, ←/→ 5 s, J/L 10 s, ↑/↓ volume, M mute, F or double-click full screen, F11 full screen, Esc back
(or leave full screen), Ctrl+L search.
