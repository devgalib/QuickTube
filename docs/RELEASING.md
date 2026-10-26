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
3. builds `assembleRelease` with R8, verifies the APK signature,
4. creates the `vX.Y.Z` tag and a GitHub Release with `QuickTube-vX.Y.Z.apk`, its `.sha256`, and notes generated from merged PRs.

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
