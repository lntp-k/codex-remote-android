# Shared daemon live sync and task full access — 0.1.9

Updated: 2026-08-10 (Asia/Seoul)

This record separates source and automated evidence from physical-device proof.
The signed APK is a QA candidate until the remaining device checks below are
performed.

## Outcome

Version `0.1.9` (`versionCode = 10`) adds the shared Codex app-server path needed
for bidirectional live task updates between Android and Codex Desktop when both
clients use the same remote host, managed daemon, and task. The status dialog
reports `Desktop live sync: On · shared daemon`; an incompatible host is visibly
reported as `Off · isolated` and retains persisted-history sync only.

The composer now exposes **Full access for this task**. Selecting it opens a
confirmation dialog before applying the exact authorization tuple
`:danger-full-access` + `never` + `user`. The dialog explains command, network,
and remote-account file access. If a turn is already running, the setting begins
with the next turn. Cancel does not change the task. Narrow
`acceptForSession` approvals remain separate and are labeled by kind for
commands, file changes, or additional permissions.

## Transport and ordering controls

- The preferred path runs `codex app-server proxy` inside authenticated,
  host-key-pinned SSH; Android does not expose an app-server listener.
- The WebSocket implementation validates the HTTP Upgrade, server masking rules,
  opcodes, fragmentation, UTF-8, and message limits. A post-upgrade protocol
  failure disconnects without replaying work into another server.
- Isolated JSONL fallback is allowed only before RPC begins. EOF with a partial
  record, even valid JSON lacking its line delimiter, now fails closed.
- Resume events are queued without blocking the sole RPC reader, then applied
  after the resume snapshot in wire order. Events arriving during the drain stay
  ordered. The queue is bounded to 512 events and an estimated 16 MiB; overflow
  disconnects instead of dropping or splicing state.
- Leaving a loading task through new task, project selection, task selection,
  fork, archive, or refreshed-list selection changes invalidates the old resume
  epoch and drains authoritative events without applying the stale snapshot.
- Optimistic Android messages use `clientUserMessageId`; a same-text message from
  Desktop remains a distinct authoritative item and cannot be claimed as the
  phone's pending send.

## Runtime and review evidence

A two-client managed-daemon probe established real fan-out: after both proxy
clients resumed the same task, client B received client A's turn-started, user
item, agent item, and turn-completed events. This verifies the daemon transport
mechanism independently of Android UI execution.

Codex Security diff scan `59c74867-4f7e-4caf-83f1-cde3908f5790` reviewed the
pre-remediation working-tree snapshot
`codex-security-snapshot/v1:sha256:e9cd589935e1fef65331124d8492991f1863631d1480b5132b31bbe2f7a48bd0`
with complete coverage and zero reportable security findings. It reproduced two
reliability defects—JSONL delimiter handling and resume-event backpressure—which
were fixed afterward and covered by regression tests. A separate concurrency
review then found and closed the abandoned-resume navigation paths, including
task forks.

## Final automated gate

| Check | Result |
| --- | --- |
| Clean build | Passed in 1m 6s; 137 actionable tasks (135 executed, 2 up-to-date) |
| JVM unit tests | 287 tests in 34 suites; 0 failures, 0 errors, 0 skipped |
| Focused shared-daemon/UI regression gate | 36 tests; 0 failures; Android-test Kotlin compiled |
| Android instrumentation APK | Compiled and assembled; tests not executed because no device/emulator was attached |
| Lint | 0 errors, 22 warnings: 10 `GradleDependency`, 6 `NewerVersionAvailable`, 3 `UseKtx`, and one each of `TrustAllX509TrustManager`, `ObsoleteSdkInt`, and `AndroidGradlePluginVersion` |
| Signed minified release APK | 3,648,600 bytes; SHA-256 `0ff0fbf27b6ef5b22a444e51156efc4cfbf47cb1cbe198dabcdd3203bfd6e5a5` |
| Debug APK | 22,315,507 bytes; SHA-256 `1a6078812f527eb8d2f3f1119f2e9c1eeb49eb62b54b5952509a962197ef3af4` |
| Android-test APK | 1,100,513 bytes; SHA-256 `ca6395972b44b5559db908a66bc0fa3a3a990659d44c541dec4e92948c117901` |
| Release signature | APK Signature Scheme v2; one RSA-4096 signer |
| Signer continuity | Certificate SHA-256 `253257e7a3f4a1175b5eed656470e764efad2072252442cee617f99bb2bd1d20`, exact match to retained `0.1.8` and earlier candidates |
| Package metadata | `com.codex.remote`; version `0.1.9` / code 10; compile/target/min SDK `36` / `36` / `26` |
| APK integrity and alignment | ZIP integrity passed; all 8 native libraries in debug and release have exact ABI-required stored offsets; Android-test has no native library |
| Native ELF alignment | Every `PT_LOAD` segment in all 8 release native libraries meets the 16 KiB 64-bit or 4 KiB 32-bit minimum |

Release signing material was read from the existing external signing store and
was not copied into the repository or artifact destinations.

The validated release output and both retained copies were read back with the
same SHA-256 digest shown above:

- `/home/jl/coding/codex-remote-android/codex-remote-android-v0.1.9.apk`
- `/home/jl/mnt/dropbox-personal/codex-remote-android-v0.1.9.apk`

## How to use the new controls

1. Install `codex-remote-android-v0.1.9.apk` over the existing signed app.
2. Connect Android to the same remote Codex host used by Desktop and open the
   same task on both clients.
3. In Android connection status, confirm `Desktop live sync: On · shared daemon`.
4. Open the composer permission menu, select **Full access for this task**, read
   the warning, and choose **Enable full access**. Select **Ask** later to restore
   per-operation prompts for future turns.

If the status says `isolated`, the remote Codex build does not expose the
compatible managed-daemon surface. Messages remain in remote history but an
already open Desktop task will not receive phone events live.

## Remaining device gates

- Overwrite-install `0.1.9` on the actual phone and confirm saved SSH connection
  data remains present.
- Open one task simultaneously on Android and Desktop and visually confirm both
  Desktop-to-phone and phone-to-Desktop messages without reopening either task.
- Confirm full-access cancellation, next-turn wording, activation, and return to
  Ask mode on the physical device.
- Exercise real password/private-key SSH, background/foreground, screen-off,
  Wi-Fi/cellular/VPN handoff, fork, archive, and reconnect paths.

Automated compilation, signatures, and protocol probes do not replace those
physical-device checks.
