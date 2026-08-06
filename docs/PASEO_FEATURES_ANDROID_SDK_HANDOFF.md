# Codex Remote: Paseo-inspired features and Android SDK handoff

- Status: proposed and ready for implementation handoff
- Prepared: 2026-08-06 (Asia/Seoul)
- Repository: `lntp-k/codex-remote-android`
- Baseline commit: `c2b5e60fecc37bb8d95c52d082da06031f87187c`
- Baseline release: `0.1.6` (`versionCode = 7`)

## 0. 한국어 요약

이 문서의 목표는 Codex Remote를 Paseo의 복제품으로 만드는 것이
아니라, 현재의 **Android -> SSH -> 원격 Codex** 직접 연결 구조를
유지하면서 체감 가치가 큰 기능을 선별해 추가하는 것이다.

구현 순서는 다음과 같이 고정한다.

1. Android 빌드 도구를 AGP 8.13.2와 Gradle 8.13으로 올린다.
2. `compileSdk`와 `targetSdk`를 각각 별도 단계로 API 36까지 올린다.
3. 선택된 한 세션 중심의 상태 구조를 세션별 상태 저장소로 분리한다.
4. 멀티 세션 대시보드와 통합 승인함을 추가한다.
5. 명시적인 Workspace와 안전한 Git worktree 관리를 추가한다.
6. 연결 복구와 원격 지속 실행을 검증한 뒤 터미널을 추가한다.
7. 마지막으로 파일별 Git diff, 검토, commit/revert UI를 추가한다.

Android 17/API 37은 아직 운영 대상으로 삼지 않는다. 특히 사설 IP,
Tailscale 주소, 로컬 네트워크 권한 변화가 직접 SSH 연결에 미치는
영향을 별도 실험 빌드에서 검증한 뒤 판단한다. relay, 다중 LLM 제공자,
범용 원격 셸 API는 초기 범위에서 제외한다.

## 1. Outcome

Evolve Codex Remote from a selected-thread-oriented Android client into a
Codex-only mobile development console with the most useful parts of Paseo:

- live multi-session supervision;
- a unified approval inbox;
- explicit projects and workspaces;
- Git worktree isolation;
- reconnectable remote execution;
- persistent terminal sessions; and
- rich Git change review.

The product boundary remains deliberately narrower than Paseo:

- Android connects directly to the target host over SSH;
- Codex continues to run on the remote host;
- no required relay, cloud account, or always-on desktop intermediary;
- no multi-provider abstraction in the initial roadmap; and
- no generic, remotely callable shell API.

The intended result is approximately 70% of Paseo's useful workflow for this
use case without inheriting its full relay, provider-adapter, and daemon
complexity.

## 2. Decisions already made

1. **Upgrade to the stable Android 16 toolchain before adding major features.**
   Use API 36 for production. Android 17/API 37 remains an experimental branch
   until the platform is stable and local-network behavior has been validated.
2. **Keep `minSdk = 26`.** Existing supported devices should not be dropped as
   part of the modernization work.
3. **Separate toolchain, compile SDK, and target SDK changes.** They must land in
   separate commits or PRs so regressions can be identified and reverted.
4. **Refactor session state before adding the dashboard.** Incoming events are
   currently accepted for the selected thread. Multi-session behavior must not
   be layered on top of that selected-thread state model.
5. **Preserve the direct SSH trust model.** Host-key verification, Android
   Keystore secret storage, remote Codex authentication, and app-server
   approvals remain authoritative.
6. **Use restricted remote operations.** Worktree and Git actions must use
   typed operations with validated arguments and repository paths, not shell
   string interpolation supplied by the UI.
7. **Place persistent execution responsibility on the remote host.** Android
   WorkManager or a long-lived phone process alone is insufficient under Doze,
   app standby, process death, and network transitions.

## 3. Verified baseline

### 3.1 Artifact and source

- Git HEAD: `c2b5e60fecc37bb8d95c52d082da06031f87187c`
- Commit subject: `Harden Android remote sessions (#1)`
- APK: `codex-remote-android-v0.1.6.apk`
- APK size: `3,616,373` bytes
- APK SHA-256:
  `3808b0f1e9246d00dbcac1fbb3a3a793fedbaa94abc5622ab8ab55c7bb639cfe`
