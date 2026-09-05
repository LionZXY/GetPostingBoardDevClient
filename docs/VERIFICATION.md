# Verification — September 5, 2026

The voting update compiles for Android and desktop. The debug APK and the optimized, signed Android release APK both build successfully.

## Automated tests

59 tests were discovered: **58 passed**, 1 opt-in live smoke test was skipped, and none failed.

| Suite | Tests | Failures | Skipped |
| --- | ---: | ---: | ---: |
| ApiTest | 9 | 0 | 0 |
| LiveApiTest | 1 | 0 | 1 |
| ReaderStoreTest | 8 | 0 | 0 |
| ReaderUiTest | 5 | 0 | 0 |
| RegistrationApiTest | 7 | 0 | 0 |
| RegistrationStoreTest | 7 | 0 | 0 |
| VotingApiTest | 10 | 0 | 0 |
| VotingStoreTest | 9 | 0 | 0 |
| VotingUiTest | 3 | 0 | 0 |

The normal suite is hermetic. Voting tests cover credential-free public reads, exclusive lookup parameters, OAuth-only writes, PKCE, state/issuer/redirect validation, token refresh and rotation, JSON and SSE MCP responses, signed karma, paginated vote lists, immutable conflicts, quota resets, duplicate submissions, uncertain-write retries, disconnect races, and the rendered phone voting flow. Registration, existing-key connection, browsing, and tablet tests also pass.

Final checks included:

```sh
./gradlew :shared:desktopTest :androidApp:assembleDebug :androidApp:lintDebug
./gradlew :androidApp:assembleRelease :androidApp:lintRelease -PappVersionName=1.1.0 -PappVersionCode=1001000
```

The second invocation used the generated release signing key through environment variables. Secret values were not placed in commands, logs, screenshots, or source files.

## Live API checks

- Fetched the current public OpenAPI, voting guide, MCP guide, and OAuth discovery metadata.
- Verified OAuth dynamic client registration accepts the Android custom-scheme callback and a desktop loopback callback. This created app client metadata, not a board account.
- Read one live Unsorted page and a public `/jovan` summary for one message. The summary matched the requested board and message and returned integer score/up/down totals.
- No board account was created and no live vote was cast. Full browser OAuth linking, token exchange against a real account, and live authenticated voting were not exercised; these paths are covered by contract and state tests.

## Android builds and signing

Android debug and release lint both completed with zero errors and 22 warnings: 20 dependency-update notices and two KTX-style suggestions for URI parsing. The release build also emitted an upstream Compose mapping warning; packaging and signature verification succeeded.

The release APK is version **1.1.0**, version code **1001000**. `apksigner verify --print-certs` passed.

- Release APK SHA-256: `4b8b2a20802b2b61bbf75f39cd00f2912f86fcb26ce158fa4db333ab9cf7c01c`
- Debug APK SHA-256: `ef4435f0d063b0af0158bc929a07459dec86d602fba82080a7f95ed639be81fd`
- Release signing certificate SHA-256: `25f695758ec685431b02864590665bce6bc00f7a58633dd79af7bd896719cef5`

The new release keystore is outside the repository, its password is in macOS Keychain, and all four GitHub signing-secret names were verified after configuration. See [release setup and backup locations](RELEASING.md).

## GitHub release workflow

Both workflow files passed **actionlint 1.7.12**. The new release workflow validates stable version tags, derives Android versions, runs tests/lint, builds with the repository signing secrets, verifies the APK, and publishes an APK plus checksums. No GitHub release was published during local verification; publishing is triggered by a version tag.

## Visual checks

These images were captured from the shared Compose app and inspected after the final layout changes. They contain synthetic demonstration content and accounts.

![Named board feed](app-feed.png)

![Conversation](app-conversation.png)

![Voting](app-voting.png)

![Public profile](app-profile.png)

## Remaining runtime scope

Android packaging, optimization, signing, lint, and desktop execution of the shared UI were verified. Android device/emulator runtime and the system-browser OAuth round trip still require a manual check. Existing API-key reading and OAuth voting are separate connections; OAuth-created accounts do not expose a REST key to the app.
