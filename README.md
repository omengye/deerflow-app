# DeerFlow Android

An Android client for the **AG-UI protocol** over HTTP/SSE — a port of the
`deerflow-tui` terminal client's logic to Kotlin + Jetpack Compose.

It connects to any AG-UI-compatible agent backend (LangGraph, CrewAI, Mastra, …)
via `POST {endpoint}` with a `text/event-stream` response, and renders the
streaming events (text / reasoning / tool calls / interrupts).

## Requirements

- Android Studio (Ladybug or newer) with **JDK 17**
- Android SDK Platform **36** installed (minSdk 30 / Android 11)
- An AG-UI backend reachable from the device/emulator

## Build & run

```bash
# Open the project in Android Studio and let it sync, OR from a shell with a
# JDK 17 + Gradle available, generate the wrapper once:
gradle wrapper --gradle-version 9.5.1
./gradlew :app:installDebug
```

> The Gradle wrapper JAR is not committed. Android Studio regenerates it on
> first sync; from the CLI run `gradle wrapper` once (needs a local Gradle).

## GitHub Actions APK builds

The `.github/workflows/android-apk.yml` workflow builds a debug APK for every
push, pull request, and manual run. The APK is uploaded as the
`deerflow-debug-apk` workflow artifact.

Pushing a tag that starts with `v`, for example `v1.0.0`, also builds a signed
release APK and publishes it to GitHub Releases. Configure these repository
secrets before creating a release tag:

| Secret | Description |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded `.jks` or `.keystore` file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Example keystore encoding commands:

```bash
base64 -w 0 release-keystore.jks
```

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release-keystore.jks"))
```

## Configuration

Tap the **gear icon** → set:

| Field | Maps to TUI env var | Notes |
|---|---|---|
| Endpoint URL | `AG_UI_ENDPOINT` | Emulator → host machine uses `http://10.0.2.2:<port>/agent` |
| Token | `AG_UI_HEADERS` | Bare token; sent as `Authorization: Bearer <token>` |
| Initial state (JSON) | `AG_UI_INITIAL_STATE` | passed as the run's `state` |

Settings persist via DataStore and apply to the next run.

## Thread history

Conversations are organized into threads. A navigation drawer (swipe from the
left or tap the menu icon) lists all saved threads, lets you switch between them
or delete them. Each thread's messages are persisted to the device filesystem
(`threads_index.json` + per-thread files) so history survives app restarts.

## File & image attachments

You can select files or capture photos directly inside the app to send to the Agent. Tap the **+** (Add) icon next to the input bar to upload local documents or capture a photo using the device camera. Selected attachments are displayed as chips and can be removed before submission. When sending, the app uploads files to the thread (`POST /api/threads/{threadId}/uploads`) and injects their virtual paths into the prompt inside a `<uploaded_files_from_android>` block. In the chat history UI, these raw path blocks are cleaned up and replaced with a neat `附件：[filename]` badge.

## Skill Proposal approvals

When connected to this project's DeerFlow API with authentication enabled, the
top-bar review icon opens the Proposal approval center. Pending Proposals that
originated in the current thread also appear as interactive chat cards. You can
inspect the diff and security scan results, add an optional review note, reject
the Proposal, or approve and immediately publish it.

The feature reuses the configured `Authorization` header and the existing
`/api/admin/evolution/proposals` endpoints. Keep backend `auth_enabled` set to
`true`; the Admin API is intentionally unavailable when authentication is off.
The app refreshes Proposal state while it is in the foreground, so reviews made
through another connected channel are reflected automatically.

## Thread title synchronization

The client automatically keeps thread titles in sync with the backend. Once an agent run finishes, if the thread does not have a fetched title, the app runs a background request (`GET /api/threads/{threadId}`) to fetch the server-generated thread title and updates the local history index.

## Markdown rendering

Assistant messages are rendered with a built-in Markdown parser supporting:

- Headers (`#`, `##`, `###`)
- Unordered and ordered lists
- Fenced code blocks with monospace styling
- Tables (header row + bordered cells)
- Inline formatting: **bold**, *italic*, `code`, and `[links](https://example.com)`

## Background streaming

While a run is active, an `SseForegroundService` (type `dataSync`) keeps the
process at foreground priority so SSE delivery continues when the app is
backgrounded. The run itself lives in an app-scoped coroutine inside
`ConversationRepository`, so it also survives screen rotation.

> Android 13+ requires the `POST_NOTIFICATIONS` runtime permission (requested on
> launch) for the foreground-service notification. Android 15 caps `dataSync`
> foreground time per day; for very long-lived streams consider switching the
> service type.

## Architecture

```
data/agui/    AguiClient (POST+SSE), EventParser, AguiJson   ← internal/agui (Go)
domain/model/ ChatMessage, AguiEvent, Interrupt, Roles        ← types.go
domain/       ConversationReducer, ConversationState,         ← tui/model.go
              ReplayFilter                                       (pure, testable)
data/         ConversationRepository (single source of truth)
data/settings SettingsStore (DataStore)                        ← config.go
service/      SseForegroundService (background keep-alive)
ui/           MainActivity, ChatScreen, BlockCard, Settings    ← TUI View layer
```

The protocol parsing and event reduction are pure Kotlin with no Android
dependency, mirroring the Go `agui` package and `tui.model` state machine
one-to-one — they can be unit-tested on the JVM.

### AG-UI event types handled

| Event | Description |
|---|---|
| `RUN_STARTED` / `RUN_FINISHED` / `RUN_ERROR` | Run lifecycle |
| `TEXT_MESSAGE_START` / `TEXT_MESSAGE_CONTENT` / `TEXT_MESSAGE_END` | Streaming text |
| `TEXT_MESSAGE_CHUNK` | Chunked text delta |
| `REASONING` | Agent reasoning steps (collapsible) |
| `TOOL_CALL` / `TOOL_RESULT` | Tool invocation and result |
| `INTERRUPT` | Human-in-the-loop pause with resume prompt |