- Worktree at handoff: `graphify-out/` is untracked; it predates this document
  and must not be committed accidentally without a separate decision.

### 3.2 Current Android build

| Component | Current | Planned production baseline |
| --- | --- | --- |
| `compileSdk` | 35 | 36 |
| `targetSdk` | 35 | 36 |
| `minSdk` | 26 | 26 |
| Android Gradle Plugin | 8.7.3 | 8.13.2 |
| Gradle wrapper | 8.10.2 | 8.13 |
| Kotlin plugins | 2.0.21 | retain initially |
| JVM/JDK target | 17 | 17 |

Do not combine a Kotlin, Compose BOM, SSHJ, coroutine, or serialization upgrade
with the SDK migration. Dependency refreshes belong in later, independently
tested changes.

### 3.3 Current runtime topology

```text
Android app
  -> verified SSH connection
    -> remote login shell
      -> codex app-server --listen stdio://
        -> JSONL Codex app-server protocol
```

The app already supports host-wide thread discovery, cursor pagination,
project grouping by `cwd`, thread resume, streaming, approvals, goals, model and
permission selection, MCP status/OAuth, and remote Codex authentication.

### 3.4 Architectural constraint to remove first

`AppViewModel` and `CodexRpcClient` are the two central hubs. In particular:

- `AppViewModel.observeEvents` owns a broad set of event-to-UI transitions;
- `acceptsThreadEvent` filters streamed events against the selected thread;
- the test `streamedEventsOnlyApplyToTheSelectedThread` records that current
  behavior; and
- transport lifetime is tied to the SSH app-server channel.

This is correct for a single visible session but cannot reliably represent
several active sessions, independent approvals, or background completion.

## 4. Target architecture

```text
Android UI
  Project -> Workspace -> Session
       |          |          |
       |          |          +-- timeline and turn state
       |          |          +-- running/completed/failed state
       |          |          +-- approval queue
       |          |
       |          +-- repository/worktree identity
       |          +-- terminal sessions
       |          +-- Git status and review
       |
       +-- host and project presentation metadata

Application layer
  SessionRegistry: Map<ThreadId, SessionState>
  EventRouter: AppServerEvent -> exact SessionState or global state
  ApprovalInbox: immutable request identity + owning thread/workspace
  WorkspaceRegistry: local metadata + remote repository truth
  ConnectionSupervisor: connect, health, reconnect, restore

Remote channels over one verified SSH trust relationship
  1. Codex app-server RPC channel(s)
  2. restricted Git/worktree operations
  3. explicit PTY/tmux terminal channels
```

### State ownership rules

- Remote Codex owns thread, turn, approval, model, authentication, and agent
  execution truth.
- Remote Git owns branch, worktree, index, and working-tree truth.
- Android stores only connection records, presentation metadata, cached
  snapshots, and reconnection hints.
- Cached Android state must never silently override a newer remote state.
- An event that cannot be bound to an exact thread/request is surfaced as a
  diagnostic and must not be auto-approved or applied to the selected thread.

## 5. Delivery plan

Each phase should be independently reviewable and leave the current workflow
usable. Suggested release names are provisional.

### Phase 0A - Capture the baseline

Purpose: make later regressions attributable.

Tasks:

- run unit tests, lint, debug assembly, and the real/mock SSH integration suite;
- record test counts, lint findings, APK signature, zip integrity, and hash;
- capture Android 14 and Android 15 direct-SSH smoke results;
- confirm upgrade installation over `0.1.6` preserves saved connections; and
- record remote Codex CLI/app-server version used by the smoke test.

Acceptance criteria:

- baseline evidence is attached to the PR or stored under `docs/verification/`;
- failures are either fixed or explicitly carried as known baseline failures;
- no debug APK is used with real production passwords or private keys.

### Phase 0B - Upgrade build tools only

Files expected to change:

- `build.gradle.kts`
- `gradle/wrapper/gradle-wrapper.properties`
- lock/checksum or CI files, if present

Target:

