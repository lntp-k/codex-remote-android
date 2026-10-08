# Codex Remote: Paseo-inspired features and Android SDK handoff

- Status: Phase 0/1 implemented; Phase 2a partial; Phase 4 Android recovery implemented
- Prepared: 2026-08-06 (Asia/Seoul)
- Repository: `lntp-k/codex-remote-android`
- Baseline commit: `c2b5e60fecc37bb8d95c52d082da06031f87187c`
- Baseline release: `0.1.6` (`versionCode = 7`)
- Implementation branch: `agent/paseo-android16-session-registry`
- Pre-`0.1.8` source parent: `dbf1aae`
  (`Prepare signed Android 0.1.7 release candidate`)
- `0.1.8` Android recovery implementation commit: `a330b01`
  (`Keep SSH sessions across Android network handoffs`)

## 0. 한국어 요약

이 문서의 목표는 Codex Remote를 Paseo의 복제품으로 만드는 것이
아니라, 현재의 **Android -> SSH -> 원격 Codex** 직접 연결 구조를
유지하면서 체감 가치가 큰 기능을 선별해 추가하는 것이다.

현재 이 계획은 제안 단계를 지나 다음 범위까지 구현되었다.

1. AGP 8.13.2, Gradle 8.13, `compileSdk = 36`, `targetSdk = 36`으로
   각각 분리된 커밋을 통해 마이그레이션했다.
2. `SessionRegistry`, `SessionState`, `SessionEventRouter`,
   `SessionRequestTracker`로 선택된 한 세션 중심의 상태 구조를 분리했다.
3. 정확한 thread/turn 소유권, 교차 세션 이벤트 차단, 세션별 승인 큐,
   캐시·히스토리의 개별/합계 보존 한도를 구현했다.
4. 사이드바 세션 상태 표시와 전역 순차 승인 표시를 추가했다. 다른
   세션의 승인은 소유 작업을 열기 전에는 허용할 수 없고 거부만 가능하다.
5. `0.1.8`에서는 Application 범위 연결 소유자, 사용자 표시 foreground
   service, default-network handoff, Doze 대기, 제한된 자동 재접속과 원격
   상태 재조회를 구현했다. 메시지와 승인 응답은 자동 재생하지 않는다.

아직 완성되지 않은 다음 단계는 완전한 멀티 세션 대시보드/통합 승인함,
명시적 Workspace·worktree, 원격 프로세스 지속 실행, 터미널, 풍부한 Git
검토이다. 서명된 `0.1.8` QA 후보와 기존 `0.1.7`/`0.1.6`의 서명자 일치는
확인했다. 다만 실제 이전 버전 위 업그레이드와 연결정보 보존, Android 기기, 실제
SSH, Android instrumentation 런타임 검증은 완료되지 않았다. 현재 자동
검증 산출물과 최종 clean build 수치는
`docs/verification/ANDROID_16_MIGRATION.md`에 기록한다.

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

### Implementation status at this handoff

| Scope | Status | Evidence boundary |
| --- | --- | --- |
| Phase 0B: AGP 8.13.2 / Gradle 8.13 / JDK 17 | Implemented | Local ARM64 gate passed: 249 JVM tests, lint, debug/test/release APK builds, signature and alignment checks. |
| Phase 0C: compile against API 36 | Implemented | `compileSdk = 36`; no physical-device claim. |
| Phase 0D: target API 36 | Implemented in source | `targetSdk = 36`; Android 14/15/16 behavior and upgrade installation remain unverified. |
| Phase 1: per-session state and exact event routing | Implemented | JVM routing, bounds, ownership, parsing, projection, and request-race regressions exist. |
| Phase 2a: visible supervision | Partial | Per-thread indicators and one global sequential approval surface exist; no full dashboard, grouped inbox, notification deep link, or process-recreation claim. |
| Phase 4: Android connection recovery | Implemented on Android | Foreground lifetime, network handoff, Doze-aware retry, exact target restore, fail-closed ambiguity handling; no remote durable daemon. |
| Phases 3, 5-7 | Not implemented | Workspace/worktree, remote persistence, terminal, rich Git review, and optional integrations remain roadmap items. |

