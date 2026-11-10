# Agent & maintainer rules (QuickTube)

These rules apply to AI coding agents (Claude Code, Copilot, etc.) and humans working in this repository.

## Git

- **"push" / "push code" / "push to GitHub"** → commit the work and push to **`main`** (the default branch).
- Android and desktop are released separately; do only the one that was asked for.
  - **"release android" / "push to production"** → make sure `main` is pushed and green, then publish it with
    `git push origin main:production`. This triggers `.github/workflows/release.yml`, which builds the signed APKs and
    creates the `vX.Y.Z` GitHub Release marked Latest, which the Android in-app updater reads (see
    [docs/RELEASING.md](docs/RELEASING.md)).
  - **"release desktop"** → make sure `main` is pushed and green, then tag it `desktop-v<appVersionBase>.<n>` (`n` is one
    more than the newest `desktop-v*` tag, starting at 1) and push only that tag: `git tag desktop-v1.0.1 main &&
    git push origin desktop-v1.0.1`. This triggers `.github/workflows/desktop.yml`, which builds the Linux and Windows
    installers and attaches them to that tag's GitHub Release. It's never marked Latest, so the Android updater ignores it.
    Don't touch `production` for it.
  - **"release" alone** → ask whether Android, desktop or both. **"release both"** → Android first, then desktop.
- Only touch `production` (Android) or push a `desktop-v*` tag when the maintainer explicitly asks for that release.
  Never force-push `production`, and never move or delete a pushed release tag.
- Contributors (anyone but the maintainer) work on a branch in their fork and open a pull request to `main`.
- Commit messages: short imperative subject, details in the body.

## Never commit

`local.properties`, `keystore.properties`, keystores (`*.jks`, `*.keystore`), `google-services.json`, service-account JSON keys,
`.env` files, or any password, key or token. They're in `.gitignore`. Before every commit, check `git status` for anything secret.
Release secrets live only in GitHub Actions secrets and on the maintainer's machine.

## Code

- Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) first. Features never depend on other features; shared code goes in `core/*`.
- Use the design system (`core:designsystem`: `QtIcons`, `pressable()`, `QtCard`, `PillButton`, `LocalBottomBarHeight`…).
- Analytics: only coarse, anonymous events through the `Analytics` interface. Never log URLs, titles, search terms or file
  names. Download events may carry the site's domain only (`DownloadStats.site`, e.g. "tiktok.com"), the country and a short
  reason code (`DownloadStats.reason` / `findReason`). Download failures are events, never Crashlytics reports.
- Before pushing: `./gradlew :app:assembleDebug testDebugUnitTest testAndroidHostTest desktopTest :desktopApp:compileKotlinDesktop` must pass.
- Shared code: modules with `src/commonMain` are Kotlin Multiplatform (Android + desktop). Put Android-only code in
  `androidMain` and desktop-only code in `desktopMain` behind `expect`/`actual` or an interface bound in Koin; never
  import `android.*` in `commonMain`. See [docs/DESKTOP.md](docs/DESKTOP.md).
- Layouts must work from a small phone to a wide desktop window: use `LocalWidthClass`, `readableWidth()`,
  adaptive grids (`GridCells.Adaptive`) and `QtNavRail` from `core:ui` rather than fixed column counts or widths.
- On-device tests keep the app installed (`android.injected.androidTest.leaveApksInstalledAfterRun=true`) so user data isn't wiped.