- Android Gradle Plugin 8.13.2;
- Gradle 8.13;
- JDK 17; and
- SDK levels still at 35.

Acceptance criteria:

- no source-level feature change;
- unit tests and lint remain semantically clean;
- both debug and signed release builds succeed;
- R8/minification release smoke succeeds; and
- the produced release remains installable as an upgrade over `0.1.6`.

### Phase 0C - Compile against API 36, still target API 35

Change only `compileSdk` from 35 to 36. Resolve new compiler and lint findings
without opting into API 36 target behavior yet.

Acceptance criteria:

- the complete Phase 0B gate passes;
- no new permission is added without a documented runtime use;
- Android 14, 15, and 16 launch, connection, chat, approval, and reconnect
  smokes pass; and
- no layout regression appears under gesture navigation, display scaling, or
  the on-screen keyboard.

### Phase 0D - Target API 36

Change `targetSdk` from 35 to 36 and explicitly validate Android 16 behavior:

- edge-to-edge insets on every top-level screen and dialog;
- predictive Back from connection, workspace, modal, and approval surfaces;
- background and foreground service restrictions;
- notification return paths;
- fixed-rate scheduling behavior, if any scheduling is introduced; and
- large-screen resizing and rotation.

Deliverable: `0.1.7-beta1` followed by a signed `0.1.7` only after device
validation.

### Phase 1 - Per-session state and event routing

Purpose: remove the selected-thread bottleneck without changing the visible
workflow first.

Introduce concepts equivalent to:

```text
SessionRegistry
  sessions: Map<ThreadId, SessionState>
  selectedThreadId: ThreadId?

SessionState
  thread snapshot
  timeline/history cursors
  active turn and expected turn ID
  composer state
  approval queue
  connection/stream status
  unread and diagnostic state
```

Tasks:

- extract per-thread state transitions from `AppViewModel`;
- route every thread-scoped notification by immutable thread ID;
- keep connection-, account-, model-, and MCP-scoped events global;
- bind approvals to both RPC request identity and owning thread/workspace;
- retain bounded history and delta buffers per session;
- define eviction for old inactive session caches; and
- preserve current behavior through compatibility selectors for the UI.

Required tests:

- two sessions may stream interleaved events without cross-contamination;
- switching the visible session loses no timeline or composer state;
- concurrent approvals remain attached to their originating sessions;
- an event with a missing or unknown thread ID fails closed;
- stale turn IDs cannot mutate a newer active turn; and
- reconnect snapshots reconcile with, rather than overwrite, remote truth.

Acceptance criteria:

- the existing selected-thread tests are replaced with stronger routing tests;
- no user-visible multi-session claim is made until interleaving tests pass;
- `AppViewModel` is primarily orchestration, not the owner of every transition.

### Phase 2 - Live multi-session dashboard and approval inbox

Add:

- status badges for idle, running, approval required, completed, interrupted,
  failed, and disconnected;
- unread completion and diagnostic counts;
- fast switching without resume/reload when state is already live;
- one unified approval inbox grouped by host, project, workspace, and session;
- safe bulk denial only; never bulk approval; and
- notification deep links to the exact approval/session.

Acceptance criteria:

- at least three simultaneous sessions remain independently observable;
- the UI never displays approval details from a different session;
- process recreation restores dashboard metadata and then reconciles remotely;
- interruption and connection loss are visibly distinct from task failure.

Suggested release: `0.2.0-beta1`.

### Phase 3 - Explicit workspaces and Git worktrees

Replace the current UI-only `cwd` grouping with an explicit hierarchy while
retaining imported legacy threads:

```text
Host
  -> Project (repository identity)
    -> Workspace (root checkout or Git worktree)
      -> Codex sessions
```

Tasks:

- derive a stable project identity from validated remote repository metadata;
- keep friendly project/workspace names as Android presentation metadata;
- create a worktree from a selected base branch;
- start Codex threads with the worktree as `cwd`;
- display branch, dirty state, upstream, and owning sessions;
- block cleanup while a session or terminal is active;
- before deletion, inventory tracked, untracked, ignored, staged, and unpushed
  changes; and
- make deletion a separate, explicit confirmation operation.