"Implemented" in this table means source plus automated checks, not a shipped
or device-validated release. The unsigned CI release artifact is deliberately
non-distributable.

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

### 3.2 Android build migration state

| Component | Original baseline | Current branch |
| --- | --- | --- |
| `compileSdk` | 35 | 36 |
| `targetSdk` | 35 | 36 |
| `minSdk` | 26 | 26 |
| Android Gradle Plugin | 8.7.3 | 8.13.2 |
| Gradle wrapper | 8.10.2 | 8.13 |
| Kotlin plugins | 2.0.21 | 2.0.21 |
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

### 3.4 Architectural constraint removed in Phase 1

The baseline routed streamed events through selected-thread UI state. The
current branch now introduces:

- `SessionRegistry`, an immutable cache keyed by exact thread ID;
- `SessionEventRouter`, which rejects missing/unknown thread IDs and stale or
  ambiguous turn ownership rather than applying them to the visible session;
- `SessionRequestTracker`, which invalidates stale connection, selection,
  load, and turn-start continuations;
- compatibility projection from the selected cached session into existing
  `AppUiState`; and
- bounded per-session and aggregate retained state with deterministic eviction
  only for inactive, unselected, non-approval, non-running, non-active-goal
  sessions.

`AppViewModel` still owns orchestration and legacy UI projection, so this is a
foundation rather than the end-state dashboard architecture. The SSH app-server
transport lifetime is also still tied to the phone connection; remote
persistence and reconnection are not implemented by this phase.

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

Status: **partially complete**. Source/build identity, automated gates, and the
signed `0.1.7` candidate with signer continuity are recorded. Physical-device,
upgrade-installation, and real SSH baseline evidence remain open.

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

Status: **implemented** in commit `fc025c7`. The toolchain is AGP 8.13.2,
Gradle 8.13, and JDK 17. Automated debug/unsigned-minified-release assembly is
part of the CI verification path. An authorized local release certificate was
used for the signed `0.1.7` QA candidate, but upgrade installation was not
performed and the artifact has not been promoted as device-validated.

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

Status: **implemented as an isolated migration step** in commit `a506f64`.
The branch subsequently advanced to Phase 0D, so its current target is no
longer API 35.

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

Status: **implemented in source** in commit `acd38eb`; device behavior remains
unverified. A signed `0.1.7` QA candidate now exists, but it must not be
described as a device-validated release.

Change `targetSdk` from 35 to 36 and explicitly validate Android 16 behavior:

- edge-to-edge insets on every top-level screen and dialog;
- predictive Back from connection, workspace, modal, and approval surfaces;
- background and foreground service restrictions;
- notification return paths;
- fixed-rate scheduling behavior, if any scheduling is introduced; and
- large-screen resizing and rotation.

Deliverable: promote the existing signed `0.1.7` QA candidate only after the
device, upgrade-preservation, and real-SSH gates pass; rebuild if any source or
release metadata changes before promotion.

### Phase 1 - Per-session state and event routing

Status: **implemented in the current working tree**.

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

Implemented details and regression coverage:

- exact thread-scoped routing for timeline items, deltas, turn lifecycle,
  goals, approvals, compaction, and failure events;
- strict app-server parsing that retains exact active-turn identity during
  resume and rejects malformed or ambiguous ownership;
- session-local timeline, history cursor, goal, settings, approval, running,
  unread, and diagnostic state;
- per-session and aggregate item/character/cursor budgets with saturating
  accounting, atomic rejection, and deterministic eligible LRU eviction;
- selected/running/approval-bearing/active-goal sessions protected from
  eviction;
- exact connection-generation and request ownership for asynchronous state;
- target-scoped safe authorization defaults instead of carrying full access
  across hosts or uncached sessions; and
- tests for interleaving, stale turns, fail-closed approval ownership,
  selection restoration, pagination/bounds, request ordering, and connection
  invalidation.

The compatibility projection means `AppViewModel` remains a large orchestrator.
Further decomposition is desirable, but the selected-thread acceptance filter
is no longer the authority for streamed session events.

