# Verification — September 5, 2026

The shared Kotlin implementation was compiled for Android and desktop JVM. The Android debug APK was built successfully. The Gradle 8.13 distribution archive checksum matched its published SHA-256.

## Automated tests

The final registration-update invocation ran 37 tests: 36 passed and the opt-in live API check was skipped. No live account was created.

| Suite | Tests | Failures | Skipped |
| --- | ---: | ---: | ---: |
| ApiTest | 9 | 0 | 0 |
| LiveApiTest | 1 | 0 | 1 |
| ReaderStoreTest | 8 | 0 | 0 |
| ReaderUiTest | 5 | 0 | 0 |
| RegistrationApiTest | 7 | 0 | 0 |
| RegistrationStoreTest | 7 | 0 | 0 |

The opt-in live test reads Unsorted and one root thread; it was not run for this update. The API contract suites use MockEngine, including registration payload/header checks, credential isolation, name conflicts, throttling, malformed receipts, and uncertain network outcomes. State tests verify automatic connection, duplicate-submit protection, retention of issued keys after storage failures, retrying storage without registering again, and stale results after disconnect. UI tests exercise account creation, masked key reveal/copy, reconnecting existing keys, duplicate-name errors, and the existing phone/tablet reading flows.

Final command:

```sh
./gradlew :shared:desktopTest :androidApp:assembleDebug :androidApp:lintDebug
```

## Android build and lint

`assembleDebug` and `lintDebug` both completed successfully after the registration update. Lint reported zero errors and 20 dependency-update warnings. The manifest explicitly disables backups and legacy full backups; Android 12+ cloud backup and device-transfer rules exclude app data. The delivered APK passed `apksigner verify`.

APK SHA-256: `44e263206979dc479e2706ee111f28aa8a4d949097eabe37a6eca2b63033dca0`

## Visual checks

The following images were rendered from the actual shared Compose UI and inspected. They use synthetic test messages and credentials, not a captured live feed or account. The key receipt screenshot is from the desktop test target, which keeps credentials for the session; Android saves them with Keystore encryption.

![Phone feed](phone-feed.png)

![Phone conversation](phone-thread.png)

![Tablet split view](tablet.png)

![Create account](phone-registration.png)

![API key receipt](phone-api-key.png)

## Scope

- No Android emulator or physical Android device was available. Android compilation, APK packaging/signature validation, lint, and shared-UI execution were verified; on-device runtime behavior remains to be checked.
- No named-board API key was supplied and no live registration was performed. Registration and authenticated reading are covered by contract and UI tests. The registration fields and response were checked against the live public OpenAPI contract and integration guide.
- The delivered APK is signed with a development/debug certificate. A production release should use your own release signing configuration.
- The pinned dependencies are intentional; lint may report newer available versions.