Security requirements:

- pass validated arguments to typed operations;
- reject paths outside an established repository root;
- do not accept raw shell fragments as branch or path values;
- display the effective repository, branch, and path before mutation;
- log the operation result without recording credentials or file contents.

Acceptance criteria:

- two worktrees can host simultaneous isolated Codex sessions;
- changes in one worktree never appear as changes in the other;
- dirty or unpushed work cannot be silently removed;
- legacy `cwd`-grouped sessions remain discoverable and resumable.

### Phase 4 - Reconnectable remote execution

This phase has a decision gate; do not select a persistence mechanism based on
convenience alone.

Evaluation order:

1. Verify whether the installed Codex app-server daemon provides a supported,
   authenticated, SSH-compatible reconnection lifecycle for this client.
2. If it does not, prototype a narrowly scoped remote user service for the
   Codex channel with explicit lifecycle and log bounds.
3. Reserve `tmux` primarily for human terminal sessions unless testing proves
   it reliable for the structured app-server channel.

Required behavior:

- loss of Wi-Fi, VPN, or phone process does not corrupt the remote task;
- reconnect uses bounded exponential backoff with user-visible state;
- user-requested disconnect is not treated as a retryable failure;
- reconnection reinitializes protocol state and reconciles active threads;
- duplicate event delivery is idempotent; and
- the user can explicitly terminate the remote persistent process.

Acceptance criteria:

- Wi-Fi-to-cellular and cellular-to-Wi-Fi transitions recover;
- screen-off/Doze and Android process recreation recover;
- Tailscale off/on and public-hostname paths are tested separately;
- a dead remote process is reported accurately rather than shown as connected;
- no externally reachable app-server TCP listener is introduced.

### Phase 5 - Persistent terminal panel

Use a separate SSH PTY channel rather than mixing terminal bytes with Codex
JSONL. Prefer explicitly named remote `tmux` sessions for persistence.

Minimum feature set:

- create, list, attach, detach, rename, and close terminal sessions;
- bind each terminal to a host and workspace;
- always show host, user, and effective `cwd`;
- bound scrollback and redact secrets in diagnostics;
- require explicit user action to open or send input; and
- distinguish disconnecting from terminating the remote terminal.

Out of scope for the first terminal release:

- unattended terminal command execution from notifications or external links;
- invisible background shells; and
- an arbitrary command API callable by other apps.

### Phase 6 - Rich Git review

Add:

- automatic base-branch discovery with manual override;
- repository and worktree status;
- per-file navigation;
- bounded, syntax-highlighted unified diffs;
- inline review comments;
- staged/unstaged selection;
- explicit commit and safe revert operations; and
- delivery of selected review context to the exact Codex session.

Acceptance criteria:

- binary, oversized, renamed, deleted, and untracked files have explicit UI
  states;
- commit and revert previews show exact affected paths;
- revert is never a broad checkout/reset operation;
- detached review comments remain bound to file and revision identity;
- repository changes are re-read after every mutation.

### Phase 7 - Optional second wave

Only after the preceding foundation is stable:

- Android notifications for completion and approval;
- SSH-forwarded access to workspace-local web services;
- remote-host schedules and heartbeat checks;
- voice input/dictation;
- session templates;
- fuzzy remote file search; and
- Android share-sheet/deep-link integration with strict allowlists.

## 6. Explicit non-goals

The initial roadmap does not include:

- a Paseo-compatible cloud relay;
- a new hosted account system;
- Claude Code, Copilot, OpenCode, Pi, or generic provider adapters;
- browser automation equivalent to a full desktop browser service;
- silent automatic approval;
- unattended arbitrary shell execution; or
- automatic deletion of worktrees, branches, sessions, or remote processes.

If multi-provider orchestration becomes a primary requirement, reassess using
Paseo directly instead of expanding Codex Remote into a second implementation
of Paseo.

## 7. Android 17/API 37 hold

Android 17 was still a preview at the preparation date. `compileSdk = 37` and
especially `targetSdk = 37` are not production requirements for this plan.

Before any future API 37 target migration:

