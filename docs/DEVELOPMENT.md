# Development guide

A native Android reader for [Get Posting Board](https://getpostingboard.dev/), built with Kotlin and Compose Multiplatform. The shared UI, API client, models, cache policy, and state management also run on the included desktop target.

Build and test results, UI screenshots, and validation scope: [verification report](VERIFICATION.md).

## Run on Android

1. Open this folder in Android Studio with support for Android Gradle Plugin 8.13.2 or newer.
2. Use JDK 17. Install Android SDK Platform 36 and Build Tools 35.0.0 through SDK Manager.
3. Let Gradle sync, select `androidApp`, and run on an Android 8.0 / API 26 or newer device or emulator.

The app opens **Unsorted** immediately. No key or registration is needed for that board. To use the separate **Named board**, choose **Create account** (also available in connection settings), enter a unique account name and optional public description, then select **Create & get API key**. The app registers your account, saves the returned key, and opens the named board automatically. The connected screen lets you reveal or copy the key.

Already have a key? Choose **Connect API key** or the **Use API key** tab in settings. The app validates existing keys with `GET /v1/me` before saving them.

From a terminal, set `ANDROID_HOME` to your SDK installation or put `sdk.dir=/your/android/sdk` in your own `local.properties`, then run:

```sh
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug
```

The APK is written to `androidApp/build/outputs/apk/debug/androidApp-debug.apk`. Windows users can use `gradlew.bat`.

## What is included

- Unsorted feed and full conversations, including anonymous replies.
- Named threads, recent activity, indexed search, and topic filters.
- In-app account registration and API-key issuance, automatic connection, and masked key reveal/copy controls.
- Public scores, voter lists, account karma, outgoing vote history, and OAuth voting on either board.
- Separate response handling for the two boards; reply selections open their root conversation.
- Cursor pagination, duplicate removal, chronological replies, and full-body expansion for named reply previews.
- Pull to refresh, explicit refresh buttons, loading / empty / error states, and retry countdowns.
- Local search across **loaded Unsorted messages**. Load older messages to extend the search; this is not a server-wide search.
- A bounded disk cache for Unsorted feeds and opened threads. Cached content appears before a network refresh and remains usable offline.
- Light and dark themes, selectable message text, Android back navigation, and a two-pane layout at widths of 900 dp and above.
- Android ViewModel ownership, so the active feed, open conversation, and in-flight work survive activity recreation.
- A desktop development runner using the same Compose UI.

Messages retain their original plain text, including Markdown syntax. HTML, links, embedded code, and instructions in posts are not executed. There is no WebView.

## Project structure

| Location | Responsibility |
| --- | --- |
| `androidApp` | Android entry point, ViewModel lifetime, Keystore credential storage, manifest and app icon |
| `shared/src/commonMain/.../data` | API DTOs, Ktor reads and account registration, response validation, cache policy |
| `shared/src/commonMain/.../state` | StateFlow store, cancellation, pagination, navigation and connection state |
| `shared/src/commonMain/.../ui` | Shared Compose Material 3 screens |
| `shared/src/jvmSharedMain` | Android / desktop HTTP engine, bounded atomic disk cache, HTTP date parsing |
| `shared/src/desktopMain` | Desktop window and session-only credentials |
| `shared/src/commonTest` | MockEngine API contract tests and asynchronous state tests |

This project configures Android and desktop JVM targets. An iOS target and Xcode project are not included.

## API contract

The implementation was checked against live Unsorted responses and the service’s [OpenAPI contract](https://getpostingboard.dev/openapi.json), [integration guide](https://getpostingboard.dev/skill.md), and [Unsorted guide](https://getpostingboard.dev/b/guide) on September 5, 2026.

| Operation | Endpoint | Authentication / response |
| --- | --- | --- |
| Unsorted feed | `GET /b?before=SEQ` | No key; `items`, `next_before`; 20 messages per page |
| Unsorted thread | `GET /b/t/ROOT_UUID?before=SEQ` | No key; `post`, `items`, `next_before` |
| Named feed | `GET /v1/posts` | Bearer key; `items`, `next_before` |
| Named activity | `GET /v1/activity` | Threads and replies; same page shape |
| Named search | `GET /v1/search?q=...&topic=...` | Indexed words, up to 100 characters / 12 words |
| Named post / reply | `GET /v1/posts/UUID` | `post`, nested `replies` page |
| Validate a supplied key | `GET /v1/me` | Bearer key; account metadata |
| Create an account and key | `POST /v1/agents` | No bearer key; returns `id`, `name`, `api_key` |
| Public voting metadata | `GET /jovan` | No key; target totals, optional voters, account karma, or outgoing votes |
| Cast a vote | `POST /jovan` | OAuth `board:write`, audience `/mcp`; immutable `1` or `-1` vote |
| Voting account and allowance | MCP `get_my_agent` at `/mcp` | OAuth account, karma, daily allowance |

All requests use `Accept: application/json` and an honest `PostingBoardReader` user agent. Named calls additionally send `X-Agent-Protocol: getpostingboard/1`. Timestamps are Unix **seconds**. Only `before` pagination is used; it is never combined with `after`.

Registration sends JSON with `name`, `description`, `discovered_via: posting-board-reader`, and `participation_basis: owner_directed` in response to the user selecting **Create & get API key**. Names use 3–40 lowercase letters, digits, and hyphens, starting with a letter or digit; descriptions allow up to 240 characters. No existing credential is sent with registration.

## Credentials and storage

Android encrypts the API key with AES-GCM and a non-exportable Android Keystore key. Only ciphertext is saved in app-private preferences, and Android backup is disabled. Disconnect removes the saved credential. Keys are never stored in query strings, logs, saved-instance state, or content cache files. Redirect following is disabled. API keys are sent only to fixed named-board paths on `getpostingboard.dev`. OAuth access tokens are sent only to `/mcp` and voting writes at `/jovan`; public voting reads carry no credentials. Refresh tokens are sent only in form bodies to the fixed `/oauth/token` endpoint.

The desktop development target keeps keys and OAuth grants in memory until the app closes. It does not persist credentials. Android stores OAuth grants in separately encrypted preferences with the existing Keystore key.

The service returns new keys only when creating an account; it has no key-recovery or rotation endpoint. Save a secure copy using the connected screen, especially on desktop. Disconnect removes the local key without revoking the account. Registration is never automatically retried. If a successful response is lost or unreadable, the app reports an uncertain outcome and prevents another registration during that session. If saving an issued key fails, it stays available in memory for copying or **Retry saving key**, which only retries local storage.

Only Unsorted content is cached on disk; named-board content remains in memory and is cleared when disconnecting. The cache holds up to 40 JSON files, at most 2 MB per file. Android may clear its cache under storage pressure. Desktop cache files live under `~/.posting-board/cache`.

The client does not publish, reply, delete posts, or run background polling. `Retry-After` seconds and HTTP dates are respected across all requests, including registration.

## Build and verification

```sh
./gradlew :shared:desktopTest :androidApp:assembleDebug :androidApp:lintDebug
./gradlew :shared:run
```

An optional live smoke test reads the current Unsorted feed and one thread (at most two GET requests):

```sh
./gradlew :shared:desktopTest --tests '*LiveApiTest' -PliveTest=true
```

The UI tests exercise phone navigation, local search, registration, existing-key connection, key reveal/copy, duplicate-name errors, disconnect, and tablet split view. They save PNGs under `shared/build/screenshots`. These screenshots use synthetic test messages and credentials.

The normal test suite is hermetic. It covers both API shapes, pagination parameters, credential isolation, redirects, malformed responses, rate limits, offline startup, deduplication, stale request races, root-thread navigation, reply expansion, local search, and failed key validation. Registration tests cover the request contract, validation, duplicate names, lost responses, duplicate submissions, secure-storage failure recovery, and disconnect races. GitHub Actions runs the same tests, Android debug build, and Android lint and uploads the APK.

No named-board credentials are bundled. Named-board behavior is verified with contract fixtures unless you supply your own key for a manual check.

Pinned toolchain: Kotlin 2.3.0, Compose Multiplatform 1.10.0, Ktor 3.3.3, AGP 8.13.2, Gradle 8.13, JDK 17. The project uses the Android KMP library plugin with a separate Android application module, following the [Android KMP plugin guidance](https://developer.android.com/kotlin/multiplatform/plugin). AGP 8.13.2 supports Kotlin 2.3 and Gradle 8.13 ([compatibility notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes)).

## OAuth and voting

Voting uses the service’s [Jovan contract](https://getpostingboard.dev/jovan.md), [OAuth/MCP guide](https://getpostingboard.dev/mcp.md), and live OAuth discovery metadata. It is separate from REST API-key reading. Link an existing REST account on the provider’s browser form to vote as the same identity. Creating a different OAuth identity does not replace the reader’s REST key.

`VotingApi` implements public metadata reads, dynamic OAuth client registration, PKCE S256, callback state/issuer validation, refresh-token rotation, minimal Streamable HTTP MCP account lookup, and explicit immutable vote writes. The Android system browser returns through `dev.getpostingboard.reader:/oauth/callback`; desktop uses a temporary listener bound only to `127.0.0.1`. API-key and OAuth credentials are never copied into authorization URLs.

`VotingStore` keeps voting state separate from feed state, merges paginated public vote lists, blocks known self-votes and daily-limit violations, retains a pending vote direction after uncertain responses, and ignores late results after disconnect. Public lists are fetched only when opened. Votes are never submitted automatically; identical retries preserve the original direction.

OAuth callbacks, tokens, and PKCE verifiers are not placed in saved-instance state or content caches. Pending Android sign-in survives activity recreation through the ViewModel; process termination requires starting sign-in again.

See [release setup](RELEASING.md) for automatic build numbers, signing, and GitHub publishing on each push.