### Phase 2 - Live multi-session dashboard and approval inbox

Status: **Phase 2a partial only**.

Implemented now:

- per-thread running, approval-required, failed, and unread indicators in the
  existing project/task navigation;
- cached-session switching without assigning a neighboring timeline;
- one global arrival-ordered approval surface;
- exact approval owner title/path/task identity;
- allow disabled for a background or unknown owner until the exact owning task
  is selected, while deny remains available; and
- an explicit action to open the owning task.

Still missing:

- a dedicated multi-session dashboard;
- an inbox grouped by host/project/workspace/session;
- bulk denial;
- completion/approval notifications and deep links;
- process-recreation persistence/reconciliation; and
- runtime proof of three simultaneously active sessions on a real host/device.

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

Android-side recovery landed in `0.1.8`: an active connection is anchored by a
foreground service, genuine default-network changes trigger a fresh SSH handoff
after turns and approvals reach a safe boundary, and transport loss reconnects
with capped backoff while reloading the selected remote task. Other pending RPCs
receive a bounded completion grace and are never replayed. Doze and offline
periods pause dialing without consuming attempts; a network change or wake can
resume a Doze-paused connection, while only a network identity change or manual
Connect resets the circuit breaker. The foreground monitor and exact desired
target remain present while automatic dialing is paused. The remote-persistence
decision gate below remains open; the Android recovery layer does not keep an
SSH-bound app-server or in-flight turn alive after that remote process exits.

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

The 2026-08-06 diff scan was tied to a **pre-fix working-tree snapshot**. It
reported four low/P3 issues: aggregate retained-state limits, authorization
inheritance, approval-owner presentation, and superseded-connection callbacks.
It also retained two non-security engineering defects for follow-up: resumed
active-turn identity loss and turn-start response/notification ordering.

Implementation commit `d9cd9f1` adds targeted fixes and regression tests for
those areas. This is remediation evidence, **not a fresh post-fix security
attestation**. Run another snapshot-pinned security scan against the final
commit and artifact before promoting any APK. See
`docs/verification/SECURITY_REVIEW_2026-08-06.md`.

## 9. Validation matrix

### Automated

- JVM unit tests for state transitions and parsers;
- interleaved multi-session event tests;
- approval identity and fail-closed tests;
- pagination, buffer, and scrollback bounds;
- Compose UI tests for approval-owner context and selected-session isolation;
- compile the Android instrumentation test APK even when no device runner is
  available;
- mock app-server protocol tests;
- real SSH bridge integration tests (still pending in this handoff);
- `lintDebug` with 0 errors and 19 categorized, recorded warnings;
- debug, Android-test, and minified unsigned-release assembly in CI;
- a separately authorized minified signed-release gate;
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
  :app:assembleDebug \
  :app:assembleDebugAndroidTest \
  :app:assembleRelease
```

Also build a signed, minified release with the four existing
`CODEX_REMOTE_*` signing variables. Do not print those values.

On DGX Spark ARM64, the API 36 automated build uses a verified compatible
ARM64 `aapt2` override:

```bash
./gradlew --no-daemon --console=plain \
  -Pandroid.aapt2FromMavenOverride=/toolchain/aapt2 \
  :app:testDebugUnitTest \
  :app:lintDebug \
  :app:assembleDebug \
  :app:assembleDebugAndroidTest \
  :app:assembleRelease