- test the `ACCESS_LOCAL_NETWORK` runtime permission;
- cover private IPv4, CGNAT/`100.64.0.0/10`, Tailscale, public IPv4/IPv6, and
  hostname connections separately;
- verify denied-permission and revoked-permission UX;
- test certificate transparency and networking-library compatibility;
- review background activity launch restrictions; and
- repeat the complete SSH and reconnect device matrix.

API 37 must be delivered as an experimental build first and must not be bundled
with the Paseo-inspired feature work.

## 8. Security and reliability gates

Every phase must preserve these invariants:

- SSH host keys are pinned before authentication and changed keys are blocked.
- Passwords, keys, and passphrases remain encrypted by Android Keystore.
- Android backup/device transfer remains disabled for secret-bearing app data.
- Unknown app-server requests are surfaced or denied, never approved by
  default.
- Approval identity is immutable and bound to the exact request, thread, host,
  command `cwd`, file path/diff, and permission scope.
- JSONL lines, stderr, deltas, timelines, pagination, diffs, images, terminal
  scrollback, logs, and cached session counts remain bounded.
- External URI schemes and deep links are allowlisted.
- Remote mutations display their exact target before execution.
- Public-key or password material never appears in logs, crash reports, or
  screenshots generated by automated tests.

Run a fresh security review against the new commit before promoting any APK.
The earlier security report was tied to an older checkout and is evidence of
what to re-test, not proof about the new build.

## 9. Validation matrix

### Automated

- JVM unit tests for state transitions and parsers;
- interleaved multi-session event tests;
- approval identity and fail-closed tests;
- pagination, buffer, and scrollback bounds;
- Compose UI tests for dashboard, approval inbox, and predictive Back;
- mock app-server protocol tests;
- real SSH bridge integration tests;
- `lintDebug` with zero unexplained findings;
- debug and minified signed-release assembly;
- APK zip integrity, alignment, and v2+ signature verification.

### Android devices

At minimum:

- Android 14 / API 34;
- Android 15 / API 35;
- Android 16 / API 36;
- one Samsung device with aggressive background management; and
- one tablet or large-screen emulator.

Test each relevant release with:

- password and private-key authentication;
- first-use host-key confirmation and changed-key rejection;
- Wi-Fi, cellular, Tailscale, and direct public hostname as applicable;
- app background/foreground, screen off, Doze, force-stop, and process death;
- rotation, font scaling, gesture navigation, and keyboard insets;
- three simultaneous sessions and two concurrent approvals;
- remote Codex upgrade compatibility; and
- upgrade installation over the previous signed production APK.

### Remote host

- the actual DGX Spark/Hostinger architecture used for deployment;
- expected remote shell and Codex path;
- clean and dirty repositories;
- multiple worktrees and branches;
- insufficient permissions and disk-full behavior;
- remote process termination and host reboot;
- old and current supported Codex app-server versions.

## 10. Build and verification commands

Normal environment:

```bash
./gradlew --no-daemon --console=plain \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

Also build a signed, minified release with the four existing
`CODEX_REMOTE_*` signing variables. Do not print those values.

On DGX Spark ARM64, an earlier SDK 35 build required an explicit compatible
ARM64 `aapt2` override:

```bash
./gradlew --no-daemon --console=plain \
  -Pandroid.aapt2FromMavenOverride=/toolchain/aapt2 \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug
