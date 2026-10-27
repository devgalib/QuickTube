# Releasing

Releases are fully automated and **only the maintainer can publish one**.

## Branches

| Branch | Who can push | What happens |
|---|---|---|
| `main` | Maintainer directly; everyone else via pull request (1 approving review, green CI) | CI builds and runs unit tests |
| `production` | **Maintainer only** (repository ruleset) | The **Release** workflow builds a signed APK and publishes a GitHub Release |

## Publishing a release

```bash
git push origin main:production
```

The workflow then:

1. names the version `<appVersionBase>.<run number>` (e.g. `1.0.7`, versionCode `7`). `appVersionBase` lives in `gradle.properties`;
   bump it (e.g. to `1.1`) for a feature release. Version codes always increase, so updates install over older versions.
2. restores the signing key and `google-services.json` from encrypted secrets (they never exist in the repository),
3. builds `assembleRelease` with R8 and resource shrinking, verifies every APK's signature,
4. creates the `vX.Y.Z` tag and a GitHub Release with one APK per CPU type (`QuickTube-vX.Y.Z-arm64-v8a.apk`,
   `QuickTube-vX.Y.Z-armeabi-v7a.apk`, ~35% smaller) plus the universal `QuickTube-vX.Y.Z.apk`, each with a `.sha256`, and
   notes generated from merged PRs. The universal APK and its checksum are uploaded last: versions up to 1.0.3 take the last
   `.apk` asset, so they keep updating correctly.

## How users get the update

Installed apps check `api.github.com/repos/devgalib/QuickTube/releases/latest` on launch (at most every 6 hours; release
builds only) and in Settings → Check for updates. When the tag's last number (the versionCode) is higher than theirs, they get an
"Update available" sheet with the release notes. **Update now** downloads the APK for the phone's CPU (the universal one if
there's no match), checks it against its `.sha256` asset and
the installed app's signing key, then installs it with Android's PackageInstaller. Users allow "install unknown apps" for
QuickTube once; Google Play Protect may also offer to scan the app the first time.

After the update, the downloaded APK is deleted (right away when Android announces the replaced package, otherwise on the next
start), like the Play Store does. Downloads, history and settings are untouched: they live in the app's database and settings,
which Android keeps across updates, and the database only migrates forward.

So a release only needs: tag `v<appVersionBase>.<versionCode>`, an `*.apk` asset and its `*.apk.sha256` (optionally per-ABI
`*-<abi>.apk` ones). The workflow does this.
Drafts and pre-releases are never offered.

Test against a fake release: build with `-PupdateApiUrl=http://localhost:8765/latest.json`, serve a release JSON plus the APK
and checksum from that folder with `python3 -m http.server 8765`, and run `adb reverse tcp:8765 tcp:8765`.

## Firebase

Project **`quicktube-app`** (Crashlytics + Analytics), Android apps `com.edureminder.quicktube` and `com.edureminder.quicktube.qa`.
Maintainers get the config with the Firebase CLI:

```bash
firebase apps:sdkconfig ANDROID --project quicktube-app -o app/google-services.json
```

`app/google-services.json` is git-ignored. Collection is off in debug builds and when users opt out (Settings → Privacy).

## Secrets

| Secret | Purpose |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | The release keystore (JKS), base64-encoded |
| `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` | Keystore credentials |
| `GOOGLE_SERVICES_JSON` | Firebase config (Crashlytics + Analytics). Optional: without it the release has no analytics |

Set or rotate them with:

```bash
scripts/setup-release-secrets.sh
```

It reads the keystore path and passwords from your local, git-ignored `local.properties` and `app/google-services.json`, and sends
them to GitHub with the `gh` CLI. Nothing is printed.

**Keep the keystore safe and backed up.** It's stored outside the repository. If it's lost, existing users can't update to new
releases and would have to uninstall first.

## Updating the README mockups

1. On a device, turn on a clean status bar (demo mode) and capture screens with no personal content (no Library list, no private links):

   ```bash
   adb shell settings put global sysui_demo_allowed 1
   adb shell am broadcast -a com.android.systemui.demo -e command enter
   adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
   adb exec-out screencap -p > docs/screenshots/raw/home.png
   ```

2. Render the framed mockups (needs Google Chrome and ImageMagick): `python3 scripts/make_mockups.py`
3. Turn demo mode off again: `adb shell am broadcast -a com.android.systemui.demo -e command exit`
