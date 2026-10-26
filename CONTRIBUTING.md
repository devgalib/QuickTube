# Contributing to QuickTube

Thanks for helping! This guide covers how to set up the project, how changes get in, and what we look for in a pull request.

## Ground rules

- Be kind and constructive. See the [Code of Conduct](CODE_OF_CONDUCT.md).
- **All changes go through pull requests to `main`.** Nobody pushes to `main` directly except the maintainer, and only the maintainer can
  publish releases (`production` branch).
- One topic per pull request. Small PRs get reviewed much faster.
- Don't add support for downloading DRM-protected content, and don't post links to piracy, adult or illegal content in issues or PRs.

## Getting started

Requirements: Android Studio (latest stable) or JDK 21+, Android SDK with API 37.

```bash
git clone https://github.com/devgalib/QuickTube.git
cd QuickTube
./gradlew :app:installDebug      # build and install on a connected device
./gradlew testDebugUnitTest       # unit tests
```

You **don't** need `google-services.json` or a signing key. Without them the app builds with analytics turned off,
and debug builds use the standard debug key.

## Workflow

1. Open (or pick) an issue and say you're working on it, so work isn't duplicated.
2. Fork the repo and create a branch from `main`: `feature/short-name` or `fix/short-name`.
3. Make your change, with tests where it makes sense (parsers, resolvers and other pure logic should always have unit tests).
4. Run `./gradlew :app:assembleDebug testDebugUnitTest` before pushing.
5. Open a pull request against `main` and fill in the template. CI must be green.
6. The maintainer reviews it. Address review comments by pushing more commits; we squash on merge.

### Commit messages

Use short, imperative subjects: `Add HLS audio rendition support`, `Fix crash when vault is empty`.
Reference issues in the body (`Fixes #42`).

## Project structure

The app is multi-module (`core/*`, `feature/*`, `app`). Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) before larger changes. Key rules:

- **Features never depend on other features.** Shared code goes into a `core` module; navigation between features goes through
  `core:navigation` routes and the wiring in `app/`.
- **Features talk to interfaces** (`MediaRepository`, `DownloadManager`, `Analytics`…), never to concrete implementations or SDKs.
- UI is Jetpack Compose with the design system in `core:designsystem` (`QtIcons`, `pressable()`, `QtCard`, etc.). Reuse it
  instead of adding one-off styles.
- Keep work off the main thread; prefer animating `graphicsLayer` properties and reading fast-changing state in draw/layout lambdas.

### Adding site support

1. If the site's pages declare their video (quality lists, JSON-LD, player configs), extend `PageMediaParser` and add a unit test with a
   **minimal synthetic HTML snippet** (never real page dumps or real media URLs).
2. Otherwise, site support usually belongs upstream in [yt-dlp](https://github.com/yt-dlp/yt-dlp); the app picks up its updates daily.

## Code style

- Kotlin official style (`kotlin.code.style=official`) with the settings in `.editorconfig`.
- Match the surrounding code; comment the *why*, not the *what*.
- No new libraries without discussing them in an issue first (APK size and licensing matter: the app is GPL-3.0).

## Reporting bugs and security issues

- Bugs and feature ideas: use the issue forms.
- Security problems: **don't open a public issue**; see [SECURITY.md](SECURITY.md).

## License

By contributing, you agree that your contributions are licensed under the [GPL-3.0-or-later](LICENSE) license.