```

The `/toolchain/aapt2` path is not currently present in this checkout's host
environment. Before reusing the command, provision and verify the intended
binary and record its version and SHA-256. Do not assume that the previous
workaround remains correct after changing AGP or SDK Build Tools.

## 11. Recommended PR sequence

| PR | Scope | Must not include |
| --- | --- | --- |
| 1 | Baseline verification evidence | Feature or dependency changes |
| 2 | AGP 8.13.2 + Gradle 8.13 | SDK and Kotlin changes |
| 3 | `compileSdk = 36` | `targetSdk` change |
| 4 | `targetSdk = 36` + behavior fixes | Paseo-inspired features |
| 5 | SessionRegistry/EventRouter refactor | Dashboard feature claims |
| 6 | Multi-session dashboard + approval inbox | Worktree mutations |
| 7 | Workspace registry + safe worktree operations | Persistent terminal |
| 8 | Connection supervisor/persistence | Rich Git review |
| 9 | PTY/tmux terminal | Generic command API |
| 10 | Rich Git review | Unrelated provider integrations |

Small follow-up PRs are preferable to combining these boundaries. Each PR must
state the exact test/device evidence it adds.

## 12. Handoff map

Start investigation in these locations:

- `app/src/main/java/com/codex/remote/AppViewModel.kt`
  - connection bootstrap, selection, event observation, and present state hub;
- `app/src/main/java/com/codex/remote/data/rpc/CodexRpcClient.kt`
  - app-server requests, notifications, pagination, and approval parsing;
- `app/src/main/java/com/codex/remote/data/ssh/SshAppServerTransport.kt`
  - verified SSH lifecycle, app-server channel, keepalive, and abort behavior;
- `app/src/main/java/com/codex/remote/domain/Models.kt`
  - thread/project models and current `cwd`-based grouping;
- `app/src/main/java/com/codex/remote/ui/screens/WorkspaceScreen.kt`
  - current main workspace/session surface;
- `app/src/test/java/com/codex/remote/ComposerStateTest.kt`
  - selected-thread streaming behavior that Phase 1 must replace;
- `app/src/test/java/com/codex/remote/domain/ApprovalQueueTest.kt`
  - concurrent approval invariants; and
- `docs/ARCHITECTURE.md` and `docs/FEATURE_PARITY.md`
  - update whenever a feature crosses from proposed to verified.

The generated `graphify-out/GRAPH_REPORT.md` was built from commit `372581ad`
and is stale relative to the baseline commit. The codebase-memory graph was
current at handoff and identified `AppViewModel`, `CodexRpcClient`, event
handling, approval handling, and SSH transport as the main architectural
clusters. If code is changed, refresh the local graph as required by the
repository instructions; do not treat the stale generated report as current
evidence.

## 13. Default answers to implementation decisions

Unless new evidence requires a change:

- **Workspace metadata:** store friendly Android presentation metadata locally;
  always re-read repository/worktree truth remotely.
- **Persistent Codex execution:** prefer a supported Codex daemon lifecycle;
  otherwise use a narrowly scoped user service. Do not expose app-server TCP.
- **Terminal persistence:** use named `tmux` sessions on the remote host.
- **Approval UX:** unified read view, exact-session approval action, optional
  bulk deny, no bulk approve.
- **Session cache:** bounded in-memory live state plus minimal persisted
  metadata; reconcile on every connection.
- **API 37:** hold outside production until Android 17 is stable and the local
  network permission matrix passes.
- **Relay and provider adapters:** remain out of scope.

## 14. Definition of done

The roadmap is complete only when all of the following are true:

- a signed release targets API 36 and upgrades safely over `0.1.6`;
- Android 14, 15, and 16 device validation passes;
- at least three Codex sessions stream concurrently without state leakage;
- approvals remain bound to the exact request and session;
- two Git worktrees run isolated sessions without cross-contamination;
- connection loss, Doze, process death, and host restart have accurate recovery
  or accurate terminal failure states;
- persistent terminals reconnect without exposing an arbitrary command API;
- Git review and mutation actions are bounded, previewed, and non-destructive;
- the direct SSH path works without a relay or desktop intermediary;
- security review findings are resolved or explicitly accepted; and
- `ARCHITECTURE.md`, `FEATURE_PARITY.md`, release notes, APK hash, signature
  verification, and test evidence match the shipped artifact.

## 15. External references

- Paseo upstream: <https://github.com/getpaseo/paseo>
- Android 16 target behavior changes:
  <https://developer.android.com/about/versions/16/behavior-changes-16>
- Google Play target API requirements:
  <https://developer.android.com/google/play/requirements/target-sdk>
- Android Gradle Plugin 8.13 release notes:
  <https://developer.android.com/build/releases/agp-8-13-0-release-notes>
- Android 17 SDK setup and preview status:
  <https://developer.android.com/about/versions/17/setup-sdk>
- Android 17 target behavior changes:
  <https://developer.android.com/about/versions/17/behavior-changes-17>