```

The disposable ARM64 build environment used `/toolchain/aapt2`, reported
`Android Asset Packaging Tool (aapt) 2.19-`, and verified SHA-256
`7e5ae2e1f62fc24cab14072555ffd0a1a7e1ce27e82cc1008967b835b6d8df5b`.
The path belongs to that environment, not the repository. CI runs on x86-64
and uses the official SDK package. Do not copy or trust a replacement binary
without recording its complete version and digest.

## 11. Delivery sequence and current state

| Step | Scope | State | Must not include |
| --- | --- | --- | --- |
| 1 | Baseline/roadmap documentation | Committed as `0d75b07` | Feature or dependency changes |
| 2 | AGP 8.13.2 + Gradle 8.13 | Committed as `fc025c7` | SDK and Kotlin changes |
| 3 | `compileSdk = 36` | Committed as `a506f64` | `targetSdk` change |
| 4 | `targetSdk = 36` | Committed as `acd38eb` | Paseo-inspired features |
| 5 | SessionRegistry/EventRouter + regression fixes | Committed as `d9cd9f1` | Full dashboard claims |
| 6 | `0.1.7` release metadata + signed candidate evidence | Verified 2026-08-08; device promotion pending | Device-validation claims |
| 7 | Complete multi-session dashboard + grouped approval inbox | Partial Phase 2a only | Worktree mutations |
| 8 | Workspace registry + safe worktree operations | Pending | Persistent terminal |
| 9 | Android connection supervisor | Implemented in `0.1.8`; remote persistence pending | Rich Git review |
| 10 | PTY/tmux terminal | Pending | Generic command API |
| 11 | Rich Git review | Pending | Unrelated provider integrations |

Small follow-up PRs are preferable to combining these boundaries. Each PR must
state the exact test/device evidence it adds.

## 12. Handoff map

Start investigation in these locations:

- `app/src/main/java/com/codex/remote/AppViewModel.kt`
  - connection bootstrap, selection, exact async ownership, session projection,
    and present orchestration hub;
- `app/src/main/java/com/codex/remote/session/SessionState.kt`
  - per-session remote/cache state and retained-state budgets;
- `app/src/main/java/com/codex/remote/session/SessionRegistry.kt`
  - immutable thread registry, selected-session projection source, global
    approval order, central bounds, and protected LRU eviction;
- `app/src/main/java/com/codex/remote/session/SessionEventRouter.kt`
  - exact thread/turn event routing and fail-closed diagnostics;
- `app/src/main/java/com/codex/remote/session/SessionRequestTracker.kt`
  - connection/load/selection/turn-start continuation ownership;
- `app/src/main/java/com/codex/remote/data/rpc/CodexRpcClient.kt`
  - app-server requests, notifications, pagination, and approval parsing;
- `app/src/main/java/com/codex/remote/data/ssh/SshAppServerTransport.kt`
  - verified SSH lifecycle, app-server channel, keepalive, and abort behavior;
- `app/src/main/java/com/codex/remote/domain/Models.kt`
  - thread/project models and current `cwd`-based grouping;
- `app/src/main/java/com/codex/remote/ui/screens/WorkspaceScreen.kt`
  - current main workspace/session surface;
- `app/src/test/java/com/codex/remote/ComposerStateTest.kt`
  - selected-session UI behavior after Phase 1 projection;
- `app/src/test/java/com/codex/remote/session/`
  - routing, bounds, eviction, approval ownership, and async-request regression
    tests;
- `app/src/test/java/com/codex/remote/ui/screens/ApprovalOwnerPresentationTest.kt`
  - exact owner presentation and fail-closed background approval behavior;
- `app/src/test/java/com/codex/remote/domain/ApprovalQueueTest.kt`
  - concurrent approval invariants; and
- `docs/ARCHITECTURE.md` and `docs/FEATURE_PARITY.md`
  - update whenever a feature crosses from proposed to verified.

`graphify-out/` is generated and remains outside the implementation/docs
commit unless deliberately refreshed and reviewed. Refresh the graph only at
the repository-mandated end of code changes; do not treat an older generated
report as evidence for the current working tree.

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

## PR #4 log export repair — 2026-10-07

Input: PR head `b515186a5b589ac378262628b3d5326a0b20d304`, base
`4c7b89a6418920067cb8ca2e2ede05f094cd0e0f`. Changes preserve the logging
feature while fixing two reviewed failures:

- Capture immutable log contents on the same executor that writes and rotates
  the files. Reports include retained segments once, oldest to newest. A
  timeout or read failure returns an export failure instead of reading live files.
- Give reports UUID-bearing names and atomically create each legacy destination
  file, so simultaneous Android 26–28 exports cannot overwrite one another.
  Concurrent first exports can safely share directory creation.
- Mark the guarded MediaStore helper as requiring API 29; the previous PR
  failed lint's NewApi check despite the caller's SDK-version guard.

Validation ran in the existing local image
`sha256:d3768cd434906067cbaa84e333c691e5256571f6dce1db04e5ef7f7cba901f35`
with SDK `/opt/android-sdk`, JDK 17, and this worktree mounted at `/workspace`.
No host SDK installation or SDK-path configuration was added to the repository.

```sh
./gradlew --no-daemon --console=plain \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:assembleRelease
```

- Final run: BUILD SUCCESSFUL in 1m 29s; 135 tasks, 28 executed and 107 up-to-date.
- Unit tests: 290 in 35 suites; zero failures, errors, or skipped tests.
  Includes three new tests for retention/order, snapshots during rotation,
  and simultaneous legacy exports with complete separate reports.
- Lint: zero errors, 22 warnings. Debug, instrumentation-test, and unsigned
  minified release APKs assembled; APK ZIP integrity passed.
- Device/emulator execution, real SSH failures, crash-handler export, and
  on-device Downloads visibility remain unverified. Build proof is not release
  or installation proof.
- Independent approval and published SHA are recorded outside this commit in
  the host merge-gate record and final task report; this section is test evidence.

| Unit | Inputs/dependencies | State/evidence | Reuse/rerun reason |
| --- | --- | --- | --- |
| Build environment | Existing pinned Android runtime image | Newly verified: container SDK supports all declared build tasks | Host SDK absent; used existing runtime without changing host configuration |
| Export repair | PR head, AppLog/LogExporter contract, new tests | Newly verified: serialized snapshots and separate complete legacy reports | Prior source review reused only as defect input; correctness tested against repaired source |
| Final verification | Final Kotlin sources and same runtime | Newly verified: 290 tests and lint passed; all three APK variants built | First run failed lint (3m 34s); corrected API annotation and test nullability, then reran tasks; Gradle reused unaffected inputs |

SDK investigation ran in parallel with source repair using one read-only
agent (parent model inherited, token count not exposed). Formal gate review
uses an independent latest Sol after committing the target. Wall time of the
first and corrected builds is not a controlled sequential-versus-parallel
comparison; no speedup claim is made.

## 도구·훅 이상 (별도 진행)

- 일시: 2026-10-07 (Asia/Seoul). 런타임: Codex.
  도구/훅: SDK 조사 에이전트의 exec_command / Rust-first PreToolUse.
  증상(원문): `Rust-first 규칙 위반: find -name/-iname/-type/-path/-regex 금지. 권장: fd <pattern> [path] 또는 fd -e <ext> (fd-find).`
  실측한 대체 수단: fd로 기존 SDK 경로 검색 완료; 기존 Docker 이미지의 SDK로 빌드 성공.
  [추정] 검색 명령 선택에 대한 정상 정책 차단임.
  상태: 열림. 훅 변경·우회 없이 조사 완료; 정책/도구 선택 재발 방지는 별도 작업.

- 일시: 2026-10-07 (Asia/Seoul). 런타임: Codex.
  도구/훅: Gradle SDK provisioning in existing runtime.
  증상(원문): `WARNING: platform-tools package is not installed, and automatic installation failed.`
  실측한 대체 수단: 기존 platform 36 및 build-tools로 빌드·단위 테스트·lint 성공.
  [추정] 컨테이너를 호스트 사용자 UID로 실행하여 이미지 SDK에 추가 설치할 수 없음.
  상태: 열림. platform-tools 및 기기 실행 환경 구성은 별도 작업이며 이 검증에서 변경하지 않음.

- 일시: 2026-10-07 (Asia/Seoul). 런타임: Codex.
  도구/훅: independent-review-record --begin / doc-claims.
  증상: 아래는 훅 진단의 인용이며 현행 경로 주장이 아님 (APK 기록 3곳).

  ```text
  문서가 스스로 반증하는 주장을 담고 있다 — 고치고 다시 --begin 하라:
  없는 폴더를 현행처럼 가리킨다: ~/coding/codex-remote-android
  ```

  실측한 대체 수단: 검증 문서가 당시 복사 기록임을 명시하고,
  존재하지 않는 현행 체크아웃 경로 대신 당시 저장소 내 파일명을 보존함.
  현재 APK 존재·해시는 재검증하지 않았다고 명시; 훅·설정은 변경하지 않음.
  [추정] 과거 경로 기록을 현행 경로 주장으로 판정한 정상 정책 차단임.
  상태: 열림. 문서 수정 완료; 경로/시점 판정의 재발 방지는 별도 작업.

Documentation-only follow-up after the successful build clarified historical
APK-copy records in two verification documents. Kotlin source, test source,
Gradle settings and runtime image remained unchanged; build evidence above
is reused for those unchanged inputs. The documentation itself is checked by
diff validation and the review recorder's document-claims check.

## Session closeout — 2026-10-07

Current status, source/derived-file boundaries and regeneration hashes,
deadlines, verified versus unverified facts, decision record, next actions,
user decisions, complete anomaly accounting and the original checkout's
append-only notes are consolidated in [SESSION_HANDOFF_20261007.md](SESSION_HANDOFF_20261007.md).
This closes source work on PR #4; the PR remains open and device validation is
not claimed. Original root worktree and historical APKs are preserved.

## PR #4 전체 병합 승인 — 2026-10-08

사용자는 이 PR의 기존 커밋 3개가 다른 작업 대화에서 작성됐지만 모두
이 프로젝트의 로깅·빌드·테스트 변경임을 확인한 뒤,
"이제 남은 판단은 이 PR 전체를 병합할지이고: 승낙함."이라고 명시적으로 승인했다.
따라서 이전 closeout의 PR 브랜치 한정 게시 경계는 이번 main 병합에 한해 해제된다.
이 승인은 독립 검토·정확한 SHA의 internal-ci 게이트를 생략하지 않는다.

- 확인한 입력: fork/main `4c7b89a6418920067cb8ca2e2ede05f094cd0e0f`,
  PR head `3b71a9af3d695f28c7950af0d5b63ec09c07d2ce`.
- 포함 범위: 기존 PR 커밋 `bf450ad`, `22c9d3e`, `b515186` 및
  이번 세션의 수정·문서 커밋 `f784b66`, `e06e687`, `a50eb99`, `3b71a9a`와 이 승인 기록.
- 방식: 별도 worktree에서 incoming SHA에 대한 최신 Sol 검토를 새로 받고,
  로컬 main을 fast-forward한 뒤 정상 Git push로 fork/main에 게시한다.
  GitHub UI/API merge, force push, rebase, cherry-pick, 게이트 변경을 사용하지 않는다.
- 원래 작업 사본 `1908c079`와 미커밋 HANDOFF, 기존 APK는 보존한다.
  실제 휴대폰 실행·설치·배포는 미검증이며 이번 승인의 수행 범위가 아니다.
- 승인 기록 시점의 상태: 병합 예정. 완료 여부와 최종 원격 SHA는
  이후 Git 출력·게이트 기록·PR 상태 및 최종 보고로 확인한다.

| 단위 | 입력·의존 대상 | 상태·근거 | 재사용/재실행 이유 |
| --- | --- | --- | --- |
| 병합 범위 확정 | live fork/main, PR head, 사용자 승인 | 새로 확인: diverged commit 없음; PR 전체 병합 승인 | 이전 PR-only 게시 경계에 대한 명시적 사용자 지시 |
| 검증·독립 검토 | 승인 기록을 더한 incoming SHA, 기존 pinned Android runtime | main 게시 범위로 검증·최신 Sol 검토 새로 수행 | 이전 Sol 승인은 main 게시를 제외했으므로 새 판정 필요 |
| 병합·게시 | APPROVE 수령·기록, 해당 SHA의 pre-push internal-ci | fast-forward 및 정상 push 후 원격/PR 상태 확인 | 승인·CI는 재사용으로 생략하지 않음 |

검증 뒤 검토, 검토 뒤 병합·push는 결과 의존 및 승인/공유 ref 쓰기 경계 때문에 순차 수행한다.
정식 검토 에이전트 1개 사용 예정: 최신 Sol (`gpt-6.1-sol`, high), 토큰 수는 도구 미제공.
