# Architecture

## Current implementation boundary (2026-08-10)

The direct Android-to-SSH topology remains unchanged. The current branch adds
the Paseo-inspired **multi-session state foundation**, not a relay, Android
agent runtime, persistent remote daemon, or general remote shell API.

Implemented in this branch:

- Android 16 production source baseline: AGP 8.13.2, Gradle 8.13,
  `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`, JDK/JVM 17;
- exact per-thread session state and event routing;
- bounded history/session retention and deterministic eligible eviction;
- connection-, selection-, load-, and turn-start-owned asynchronous results;
- per-task session indicators; and
- a global sequential approval surface with exact owner context and
  fail-closed background approval behavior;
- a process-wide connection owner anchored by a user-visible foreground
  service; and
- default-network monitoring with safe-boundary SSH/app-server handoff
  and remote task reconciliation.

Not yet established by this implementation:

- a complete multi-session dashboard or grouped approval inbox;
- durable remote execution after the SSH app-server process itself exits;
- Workspace/worktree lifecycle, terminal sessions, or rich Git mutation UI;
- actual device upgrade/data-preservation compatibility (the signed `0.1.8`
  candidate has the same package and signer as `0.1.7`/`0.1.6` and a higher
  version code, so the static upgrade prerequisites are established); or
- physical-device, Android instrumentation runtime, and real SSH smoke proof.

## Source audit

This client follows the public Codex app-server contract rather than embedding
an agent on Android.

- Codex source inspected at `openai/codex` commit
  `61a44880a85d2fd0d8770908dea5733495e571c8` (2026-07-26).
- Protocol definitions come from `codex-rs/app-server-protocol`.
- Server behavior comes from `codex-rs/app-server`.
- Desktop behavior and command registration were audited from Codex Desktop
  `26.721.4979.0`; Android keeps only actions that can operate on the SSH host.
- `codex-rs/app-server-daemon/README.md` explicitly describes SSH-launched
  app-server instances used by desktop and mobile remote clients.
- `codex-rs/app-server-protocol/src/protocol/v2/thread.rs` defines host-wide
  `thread/list` discovery, `cwd` filtering and opaque cursor pagination.
- `codex-rs/app-server/tests/suite/v2/thread_list.rs` verifies `nextCursor`
  pagination and shows that `cwd: None` does not restrict results to one project.
- Desktop sends an empty `sourceKinds` filter for its user-facing thread list;
  Android mirrors that behavior and follows every `nextCursor` page.

## Runtime flow

```text
Android UI
   |
   | foreground-service-owned lifetime
   | default-network callback + SSH handshake (verify/pin SHA-256 host key)
   v
Remote login shell
   |
   | codex app-server --listen stdio://
   v
JSONL transport over SSH stdin/stdout
   |
   +-- initialize / initialized
   +-- thread/list (all cursor pages, no cwd filter)
   +-- thread/start, thread/resume, thread/fork, thread/compact/start
   +-- thread/archive, thread/unarchive, thread/delete
   +-- thread/goal/get, thread/goal/set, thread/goal/clear
   +-- thread/metadata/update (remote pin state)
   +-- turn/start, turn/steer, turn/interrupt
   +-- review/start, collaborationMode/list
   +-- account/read, account/login/start
   +-- model/list (all cursor pages)
   +-- skills/list, plugin/installed, mcpServerStatus/list
   +-- mcpServer/oauth/login, config/mcpServer/reload
   +-- feedback/upload (remote thread id and remote logs)
   +-- item and turn streaming notifications
   +-- command, file-change, permission and user-input approvals
```

Agent execution, repository access, authentication, tools and approvals remain
owned by the remote Codex installation. Android has no local agent runtime.

## Background lifetime and network recovery

`CodexRemoteApplication` owns the single process-wide `AppViewModel`, while
`SshConnectionService` keeps the user-requested connection visible and active
when no Activity is on screen. The service uses the `specialUse` foreground
service type because connections may target arbitrary SSH hosts rather than a
single local-device category. Its persistent notification opens the app and
offers an explicit Disconnect action.

`ConnectivityManager.registerDefaultNetworkCallback` tracks Android's current
default network without requiring `NET_CAPABILITY_VALIDATED`; this avoids
filtering out private/VPN routes such as Tailscale. A guarded delayed snapshot
handles a cold offline start without allowing a stale snapshot to overwrite a
newer callback. A dial also records its `activeNetwork` handle, so the initial
callback or a resubscription callback does not cause a duplicate SSH bootstrap
when it reports the same route.

