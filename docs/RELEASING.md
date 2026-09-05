# Publishing an Android release

The **Release Android app** workflow runs on every branch push. It builds a signed, optimized APK, runs the automated tests and Android lint, verifies its signature, and attaches `posting-board.apk` and `SHA256SUMS.txt` to a GitHub release. Builds from the default branch (`main`) supply the README's latest download; other branches publish prereleases.

## One-time signing setup

Add these repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Use the same signing key for every release so Android can install updates over the existing app. Keep an independent, secure backup of the keystore and passwords. Keystore files are ignored by Git. The workflow restores the keystore only to the runner’s temporary directory and removes it after the build. Signing secrets are never committed to the repository; the workflow stops with a clear error if any are missing. The four secrets have been configured for `LionZXY/GetPostingBoardDevClient`.

## Automatic release versions

Push your changes. No manual version tag is needed. The workflow checks out the pushed commit and creates a release tag for that exact commit after tests, lint, and APK signature verification succeed. You can also run **Release Android app** manually on a selected branch. Tag pushes and branch deletions do not start release builds.

The build number is GitHub's [`github.run_number`](https://docs.github.com/en/actions/reference/workflows-and-actions/variables): it increases for each run of this workflow and stays the same on a rerun. Build **N** produces tag **v1.1.N**, Android `versionName` **1.1.N**, and `versionCode` **1,001,000 + N**. The offset preserves upgrades from the initial signed APK. Keep this workflow and the offset when changing the displayed major/minor version so Android version codes continue increasing.

Reruns reuse their version and leave already-published downloads intact. An interrupted draft upload can be completed by rerunning the workflow. Every push keeps its own build; new pushes do not cancel older builds. GitHub selects the latest stable release by release date and semantic version, so prereleases and slower older builds do not replace the latest download.

The originally requested [Build Number action](https://github.com/marketplace/actions/build-number) depends on an unavailable Heroku service (HTTP 404, checked September 5, 2026). GitHub's built-in counter provides the numbering without that external service.

Local unsigned release build:

```sh
./gradlew :androidApp:assembleRelease -PappVersionName=1.1.0 -PappVersionCode=1001000
```

For a signed local build, provide `ANDROID_SIGNING_KEYSTORE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` through your secret manager/environment. Never commit their values.

The downloadable release APK uses your release certificate. Development APKs use a different debug certificate; Android requires uninstalling a debug build before installing the release build, which removes app data. Export your API key or preserve access to your linked account before switching.

## Signing backup for this installation

The release keystore is stored outside the repository at `~/.local/share/posting-board/signing/release.keystore`, with alias `posting-board-release`. Its password is in macOS Keychain under service **Posting Board Android release signing**, account **LionZXY/GetPostingBoardDevClient**. Back up both securely before moving to another machine. `BACKUP.txt` in the signing directory records these locations without containing the password.
