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
gradle wrapper --gradle-version 8.11.1
./gradlew :app:installDebug
```

> The Gradle wrapper JAR is not committed. Android Studio regenerates it on
> first sync; from the CLI run `gradle wrapper` once (needs a local Gradle).

## Configuration

Tap the **gear icon** → set:

| Field | Maps to TUI env var | Notes |
|---|---|---|
| Endpoint URL | `AG_UI_ENDPOINT` | Emulator → host machine uses `http://10.0.2.2:<port>/agent` |
| Headers (JSON) | `AG_UI_HEADERS` | e.g. `{"Authorization":"Bearer …"}` |
| Initial state (JSON) | `AG_UI_INITIAL_STATE` | passed as the run's `state` |

Settings persist via DataStore and apply to the next run.

## Thread history

Conversations are organized into threads. A navigation drawer (swipe from the
left or tap the menu icon) lists all saved threads, lets you switch between them
or delete them. Each thread's messages are persisted to the device filesystem
(`threads_index.json` + per-thread files) so history survives app restarts.

## File & image attachments

You can select files or capture photos directly inside the app to send to the Agent. Tap the **+** (Add) icon next to the input bar to upload local documents or capture a photo using the device camera. Selected attachments are displayed as chips and can be removed before submission. When sending, the app uploads files to the thread (`POST /api/threads/{threadId}/uploads`) and injects their virtual paths into the prompt inside a `<uploaded_files_from_android>` block. In the chat history UI, these raw path blocks are cleaned up and replaced with a neat `附件：[filename]` badge.

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
