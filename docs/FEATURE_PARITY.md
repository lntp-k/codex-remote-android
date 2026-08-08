# Desktop feature parity audit

Audit baseline:

- Codex Desktop `26.721.4979.0` command registrations and remote-host request bridge.
- Open-source Codex app-server schema from `codex-rs/app-server-protocol`.
- Android remains an SSH client only; agent execution stays on the remote host.

Verification labels in this file distinguish source/automated implementation
from device/runtime proof. The Android 16 and multi-session changes described
below have automated coverage, and final local ARM64 build counts/digests are
recorded in `docs/verification/ANDROID_16_MIGRATION.md`. A signed `0.1.7` QA
candidate and signer continuity with `0.1.6` are verified. Actual upgrade
installation, Android instrumented runtime, physical devices, and real SSH
smoke are still pending.

## Implemented remote workflow

| Area | Android support |
| --- | --- |
| Host discovery | Imports every active remote thread with cursor pagination and no project filter; groups projects by thread `cwd`. |
| Thread lifecycle | Start, paginated resume, rename, pin, archive, browse archived tasks, unarchive, permanently delete, fork, and compact. |
| Turns | Load the latest five full turns first, fetch older pages at the top with `thread/turns/list`, preserve scroll position, start, stream, steer, interrupt, and render every current `ThreadItem` variant. |
| Composer | Desktop-style plus menu, remote file/folder references, Android image input, Goal/Plan actions, remote skill/plugin `$` mentions, slash commands, model, reasoning, service tier, collaboration mode, and permissions. |
| Commands | `/compact`, `/feedback`, `/fork`, `/goal`, `/init`, `/mcp`, `/model`, `/new`, `/plan`, `/reasoning`, `/review-mode`, and `/status`, plus task-management shortcuts. |
| Goals | Desktop-style removable Goal marker for the next composer submission, plus read, create, edit, pause, resume, clear, and continue persisted remote goals. |
| Review and file changes | Start inline reviews and render structured, expandable changed-file summaries in the conversation. There is no separate Changes tab. |
| Long conversations | Open at the latest message, load older turns incrementally while scrolling upward, preserve the visible anchor during prepend, and show a return-to-latest button away from the bottom. |
| MCP | Show server/tool/resource status, start OAuth, observe completion, and reload remote MCP configuration. |
| Authentication | Reuse remote auth and support the ChatGPT device-code flow. |
| Session state | Keep independent bounded timeline, history, goal, settings, turn, approval, unread, and diagnostic state for known remote threads; route interleaved events by exact thread/turn identity. |
| Session supervision (partial) | Show per-task running, approval, failed, and unread indicators in the existing project/task navigation. This is not yet a dedicated dashboard. |
| Approvals | Command, file-change, permission, and structured user-input requests in one arrival-ordered surface. Show exact owner task/path/ID; background or unknown owners cannot be allowed until the exact task is opened, while deny remains available. This is not yet a grouped inbox. |
| Diagnostics | Context and rate-limit status, app-server warnings, feedback upload, and SSH host-key pinning. |
| Android build baseline | Source compiles/targets API 36 with AGP 8.13.2, Gradle 8.13, JDK 17 and `minSdk = 26`. CI assembles JVM tests/lint, debug APK, Android-test APK, and an unsigned minified release, then checks integrity, 16 KiB alignment, expected signatures, and hashes. Separately, the local signed `0.1.7` QA candidate passed signature continuity, ZIP, and 16 KiB ZIP/ELF checks. |

## Remaining gaps

### High priority

- Complete multi-session dashboard and grouped approval inbox: host/project/
  workspace grouping, aggregate status, safe bulk denial, notification deep
  links, process-recreation restoration, and real three-session runtime proof.
- Rich Git review: automatic base-branch discovery, per-file navigation, syntax-highlighted diffs, inline comments, commit/revert actions, and detached review delivery.
- Edit or undo a historical turn. The protocol's `thread/rollback` is deprecated and does not restore repository changes; a correct implementation must restore both conversation history and affected files.
- Background terminal sessions and interactive terminal input. Command events render, but Android has no terminal panel for long-running PTY sessions.
- MCP extended-form elicitation, MCP App HTML surfaces, resource browsing, and direct MCP tool invocation.
- Plugin management: marketplace browsing, install/uninstall/update, authentication policy, plugin details, and sharing. Android currently loads installed plugins for `$` mentions only. The public protocol marks several plugin management methods as under development.

### Composer and media

- Fuzzy remote file search across the project. The current picker browses remote directories and inserts `@file` references, but it does not yet provide desktop's ranked workspace-wide search.
- Generic file attachments, remote-path images, audio attachments, dictation, realtime voice, and rich generated-media viewers.
- Temporary `/side` chats, Memories controls, personality selection, and auto-review-denial approval shortcuts.
- Historical-turn fork selection through `thread/fork.lastTurnId`; current fork copies the complete task.

### Settings and account

- Enable/disable skills, add extra skill roots, view full skill/plugin details, and manage Hooks.
- Full remote config editor, experimental feature switches, account logout, usage history, earned rate-limit resets, and workspace messages.
- Notification preferences, desktop automations, app connectors, and Remote Control relay pairing.

### SSH and platform integration

- OpenSSH config expansion, ProxyJump, SSH agent or hardware-key authentication, and managed relay pairing.
- Remote file open-in-editor actions, deep links, desktop notifications, and Android share-sheet integration.

### Release and runtime verification

- Upgrade installation of the signed `0.1.7` candidate over `0.1.6`, with
  saved connections preserved. Its package ID, increasing version code, and
  identical signer are statically verified prerequisites, not an install test.
- Android 14, 15, and 16 device behavior, including Samsung background policy,
  edge-to-edge, predictive Back, rotation, resizing, and keyboard insets.
- Execution of the Android instrumentation test APK on a device/emulator. The
  current CI compiles the APK but does not run it.
- Real password/private-key SSH, host-key change rejection, streaming,
  approval, reconnect, and remote Codex compatibility smoke.

These gaps should not be represented as working until their app-server request,
notification handling, UI state, error state, and non-destructive verification
are all implemented.
