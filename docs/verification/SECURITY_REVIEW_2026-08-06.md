# Security review and remediation record -- 2026-08-06

## Status

**Targeted remediation implemented; fresh post-fix attestation pending.**

The Codex Security diff scan reviewed a pinned working-tree snapshot based on
commit `acd38eb736df66051461b373642bcc425804eb56`. It ran **before** the fixes
described below. The report found four low-severity/P3 issues and classified
coverage of its own snapshot as complete. It also retained two engineering
defects that did not meet the security-finding threshold.

Subsequent source and regression changes directly address those findings. This
record maps the old findings to the new controls, but it is not a rescan and
does not close them as a formal post-fix security result. A final scan must be
pinned to the final commit and artifacts.

## Scan identity

| Field | Value |
| --- | --- |
| Scan ID | `d6ee68d8-fde7-4a80-ba0c-03360c933e94` |
| Mode | `working_tree` / `git_diff` |
| Base revision | `acd38eb736df66051461b373642bcc425804eb56` |
| Remediation implementation | `d9cd9f1` (not covered by this formal scan) |
| Snapshot digest | `codex-security-snapshot/v1:sha256:8ce2b6dadb06f42aa0774102a298bd91daa05530366393e8846aad68224961ef` |
| Findings | 4 low/P3 |
| Confidence | 2 high, 2 medium |
| Snapshot coverage | Complete |
| Manifest SHA-256 | `e0a4f2925e3c2df92e250e9f0ae27287004b2a794ce35cd279f4ba520fd336ff` |
| Findings SHA-256 | `d793c1295f975249bb4a46504781a237919c4dceb4d778230d3a597a482d2def` |
| Coverage SHA-256 | `64eeb193b8ab5cc5adfab44d529f2b5869e84c69d9906b33cc07da57ee56aea6` |
| Report SHA-256 | `880df793aa29ff905a04e7431d3dc92226ef630befaa5b30f036edacda1fc5be` |

The scan threat model treated the authenticated SSH/app-server peer,
repository-driven content, protocol bytes, Android content-provider input, and
build dependencies as untrusted. Protected assets included SSH credentials and
host identity, connection/thread/turn/approval integrity, Android availability,
remote authority reachable through Codex, and signed APK integrity.

## Findings and current remediation evidence

| Pre-fix finding | Severity | Current working-tree control | Status |
| --- | --- | --- | --- |
| Session history retention lacked an aggregate Android heap budget | Low/P3 | Central per-session and aggregate retained-state limits, saturating accounting, atomic rejection, bounded cursors/diagnostics, and deterministic eviction only of eligible inactive sessions | Addressed with targeted JVM regressions; device heap proof pending |
| Full-access approval policy persisted across hosts and sessions | Low/P3 | Connection/uncached-session transitions use the safe `:workspace` / `on-request` / `user` tuple; restored remote settings remain target-scoped | Addressed with authorization/projection regressions; real SSH proof pending |
| Background approval could be authorized under another task's visual context | Low/P3 | Approval carries exact owner thread/request identity; owner title/path/ID is shown; Allow is disabled for background or unknown owner; Deny and Open owning task remain available | Addressed with JVM presentation and Android test sources; device execution pending |
| Superseded SSH callbacks could mutate the successor host view | Low/P3 | Async publications require the exact RPC client and connection generation; session loads, selection-sensitive work, and turn starts carry bounded ownership tokens | Addressed with request/projection regressions; injected cross-host scheduler proof pending |

### 1. Aggregate retained-state controls

`SessionRegistry` is now the central retained-session mutation boundary. The
default limits include:

- at most 32 cached sessions;
- 512 items and 4 Mi character units per timeline;
- 16 Mi aggregate retained character units;
- 2,048 aggregate timeline items;
- 512 aggregate history cursors; and
- separate count/length limits for per-session cursors and diagnostics.

The calculation includes remote thread metadata, timeline text/diffs, history
cursors/errors, goal/settings data, active/expected turn IDs, approval payloads,
and diagnostics. Arithmetic saturates instead of overflowing. Admission and
updates first prove that the complete candidate fits. Eviction is deterministic
and cannot remove the selected session or one with a running turn, pending
approval, or active goal. If protected saturation leaves no safe candidate, the
mutation is rejected atomically.

Regression coverage under `app/src/test/java/com/codex/remote/session/` includes
per-session overflow, aggregate character/item/cursor budgets, metadata and
approval accounting, active-goal protection, atomic eviction rollback, and
saturating arithmetic.

### 2. Target-scoped authorization

Permission profile, approval policy, and reviewer are treated as one effective
authorization context. A new SSH connection and an uncached target begin with
safe workspace-write/on-request/user behavior. Nullable settings from one
remote target are not allowed to revive another target's previous full-access
choice. This prevents a prior `never` policy from implicitly synthesizing
danger-full-access for a new host or thread.

Projection regressions cover disconnect, missing remote authorization fields,
complete target-scoped restoration, and the request builders' effective
authorization tuple.

### 3. Approval owner context

Approval storage is session-owned and indexed by exact thread plus queue/RPC
request identity. The global surface preserves arrival order without changing
ownership. Presentation derives owner metadata only from an exact known remote
thread and shows the task title, path, and ID.

If the approval belongs to a background session, Allow is disabled until the
user opens that exact task. If the owner is unknown, an ID match to the current
selection is insufficient and the surface remains fail-closed. Denial is still
available so an untrusted or orphaned request cannot force navigation before it
can be safely rejected.

JVM tests cover selected, background, unknown, ID-only, and missing-owner
presentations. Android test sources cover the visible owner block, disabled
Allow, enabled Deny, and opening the owning task. Those Android tests have been
compiled but have not yet run on a device/emulator.

