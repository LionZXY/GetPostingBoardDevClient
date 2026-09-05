# Publishing an Android release

The **Release Android app** workflow builds a signed, optimized APK, runs the automated tests and Android lint, verifies its signature, and attaches `posting-board.apk` and `SHA256SUMS.txt` to a GitHub release. The README download button always points to the latest release.

## One-time signing setup

Add these repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Use the same signing key for every release so Android can install updates over the existing app. Keep an independent, secure backup of the keystore and passwords. Keystore files are ignored by Git. The workflow restores the keystore only to the runner’s temporary directory and removes it after the build. Signing secrets are never committed to the repository; the workflow stops with a clear error if any are missing. The four secrets have been configured for `LionZXY/GetPostingBoardDevClient`.

## Release a version

Push a tag in the form `vMAJOR.MINOR.PATCH`, such as `v1.1.0`. Alternatively, run **Release Android app** manually and enter an existing version tag. The workflow checks out and verifies that exact tag, tests it, and publishes the release only if the checks and signature verification pass. It does not replace an existing release.

The tag sets Android’s `versionName`. `versionCode` is `major × 1,000,000 + minor × 1,000 + patch`; minor and patch must be below 1,000. Use increasing versions for updates. This workflow publishes stable releases; prerelease tags are rejected.

Local unsigned release build:

```sh
./gradlew :androidApp:assembleRelease -PappVersionName=1.1.0 -PappVersionCode=1001000
```

For a signed local build, provide `ANDROID_SIGNING_KEYSTORE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` through your secret manager/environment. Never commit their values.

The downloadable release APK uses your release certificate. Development APKs use a different debug certificate; Android requires uninstalling a debug build before installing the release build, which removes app data. Export your API key or preserve access to your linked account before switching.

## Signing backup for this installation

The release keystore is stored outside the repository at `~/.local/share/posting-board/signing/release.keystore`, with alias `posting-board-release`. Its password is in macOS Keychain under service **Posting Board Android release signing**, account **LionZXY/GetPostingBoardDevClient**. Back up both securely before moving to another machine. `BACKUP.txt` in the signing directory records these locations without containing the password.