An established TCP socket cannot migrate to a new default network. After a
500 ms debounce, a genuinely changed route therefore requires a fresh
SSH/app-server connection. If any turn or approval is active, one polling job
defers the handoff until that protected work reaches a safe boundary. Other
pending RPCs receive a five-second completion grace; if the grace expires the
client reconnects without replaying the operation and tells the user to verify
its result. New turns, reviews, and forks are temporarily rejected while a
handoff is pending. A responding approval whose delivery becomes ambiguous
still fails closed rather than being replayed.

When no replacement default network exists, the current transport is left open
until it fails, but new reconnect dials wait for network availability. SSHJ's
reply-checked protocol keepalive runs every 15 seconds and treats three
unanswered requests as a lost connection, so a black-holed old route eventually
reaches the normal transport-failure recovery path without semantic health RPCs.
Because Android Doze suspends network access even for an active foreground
service, `ACTION_DEVICE_IDLE_MODE_CHANGED` pauses scheduled dials without
spending the retry budget and performs an immediate recovery check after wake.

Transport failures retry after 1/2/4/8/16/30-second capped delays. Timers canceled
by callback churn do not consume the retry budget; only actual SSH dials count,
automatic dialing pauses after ten consecutive attempts, and the counter resets
after a 30-second stable connection. The foreground monitor and exact desired
connection marker remain active at the circuit breaker, so a new default-network
identity resets the budget and makes one immediate recovery attempt. Device wake
resumes a budget that Doze paused, but does not reset an already-open circuit
breaker. Generation checks prevent late failures or stale timers from replacing
a newer healthy connection.

The requested connection ID is committed before the foreground service starts,
so `START_STICKY` process recreation restores the exact intended host rather
than whichever host was last used successfully. Explicit Disconnect and terminal
failures clear that intent. A missing/corrupt target removes the foreground
notification and stops with `START_NOT_STICKY` instead of leaving an orphan
service. SSH shutdown is detached on the main thread and closed on an I/O scope
to avoid blocking the Android UI on a dead route.

Reconnect always creates a new SSH process and app-server protocol session,
reruns initialization and host-key verification, reloads remote threads, and
resumes the previously selected thread from remote truth. It does not replay an
in-flight user message or approval. If transport loss made connected work
ambiguous, the restored UI explicitly asks the user to verify the remote result.
Host-key, authentication, remote setup,
protocol-identity, ownership, and ambiguous approval-delivery failures suspend
automatic recovery and require explicit user action.

This is logical session recovery, not TCP migration or durable remote
execution. Android may still stop the app through force-stop/Task Manager, and
a tunnel transition can terminate the SSH-bound app-server (including an
in-flight turn). True uninterrupted remote work still requires the separately
gated persistent daemon/relay design described in Phase 4 of the handoff.

## Session state and exact event routing

```text
CodexRpcClient
  -> strict AppServerEvent parsing
    -> SessionEventRouter(threadId, turnId, request identity)
      -> SessionRegistry.sessions[threadId]
        -> SessionState
          timeline/history cursors
          goal and remote settings snapshot
          active/expected turn identity
          session-owned approval queue
          unread/diagnostic/stream status
      -> selected-session compatibility projection
        -> AppUiState -> Compose UI
```

`SessionRegistry` is the mutation boundary for retained session state. It is
keyed by immutable remote thread IDs; selection is presentation state and does
not decide event ownership. `SessionEventRouter` binds thread-scoped events to
the exact known thread. Missing or unknown thread IDs, missing or stale turn
IDs, ownership conflicts, duplicate approval IDs, and capacity failures produce
bounded diagnostics and are not applied to whichever task happens to be open.

The selected session is projected into the existing `AppUiState` so the
current workspace UI can remain mostly compatible while background sessions
continue to retain independent timelines, turns, approvals, goals, settings,
and unread state. This projection is transitional: `AppViewModel` remains a
large orchestration layer and a dedicated dashboard has not been added.

### Active-turn authority

Resume and fork parsing preserve an active turn only when one exact remote turn
is authoritatively active. The client does not infer a running turn merely from
old timeline item status. Streaming events must match the session's exact
active/expected turn ID; stale completion or delta events cannot finish or
append to a newer turn.

Failure events preserve whether a turn ID was exact, genuinely absent in a
supported legacy error notification, or present but malformed. Only the
genuinely absent legacy case may bind to one sole current turn. Malformed or
blank IDs are rejected with a disconnect recommendation instead of being
re-attributed to the visible/current turn. Terminal turn events discard
pending approvals while retaining already-responding entries only long enough
to reconcile their resolution.

`SessionRequestTracker` assigns connection generations and request sequences to
asynchronous loads and starts. A callback must still belong to the same RPC
client and generation before it can publish state. Selection-sensitive work
also carries selection ownership. This prevents an old SSH connection or an
older task load from overwriting the successor connection/session view.