### 4. Superseded callback isolation

Connection changes invalidate a monotonically increasing generation. Async
work must prove both exact client identity and captured generation before it
updates registry or UI state. Per-thread load tokens prevent an older resume or
history response from replacing a newer one, while selection tokens prevent
draft-sensitive work from publishing after navigation. Turn-start tokens
reconcile response/notification ordering without allowing a superseded start
to roll back or reopen a newer operation.

Regression coverage includes same-thread load supersession, reconnect
invalidation, navigation invalidation, notification-before-response,
completion-before-response, and turn-start rollback ownership. A deterministic
injected test that pauses a completed host-A continuation across a reconnect to
host B remains a release follow-up.

## Engineering defects retained from the scan

The scan rejected these as security findings after attack-path analysis, but
kept them as reliability defects:

1. **Resumed active-turn identity loss.** An in-progress remote turn with no
   timeline items could lose its turn ID. Resume/fork parsing now preserves one
   exact authoritative active turn ID even when the turn contains no items;
   completed, malformed, or ambiguous candidates yield no active identity.
2. **Turn-start response race.** A start notification/completion could arrive
   before the RPC response, allowing late response handling to reopen or
   conflict with terminal state. `SessionRequestTracker` now models applied,
   already-observed, terminal, superseded, and conflict outcomes and has
   ordering regressions.

These changes passed the final clean automated test gate and remain subject to
the fresh scan, signed-release, and device/runtime gates.

## Additional independent post-scan review

Separate source reviews after the scan found eight reliability and ownership
boundaries in the new implementation. They were fixed before the final clean
build:

1. `/init` captures the exact thread/project selection and abandons its delayed
   existence-check result after navigation, so it cannot send the prompt into
   a neighboring task or running turn.
2. A background refresh advances selection ownership when only a draft project
   path changes; a delayed `thread/start` result cannot combine the old thread
   with the new project's path.
3. Conflicting turn IDs from a start notification and response disconnect
   fail-closed. A bounded recent-completion set also prevents a late duplicate
   start notification from rebinding a completed turn to a newer operation.
4. If a history-page state update is rejected at a retention boundary, both
   registry and projected UI clear the loading latch. Failure to restore that
   reducing mutation disconnects instead of leaving pagination stuck.
5. Thread, turn, goal, login, permission-profile, and response identifiers use
   strict nonblank string parsing. Resume accepts only an authoritative active
   turn or a cached identity still known to be live; stale timeline status is
   never promoted into turn ownership.
6. Turn completion/failure removes pending approvals and preserves only an
   already-responding entry for resolution tracking. A second, conflicting
   turn start is treated as split-brain and recommends disconnect, while a late
   stale completion remains a drop-only event.
7. An uncached session derives model, effort, service tier, collaboration mode,
   and the safe authorization tuple from the remote catalog/defaults instead
   of the previously selected task. Server-reported active threads cannot be
   archived even when absent from the local cache. Approval acceptance also
   requires the exact selected owner and its exact live turn in both UI and
   response dispatch.
8. Failure parsing distinguishes an exact turn ID, a genuinely absent legacy
   ID, and a present-but-malformed ID. Only the genuinely absent legacy form
   may bind to one sole current turn; malformed/null/blank IDs are rejected
   with a disconnect recommendation and cannot terminate a newer turn or purge
   its approvals.

Focused coverage is in `AppViewModelSafetyTest`, `SessionProjectionTest`,
`SessionRequestTrackerTest`, `SessionEventRouterTest`, and
`CodexRpcParsingTest`. A final independent rereview reported no remaining
P0-P3 item in this bounded change set. That rereview is not a replacement for
the fresh formal post-fix scan required below.

## Build-path controls reviewed

The reviewed CI path has read-only repository permissions, full-SHA action
pins, Gradle wrapper/distribution checksum verification, explicit Android SDK
packages, empty production signing variables, and an assertion that the
minified release remains unsigned. The current workflow also assembles the
Android-test APK, checks all APK ZIPs and 16 KiB alignment, verifies expected
debug/test signatures, requires verification of the unsigned release to fail,
and hashes every APK plus the R8 mapping.

These controls strengthen provenance but do not replace production signing,
signer continuity, artifact publication controls, or device installation.

## Remaining validation and limitations

- Final clean automated gate: 212 JVM tests in 24 suites with no
  failures/errors/skips; lint completed with 0 errors and 19 recorded warnings;
  debug, Android-test, unsigned release, and R8 mapping hashes are recorded in
  `ANDROID_16_MIGRATION.md`.
- No fresh post-fix Codex Security scan has been run against the final commit.
- No physical Android device, ART heap/OOM threshold, or runtime instrumentation
  execution was used.
- No production-signed release or upgrade installation over `0.1.6` was tested.
- No real password/private-key SSH and Codex app-server session was exercised.
- The narrow completed-response cross-host scheduler race was not reproduced
  with an injected RPC client in the pre-fix scan.
- Remote Codex, the SSH server, and behavior confined to an intentionally
  selected compromised remote host remain outside this Android repository
  unless the client crosses a local ownership boundary.

## Required closure gate

Before release promotion:

1. pin the reviewed implementation commit and retain the final clean build
   values already recorded in `ANDROID_16_MIGRATION.md`;
2. run a new diff/full security scan pinned to the final commit and retain its
   manifest/findings/coverage/report digests;
3. execute the approval/session instrumentation path and memory-pressure checks
   on a representative Android device;
4. complete the real SSH/app-server critical path; and
5. build with authorized production signing, verify signer continuity, and
   install as an upgrade over `0.1.6`.

Only that later evidence may change this record from "targeted remediation" to
"post-fix verified".