### Retention and eviction

Peer-controlled retained state is bounded centrally. Current default ceilings
include 32 cached sessions, 512 timeline items and 4 Mi character units per
timeline, 16 Mi aggregate retained character units, 2,048 aggregate timeline
items, and 512 aggregate history cursors. Individual cursor count/length and
diagnostic count/length are also bounded. Accounting uses saturating arithmetic.

When aggregate capacity is needed, only the deterministic least-recently-used
eligible session can be evicted. The selected session and sessions with a
running turn, pending approval, or active goal are protected. If no eligible
session can make the candidate fit, the mutation is rejected atomically rather
than partially evicting or retaining oversized data.

These are logical retention budgets, not a claim of measured Android heap
headroom. A physical-device/ART memory test remains required before release.

## Approval ownership and presentation

Each approval is stored under a composite owner: exact thread ID plus queue/RPC
request identity. A global arrival order provides one sequential approval
surface without merging ownership. Command, file-change, permission, and user
input approvals must match the owning session and active turn; legacy approval
methods without a turn ID bind only when there is exactly one active turn.

The UI shows the approval's owning task title, path, and task ID. If that owner
is a background task or cannot be proven from known thread metadata, **Allow**
is disabled. The user may deny it or explicitly open the owning task first.
This is Phase 2a only: grouped inbox navigation, safe bulk denial, notifications,
deep links, and process-recreation restoration are not implemented.

## Host-wide discovery

A saved connection stores only SSH host, authentication and platform details.
On connection, the client requests every page of non-archived interactive
threads with the same empty `sourceKinds` filter as Desktop. It deliberately
omits the optional `cwd` field from `thread/list`, follows `nextCursor` until it
is null, and deduplicates thread IDs across overlapping pages.

Projects are a UI projection of the returned threads grouped by normalized
`Thread.cwd`; they are not stored in the SSH connection. Resuming a conversation
uses that thread's own `cwd`. Starting a conversation uses the selected imported
project's path.

The model picker is populated exclusively from the remote `model/list` catalog,
including each model's reasoning choices, input modalities, service tiers and
defaults. Plan mode comes from `collaborationMode/list`; permissions come from
`permissionProfile/list`. Text, structured skill/plugin mentions and image data
are sent as official `UserInput` objects. Follow-up messages sent while a turn
is active use `turn/steer` with the active `expectedTurnId`.

Existing remote API-key and ChatGPT accounts are read through `account/read`.
When the remote host requires ChatGPT authentication, the Android client starts
the official device-code flow through `account/login/start`; credentials remain
owned by the remote Codex installation.

Archived conversations are fetched on demand with the same host-wide,
cursor-paginated `thread/list` query and `archived: true`. Restoring and
permanently deleting them use `thread/unarchive` and `thread/delete`; deletion
is always guarded by a confirmation dialog. Context compaction is available
both as `/compact` and as an explicit current-task menu action.

The MCP status view can reload the remote MCP configuration and start the same
OAuth request used by Desktop. Android opens the returned authorization URL in
the system browser and waits for `mcpServer/oauthLogin/completed`; SSH hosts
whose OAuth provider redirects to loopback must configure a reachable
`mcp_oauth_callback_url` or an SSH tunnel on the remote host.

## Security model

- Passwords, private keys and passphrases are encrypted with an AES-GCM key
  generated inside Android Keystore.
- Android backup and device transfer are disabled for all app data domains.
- A new SSH host is rejected before authentication and its SHA-256 fingerprint
  is shown for explicit confirmation. A changed key is always blocked.
- app-server uses stdio inside SSH. No app-server TCP listener is exposed.
- The remote thread starts with `workspace-write` sandboxing and `on-request`
  approval by default. Named permission profiles are loaded from the host.
  Explicit full access maps to the app-server `dangerFullAccess` policy.

## Compatibility boundary

The app uses stable app-server methods and tolerant JSON parsing. Unknown item
types are ignored, while unknown server-initiated requests are surfaced rather
than automatically approved. Because schemas are tied to the installed Codex
version, the Android protocol layer should be tested whenever the remote Codex
installation is upgraded across major protocol changes.

Current SSH connection setup supports direct password and private-key hosts.
OpenSSH config expansion, ProxyJump, hardware-backed SSH agents and managed
Remote Control relay pairing are not implemented.

SSHJ's `curve25519` key-exchange factories are excluded on Android because the
platform JCA does not expose the `X25519` key-pair generator expected by SSHJ.
The client retains the interoperable ECDH and DH group14 families rather than
failing before host-key verification.
