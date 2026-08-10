package com.codex.remote

import android.app.Application
import android.net.ConnectivityManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codex.remote.data.rpc.AppServerEvent
import com.codex.remote.data.rpc.CodexRpcClient
import com.codex.remote.data.rpc.FailureKind
import com.codex.remote.data.rpc.RpcException
import com.codex.remote.data.ssh.HostKeyChangedException
import com.codex.remote.data.ssh.RemoteCodexUnavailableException
import com.codex.remote.data.ssh.SshAppServerTransportFactory
import com.codex.remote.data.ssh.UnknownHostKeyException
import com.codex.remote.data.store.ConnectionStore
import com.codex.remote.connection.ConnectionMaintenanceStore
import com.codex.remote.connection.NetworkHandoffDisposition
import com.codex.remote.connection.NetworkRecoveryAction
import com.codex.remote.connection.NetworkTransition
import com.codex.remote.connection.NetworkTransitionTracker
import com.codex.remote.connection.ReconnectAttemptLedger
import com.codex.remote.connection.ReconnectReadiness
import com.codex.remote.connection.SshConnectionService
import com.codex.remote.connection.availableNetworkRequiresHandoff
import com.codex.remote.connection.networkHandoffDisposition
import com.codex.remote.connection.reconnectReadiness
import com.codex.remote.connection.networkRecoveryAction
import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.ApprovalFileItemKey
import com.codex.remote.domain.ApprovalQueue
import com.codex.remote.domain.ApprovalQueueKey
import com.codex.remote.domain.ApprovalRequest
import com.codex.remote.domain.ConnectionDraft
import com.codex.remote.domain.ConnectionStatus
import com.codex.remote.domain.ComposerMention
import com.codex.remote.domain.ComposerMentionKind
import com.codex.remote.domain.ComposerImageAttachment
import com.codex.remote.domain.PermissionMode
import com.codex.remote.domain.RemoteCollaborationMode
import com.codex.remote.domain.RemoteProject
import com.codex.remote.domain.RemoteAccount
import com.codex.remote.domain.RemoteModel
import com.codex.remote.domain.RemotePathEntry
import com.codex.remote.domain.RemoteServerInfo
import com.codex.remote.domain.RemoteThread
import com.codex.remote.domain.ReviewTargetKind
import com.codex.remote.domain.SavedConnection
import com.codex.remote.domain.TimelineItem
import com.codex.remote.domain.TimelineKind
import com.codex.remote.domain.ThreadGoalStatus
import com.codex.remote.domain.ThreadSessionIndicator
import com.codex.remote.domain.approvalFileSnapshotRetainedCharCount
import com.codex.remote.domain.fileApprovalSnapshotOrNull
import com.codex.remote.domain.groupThreadsByProject
import com.codex.remote.domain.mergeTimelineHistory
import com.codex.remote.domain.composerToken
import com.codex.remote.domain.containsComposerToken
import com.codex.remote.domain.withThreadArchived
import com.codex.remote.domain.withThreadRenamed
import com.codex.remote.session.OwnedApproval
import com.codex.remote.session.SessionEventRouter
import com.codex.remote.session.SessionRegistry
import com.codex.remote.session.SessionRegistryMutation
import com.codex.remote.session.SessionRequestTracker
import com.codex.remote.session.SessionRouteDisposition
import com.codex.remote.session.SessionSettings
import com.codex.remote.session.SessionState
import com.codex.remote.session.SessionStreamStatus
import com.codex.remote.session.TurnStartToken
import com.codex.remote.session.TurnStartResponseDisposition
import com.codex.remote.session.retainedCharacterCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.SSHException

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ConnectionStore(application)
    private val maintenanceStore = ConnectionMaintenanceStore(application)
    private val connectivityManager = application.getSystemService(ConnectivityManager::class.java)
    private val powerManager = application.getSystemService(PowerManager::class.java)
    private val transportFactory = SshAppServerTransportFactory(application)
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    private val transportCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var rpc: CodexRpcClient? = null
    private var eventJob: Job? = null
    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectStabilityJob: Job? = null
    private var networkHandoffJob: Job? = null
    private var sessionRegistry = SessionRegistry()
    private val sessionRequestTracker = SessionRequestTracker()
    private val reconnectAttempts = ReconnectAttemptLedger()
    private val networkTransitions = NetworkTransitionTracker()
    private var shouldMaintainConnection = false
    private var hasObservedNetworkState = false
    private var networkHandoffPending = false
    private var activeConnectionAttemptNetworkId: Long? = null
    private var reconnectHadUnconfirmedWork = false
    private var reconnectCircuitBreakerPaused = false
    private var isDeviceIdleMode = powerManager.isDeviceIdleMode
    private var pendingReconnectSelection: ReconnectSelection? = null
    private var didRestoreLastConnection = false
    // Keep response flush/completion and inbound approval enqueue in one order so a wire ID
    // cannot be reused against an entry that is still locally outstanding.
    private val approvalFlowMutex = Mutex()

    init {
        viewModelScope.launch {
            store.connections.collect { connections ->
                val sortedConnections = connections.sortedByDescending { it.lastUsedAt }
                val connectionToRestore = if (!didRestoreLastConnection) {
                    didRestoreLastConnection = true
                    val desiredConnectionId = maintenanceStore.desiredConnectionId()
                    sortedConnections.desiredConnectionOrNull(desiredConnectionId).also { desired ->
                        if (desiredConnectionId != null) {
                            if (desired == null) {
                                val cleared = maintenanceStore.clear()
                                Log.e(
                                    CONNECTION_LOG_TAG,
                                    "state=maintenance_target_missing clear_persisted=$cleared",
                                )
                                SshConnectionService.stop(getApplication())
                            }
                        }
                    }
                } else {
                    null
                }
                val restoreConnection = connectionToRestore?.takeIf { _state.value.activeConnection == null }
                _state.update { current ->
                    val refreshedActive = current.activeConnection?.let { active ->
                        connections.firstOrNull { it.id == active.id } ?: active
                    }
                    current.copy(
                        savedConnections = sortedConnections,
                        activeConnection = restoreConnection ?: refreshedActive,
                        connectionStatus = if (restoreConnection != null) ConnectionStatus.CONNECTING else current.connectionStatus,
                        connectionMessage = if (restoreConnection != null) {
                            "Connecting to ${restoreConnection.host}…"
                        } else {
                            current.connectionMessage
                        },
                        isRestoringLastConnection = false,
                    )
                }
                restoreConnection?.let(::connect)
            }
        }
    }

    fun showConnections(show: Boolean = true) = _state.update {
        it.copy(showConnections = show, showConnectionEditor = false, editingConnection = null)
    }

    fun editConnection(connection: SavedConnection? = null) = _state.update {
        it.copy(showConnections = true, showConnectionEditor = true, editingConnection = connection, notice = null)
    }

    fun closeEditor() = _state.update { it.copy(showConnectionEditor = false, editingConnection = null) }

    fun saveConnection(draft: ConnectionDraft, connectAfterSave: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(isBusy = true, notice = null) }
            runCatching {
                store.save(draft, _state.value.editingConnection)
            }.onSuccess { saved ->
                _state.update {
                    it.copy(
                        isBusy = false,
                        showConnectionEditor = false,
                        editingConnection = null,
                        showConnections = !connectAfterSave,
                    )
                }
                if (connectAfterSave) connect(saved)
            }.onFailure(::showError)
        }
    }

    fun deleteConnection(connection: SavedConnection) {
        viewModelScope.launch {
            if (_state.value.activeConnection?.id == connection.id) disconnect()
            store.delete(connection.id)
        }
    }

    fun connect(connection: SavedConnection) {
        didRestoreLastConnection = true
        if (!maintenanceStore.remember(connection.id)) {
            Log.e(CONNECTION_LOG_TAG, "state=maintenance_target_persist_failed")
            _state.update { current ->
                if (
                    current.activeConnection != null &&
                    current.connectionStatus != ConnectionStatus.DISCONNECTED &&
                    current.connectionStatus != ConnectionStatus.ERROR
                ) {
                    current.copy(notice = "Could not save the new background connection target; the current connection was kept.")
                } else {
                    current.copy(
                        activeConnection = connection,
                        connectionStatus = ConnectionStatus.ERROR,
                        connectionMessage = "Could not persist the background connection target. Tap Connect to retry.",
                        showConnections = true,
                        notice = "Connection paused because its recovery target could not be saved.",
                    )
                }
            }
            return
        }
        reconnectJob?.cancel()
        reconnectJob = null
        val currentDefaultNetworkId = runCatching {
            connectivityManager.activeNetwork?.networkHandle
        }.getOrNull()
        networkTransitions.reset(currentDefaultNetworkId)
        hasObservedNetworkState = true
        activeConnectionAttemptNetworkId = currentDefaultNetworkId
        reconnectAttempts.reset()
        reconnectCircuitBreakerPaused = false
        pendingReconnectSelection = null
        networkHandoffPending = false
        networkHandoffJob?.cancel()
        networkHandoffJob = null
        shouldMaintainConnection = true
        if (!SshConnectionService.start(getApplication())) {
            shouldMaintainConnection = false
            if (!maintenanceStore.clear()) {
                Log.e(CONNECTION_LOG_TAG, "state=maintenance_target_clear_failed reason=fgs_start")
            }
            disconnectInternal(clearActive = true)
            _state.update {
                it.copy(
                    activeConnection = connection,
                    connectionStatus = ConnectionStatus.ERROR,
                    connectionMessage = "Android blocked background connection startup. Open Codex Remote and tap Connect again.",
                    showConnections = true,
                    notice = "Connection paused because Android could not start its foreground service.",
                )
            }
            return
        }
        when (
            reconnectReadiness(
                isDeviceIdleMode = isDeviceIdleMode,
                hasObservedNetworkState = hasObservedNetworkState,
                hasAvailableNetwork = networkTransitions.hasAvailableNetwork,
            )
        ) {
            ReconnectReadiness.WAITING_FOR_DEVICE_WAKE -> {
                _state.update {
                    it.copy(
                        activeConnection = connection,
                        connectionStatus = ConnectionStatus.RECONNECTING,
                        connectionMessage = "Waiting for the device to wake…",
                        showConnections = false,
                    )
                }
                return
            }
            ReconnectReadiness.WAITING_FOR_NETWORK -> {
                _state.update {
                    it.copy(
                        activeConnection = connection,
                        connectionStatus = ConnectionStatus.RECONNECTING,
                        connectionMessage = "Waiting for a network…",
                        showConnections = false,
                    )
                }
                return
            }
            ReconnectReadiness.READY -> Unit
        }
        startConnectionAttempt(connection, isReconnect = false)
    }

    private fun startConnectionAttempt(connection: SavedConnection, isReconnect: Boolean) {
        connectionJob = viewModelScope.launch {
            networkHandoffPending = false
            networkHandoffJob?.cancel()
            networkHandoffJob = null
            val attemptNetworkId = runCatching {
                connectivityManager.activeNetwork?.networkHandle
            }.getOrNull()
            activeConnectionAttemptNetworkId = attemptNetworkId
            disconnectInternal(clearActive = false, suspendMaintenance = false)
            val connectionEpoch = sessionRequestTracker.connectionGeneration
            var attemptClient: CodexRpcClient? = null
            _state.update {
                it.copy(
                    activeConnection = connection,
                    connectionStatus = if (isReconnect) {
                        ConnectionStatus.RECONNECTING
                    } else {
                        ConnectionStatus.CONNECTING
                    },
                    connectionMessage = if (isReconnect) {
                        "Reconnecting to ${connection.host}…"
                    } else {
                        "Connecting to ${connection.host}…"
                    },
                    showConnections = false,
                    timeline = emptyList(),
                    olderHistoryCursor = null,
                    hasOlderHistory = false,
                    isOlderHistoryLoading = false,
                    olderHistoryError = null,
                    consumedHistoryCursors = emptySet(),
                    threads = emptyList(),
                    archivedThreads = emptyList(),
                    isArchivedThreadsLoading = false,
                    archivedThreadsError = null,
                    projects = emptyList(),
                    skills = emptyList(),
                    plugins = emptyList(),
                    isComposerCatalogLoading = true,
                    composerCatalogError = null,
                    selectedProjectPath = null,
                    selectedThreadId = null,
                    threadGoal = null,
                    isGoalLoading = false,
                    goalError = null,
                    models = emptyList(),
                    selectedModel = null,
                    selectedReasoningEffort = null,
                    selectedServiceTier = null,
                    collaborationModes = emptyList(),
                    selectedCollaborationMode = "default",
                    permissionProfiles = emptyList(),
                    selectedPermissionProfile = SAFE_PERMISSION_PROFILE,
                    approvalPolicy = SAFE_APPROVAL_POLICY,
                    approvalsReviewer = SAFE_APPROVALS_REVIEWER,
                    remoteServer = null,
                    remoteAccount = null,
                    remoteDeviceLogin = null,
                    isLoginStarting = false,
                    mcpServers = emptyList(),
                    isMcpStatusLoading = false,
                    mcpStatusError = null,
                    isMcpLoginStarting = false,
                    mcpAuthorizationUrl = null,
                    isFeedbackSubmitting = false,
                    feedbackError = null,
                    rateLimits = null,
                    threadTokenUsage = null,
                    isStatusLoading = false,
                    statusError = null,
                    pendingHostKeyFingerprint = null,
                    notice = if (isReconnect) it.notice else null,
                )
            }
            runCatching {
                val secrets = withContext(Dispatchers.IO) { store.decrypt(connection) }
                val transport = transportFactory.open(connection, secrets)
                val client = CodexRpcClient(transport)
                if (sessionRequestTracker.connectionGeneration != connectionEpoch) {
                    closeClientInBackground(client)
                    throw SupersededConnectionException()
                }
                attemptClient = client
                rpc = client
                observeEvents(client, connectionEpoch)
                val server = withTimeout(20_000) { client.initialize() }
                val account = withTimeout(20_000) { client.readAccount() }
                val models = withTimeout(30_000) { client.listModels() }
                val threads = withTimeout(60_000) { client.listThreads() }
                val collaborationModes = runCatching {
                    withTimeout(20_000) { client.listCollaborationModes() }
                }.getOrDefault(emptyList())
                val permissionProfiles = runCatching {
                    withTimeout(20_000) { client.listPermissionProfiles(null) }
                }.getOrDefault(emptyList())
                if (!isCurrentConnection(client, connectionEpoch)) throw SupersededConnectionException()
                store.recordUsed(connection.id)
                if (!isCurrentConnection(client, connectionEpoch)) throw SupersededConnectionException()
                ConnectionBootstrap(server, account, models, threads, collaborationModes, permissionProfiles)
            }.onSuccess { bootstrap ->
                if (sessionRequestTracker.connectionGeneration != connectionEpoch || rpc !== attemptClient) {
                    return@onSuccess
                }
                val projects = groupThreadsByProject(bootstrap.threads)
                val selectedModel = bootstrap.models.firstOrNull { model -> model.isDefault }
                    ?: bootstrap.models.firstOrNull()
                val reconnectSelection = pendingReconnectSelection
                val hadUnconfirmedWork = reconnectHadUnconfirmedWork
                val selectedProjectPath = reconnectSelection?.projectPath
                    ?.takeIf { path -> projects.any { it.path == path } }
                    ?: projects.firstOrNull()?.path
                val connectedNetworkChanged = networkTransitions.currentNetworkId
                    ?.let { currentNetworkId -> currentNetworkId != attemptNetworkId }
                    ?: false
                val needsImmediateNetworkHandoff = networkHandoffPending || connectedNetworkChanged
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.CONNECTED,
                        connectionMessage = connectionSummary(
                            projects.size,
                            bootstrap.threads.size,
                            bootstrap.server.codexVersion,
                        ),
                        models = bootstrap.models,
                        selectedModel = selectedModel?.id,
                        selectedReasoningEffort = selectedModel?.preferredReasoningEffort(),
                        selectedServiceTier = selectedModel?.defaultServiceTier,
                        collaborationModes = bootstrap.collaborationModes,
                        selectedCollaborationMode = bootstrap.collaborationModes
                            .firstOrNull { mode -> mode.mode == "default" }?.mode ?: "default",
                        permissionProfiles = bootstrap.permissionProfiles,
                        selectedPermissionProfile = SAFE_PERMISSION_PROFILE,
                        approvalPolicy = SAFE_APPROVAL_POLICY,
                        approvalsReviewer = SAFE_APPROVALS_REVIEWER,
                        remoteServer = bootstrap.server,
                        remoteAccount = bootstrap.account,
                        threads = bootstrap.threads,
                        projects = projects,
                        skills = emptyList(),
                        plugins = emptyList(),
                        isComposerCatalogLoading = true,
                        composerCatalogError = null,
                        selectedProjectPath = selectedProjectPath,
                        showConnections = false,
                        notice = when {
                            needsImmediateNetworkHandoff -> {
                                "Network changed while connecting; reopening SSH on the current route."
                            }
                            !isReconnect -> it.notice
                            hadUnconfirmedWork -> {
                                "Connection restored; verify the operation that was in flight while the connection was interrupted."
                            }
                            else -> "Connection restored"
                        },
                    )
                }
                if (needsImmediateNetworkHandoff) {
                    requestNetworkHandoff(connection)
                    return@onSuccess
                }
                reconnectStabilityJob?.cancel()
                if (isReconnect) {
                    reconnectStabilityJob = viewModelScope.launch {
                        delay(RECONNECT_STABILITY_WINDOW_MILLIS)
                        if (rpc === attemptClient && _state.value.connectionStatus == ConnectionStatus.CONNECTED) {
                            reconnectAttempts.reset()
                            Log.i(CONNECTION_LOG_TAG, "state=stable reconnect_attempt_reset=true")
                        }
                    }
                } else {
                    reconnectAttempts.reset()
                }
                pendingReconnectSelection = null
                reconnectHadUnconfirmedWork = false
                reconnectCircuitBreakerPaused = false
                refreshComposerCatalog()
                reconnectSelection?.threadId
                    ?.let { threadId -> bootstrap.threads.firstOrNull { it.id == threadId } }
                    ?.let(::selectThread)
            }.onFailure { error ->
                if (error.isCancellation()) return@onFailure
                if (sessionRequestTracker.connectionGeneration != connectionEpoch || rpc !== attemptClient) {
                    closeClientInBackground(attemptClient)
                    return@onFailure
                }
                eventJob?.cancel()
                eventJob = null
                closeClientInBackground(rpc)
                rpc = null
                val unknownHostKey = generateSequence(error) { it.cause }
                    .filterIsInstance<UnknownHostKeyException>()
                    .firstOrNull()
                if (unknownHostKey != null) {
                    suspendConnectionMaintenance()
                    _state.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.ERROR,
                            connectionMessage = "Confirm the SSH host fingerprint first",
                            pendingHostKeyFingerprint = unknownHostKey.fingerprint,
                        )
                    }
                } else if (error.isRetryableConnectionFailure() && shouldMaintainConnection) {
                    scheduleReconnect(friendlyError(error))
                } else {
                    suspendConnectionMaintenance()
                    _state.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.ERROR,
                            connectionMessage = friendlyError(error),
                            notice = friendlyError(error),
                        )
                    }
                }
            }
        }
    }

    fun trustPendingHostKey() {
        val connection = _state.value.activeConnection ?: return
        val fingerprint = _state.value.pendingHostKeyFingerprint ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        viewModelScope.launch {
            store.recordFingerprint(connection.id, fingerprint)
            if (!sessionRequestTracker.isCurrentConnection(connectionGeneration) ||
                _state.value.activeConnection?.id != connection.id ||
                _state.value.pendingHostKeyFingerprint != fingerprint
            ) {
                return@launch
            }
            _state.update { it.copy(pendingHostKeyFingerprint = null) }
            connect(connection.copy(hostKeyFingerprint = fingerprint))
        }
    }

    fun rejectPendingHostKey() = _state.update {
        it.copy(
            pendingHostKeyFingerprint = null,
            connectionStatus = ConnectionStatus.ERROR,
            connectionMessage = "Connection to the unverified host was canceled",
        )
    }

    fun disconnect() {
        didRestoreLastConnection = true
        viewModelScope.launch {
            disconnectInternal(clearActive = true)
        }
    }

    fun onDefaultNetworkAvailable(networkId: Long) {
        viewModelScope.launch {
            hasObservedNetworkState = true
            val transition = networkTransitions.onAvailable(networkId)
            val connection = _state.value.activeConnection ?: return@launch
            if (!shouldMaintainConnection) return@launch
            if (isDeviceIdleMode) {
                Log.i(CONNECTION_LOG_TAG, "state=network_available recovery_deferred=device_idle")
                return@launch
            }
            val availableNetworkMayRequireHandoff = when (transition) {
                NetworkTransition.CHANGED,
                NetworkTransition.RESTORED,
                NetworkTransition.INITIAL -> availableNetworkRequiresHandoff(
                    activeConnectionAttemptNetworkId,
                    networkId,
                )
                NetworkTransition.UNCHANGED,
                NetworkTransition.LOST,
                NetworkTransition.STALE_LOSS -> false
            }
            if (availableNetworkMayRequireHandoff && connectionJob?.isActive == true) {
                networkHandoffPending = true
                Log.i(CONNECTION_LOG_TAG, "state=network_handoff_deferred reason=connection_attempt")
            }
            val recoveryAction = if (
                transition == NetworkTransition.INITIAL &&
                _state.value.connectionStatus == ConnectionStatus.CONNECTED &&
                availableNetworkMayRequireHandoff
            ) {
                NetworkRecoveryAction.REQUEST_HANDOFF
            } else {
                networkRecoveryAction(
                    transition = transition,
                    connectionStatus = _state.value.connectionStatus,
                    connectionAttemptActive = connectionJob?.isActive == true,
                )
            }.let { action ->
                if (action == NetworkRecoveryAction.REQUEST_HANDOFF && !availableNetworkMayRequireHandoff) {
                    NetworkRecoveryAction.NONE
                } else {
                    action
                }
            }
            when (recoveryAction) {
                NetworkRecoveryAction.REQUEST_HANDOFF -> {
                    requestNetworkHandoff(connection)
                }
                NetworkRecoveryAction.SCHEDULE_RECONNECT -> {
                    if (reconnectCircuitBreakerPaused) {
                        reconnectAttempts.reset()
                        reconnectCircuitBreakerPaused = false
                        Log.i(CONNECTION_LOG_TAG, "state=reconnect_circuit_breaker_reset reason=network_change")
                    }
                    scheduleReconnect(
                        message = "Network available; restoring the SSH session",
                        immediate = true,
                        connection = connection,
                    )
                }
                NetworkRecoveryAction.NONE -> Unit
            }
        }
    }

    fun onDefaultNetworkUnavailable() {
        viewModelScope.launch {
            hasObservedNetworkState = true
            networkTransitions.onUnavailable()
            if (!shouldMaintainConnection) return@launch
            networkHandoffJob?.cancel()
            networkHandoffJob = null
            networkHandoffPending = false
            reconnectJob?.cancel()
            reconnectJob = null
            if (_state.value.connectionStatus == ConnectionStatus.RECONNECTING) {
                _state.update { it.copy(connectionMessage = "Waiting for a network…") }
            }
            Log.i(CONNECTION_LOG_TAG, "state=network_unavailable")
        }
    }

    fun onDeviceIdleModeChanged(isIdle: Boolean) {
        viewModelScope.launch {
            if (isDeviceIdleMode == isIdle) return@launch
            isDeviceIdleMode = isIdle
            if (!shouldMaintainConnection) return@launch
            if (isIdle) {
                reconnectJob?.cancel()
                reconnectJob = null
                networkHandoffJob?.cancel()
                networkHandoffJob = null
                if (_state.value.connectionStatus == ConnectionStatus.RECONNECTING) {
                    _state.update {
                        it.copy(
                            connectionMessage = "Waiting for the device to wake…",
                            showConnections = false,
                        )
                    }
                }
                Log.i(CONNECTION_LOG_TAG, "state=device_idle reconnect_deferred=true")
                return@launch
            }

            val connection = _state.value.activeConnection ?: return@launch
            val currentNetworkId = networkTransitions.currentNetworkId
            if (_state.value.connectionStatus == ConnectionStatus.CONNECTED) {
                if (
                    currentNetworkId != null &&
                    availableNetworkRequiresHandoff(activeConnectionAttemptNetworkId, currentNetworkId)
                ) {
                    requestNetworkHandoff(connection)
                } else {
                    networkHandoffPending = false
                }
            } else if (
                _state.value.connectionStatus != ConnectionStatus.CONNECTED &&
                connectionJob?.isActive != true &&
                (!hasObservedNetworkState || networkTransitions.hasAvailableNetwork)
            ) {
                scheduleReconnect(
                    message = "Device awake; restoring the SSH session",
                    immediate = true,
                    connection = connection,
                )
            }
            Log.i(CONNECTION_LOG_TAG, "state=device_awake recovery_checked=true")
        }
    }

    private fun requestNetworkHandoff(connection: SavedConnection) {
        if (!shouldMaintainConnection || _state.value.activeConnection?.id != connection.id) return
        networkHandoffPending = true
        networkHandoffJob?.cancel()
        networkHandoffJob = viewModelScope.launch {
            delay(NETWORK_CHANGE_DEBOUNCE_MILLIS)
            var deferWasLogged = false
            var pendingRpcSinceMillis: Long? = null
            while (
                networkHandoffPending &&
                shouldMaintainConnection &&
                _state.value.activeConnection?.id == connection.id &&
                _state.value.connectionStatus == ConnectionStatus.CONNECTED
            ) {
                val hasRunningTurn = hasRunningTurn()
                val hasPendingApproval = hasPendingApproval()
                val hasPendingRpc = rpc?.hasPendingRequests() == true
                when {
                    !hasRunningTurn && !hasPendingApproval && !hasPendingRpc -> {
                        performNetworkHandoffIfSafe(connection)
                        return@launch
                    }
                    hasRunningTurn || hasPendingApproval -> pendingRpcSinceMillis = null
                    else -> {
                        val now = SystemClock.elapsedRealtime()
                        val since = pendingRpcSinceMillis ?: now.also { pendingRpcSinceMillis = it }
                        if (now - since >= NETWORK_PENDING_RPC_GRACE_MILLIS) {
                            reconnectHadUnconfirmedWork = true
                            performNetworkHandoffIfSafe(connection, allowPendingRpc = true)
                            return@launch
                        }
                    }
                }
                if (!deferWasLogged) {
                    Log.i(CONNECTION_LOG_TAG, "state=network_handoff_deferred reason=protected_work")
                    deferWasLogged = true
                }
                delay(NETWORK_HANDOFF_RECHECK_MILLIS)
            }
        }
    }

    private fun performNetworkHandoffIfSafe(
        connection: SavedConnection,
        allowPendingRpc: Boolean = false,
    ) {
        if (
            !networkHandoffPending ||
            !shouldMaintainConnection ||
            _state.value.activeConnection?.id != connection.id ||
            _state.value.connectionStatus != ConnectionStatus.CONNECTED
        ) {
            return
        }
        val hasRunningTurn = hasRunningTurn()
        val hasPendingApproval = hasPendingApproval()
        val hasPendingRpc = rpc?.hasPendingRequests() == true
        val canOverridePendingRpc = allowPendingRpc &&
            !hasRunningTurn &&
            !hasPendingApproval &&
            hasPendingRpc
        if (
            currentNetworkHandoffDisposition() == NetworkHandoffDisposition.DEFER &&
            !canOverridePendingRpc
        ) {
            Log.i(CONNECTION_LOG_TAG, "state=network_handoff_deferred reason=protected_work")
            return
        }
        if (suspendForAmbiguousApprovalDelivery()) return
        networkHandoffPending = false
        networkHandoffJob = null
        rememberSelectionForReconnect()
        disconnectInternal(clearActive = false, suspendMaintenance = false)
        scheduleReconnect(
            message = "Network changed; opening SSH on the new route",
            immediate = true,
            connection = connection,
        )
    }

    private fun currentNetworkHandoffDisposition(): NetworkHandoffDisposition {
        val hasRunningTurn = hasRunningTurn()
        val hasPendingApproval = hasPendingApproval()
        val hasPendingRpc = rpc?.hasPendingRequests() == true
        return networkHandoffDisposition(hasRunningTurn, hasPendingApproval, hasPendingRpc)
    }

    private fun hasRunningTurn(): Boolean = _state.value.isTurnRunning ||
        sessionRegistry.sessions.values.any { it.isTurnRunning }

    private fun hasPendingApproval(): Boolean = sessionRegistry.sessions.values.any {
        it.approvalQueue.entries.isNotEmpty() || it.approvalQueue.respondingKeys.isNotEmpty()
    }

    private fun disconnectInternal(clearActive: Boolean, suspendMaintenance: Boolean = true) {
        if (suspendMaintenance) suspendConnectionMaintenance()
        reconnectStabilityJob?.cancel()
        reconnectStabilityJob = null
        sessionRequestTracker.invalidateConnection()
        eventJob?.cancel()
        eventJob = null
        val detachedClient = rpc
        rpc = null
        closeClientInBackground(detachedClient)
        sessionRegistry = SessionRegistry()
        _state.update { it.afterDisconnect(clearActive) }
    }

    private fun suspendConnectionMaintenance() {
        shouldMaintainConnection = false
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectStabilityJob?.cancel()
        reconnectStabilityJob = null
        networkHandoffJob?.cancel()
        networkHandoffJob = null
        networkHandoffPending = false
        reconnectHadUnconfirmedWork = false
        reconnectCircuitBreakerPaused = false
        reconnectAttempts.reset()
        pendingReconnectSelection = null
        if (!maintenanceStore.clear()) {
            Log.e(CONNECTION_LOG_TAG, "state=maintenance_target_clear_failed")
        }
        SshConnectionService.stop(getApplication())
    }

    private fun rememberSelectionForReconnect() {
        if (pendingReconnectSelection != null) return
        val snapshot = _state.value
        pendingReconnectSelection = ReconnectSelection(
            projectPath = snapshot.selectedProjectPath,
            threadId = snapshot.selectedThreadId,
        )
    }

    private fun suspendForAmbiguousApprovalDelivery(): Boolean {
        val responseInFlight = sessionRegistry.sessions.values.any { session ->
            session.approvalQueue.respondingKeys.isNotEmpty()
        }
        if (!responseInFlight) return false
        val message = "Approval response delivery could not be confirmed; disconnected without retrying."
        disconnectInternal(clearActive = false)
        _state.update {
            it.copy(
                connectionStatus = ConnectionStatus.ERROR,
                connectionMessage = message,
                showConnections = true,
                notice = message,
            )
        }
        Log.w(CONNECTION_LOG_TAG, "state=suspended reason=approval_delivery_ambiguous")
        return true
    }

    private fun scheduleReconnect(
        message: String,
        immediate: Boolean = false,
        connection: SavedConnection? = _state.value.activeConnection,
    ) {
        val target = connection ?: return
        if (!shouldMaintainConnection || _state.value.activeConnection?.id != target.id) return
        reconnectJob?.cancel()
        reconnectJob = null
        when (
            reconnectReadiness(
                isDeviceIdleMode = isDeviceIdleMode,
                hasObservedNetworkState = hasObservedNetworkState,
                hasAvailableNetwork = networkTransitions.hasAvailableNetwork,
            )
        ) {
            ReconnectReadiness.WAITING_FOR_DEVICE_WAKE -> {
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.RECONNECTING,
                        connectionMessage = "Waiting for the device to wake…",
                        showConnections = false,
                        notice = message,
                    )
                }
                Log.i(CONNECTION_LOG_TAG, "state=waiting_for_device_wake reason=retry_requested")
                return
            }
            ReconnectReadiness.WAITING_FOR_NETWORK -> {
                _state.update {
                    it.copy(
                        connectionStatus = ConnectionStatus.RECONNECTING,
                        connectionMessage = "Waiting for a network…",
                        showConnections = false,
                        notice = message,
                    )
                }
                Log.i(CONNECTION_LOG_TAG, "state=waiting_for_network reason=retry_requested")
                return
            }
            ReconnectReadiness.READY -> Unit
        }
        val attempt = reconnectAttempts.startedAttempts
        if (!reconnectAttempts.canSchedule()) {
            val pausedMessage =
                "Automatic reconnect paused after ${reconnectAttempts.maximumAttempts} failed attempts. It will retry after a network change, or you can tap Connect."
            reconnectCircuitBreakerPaused = true
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.ERROR,
                    connectionMessage = pausedMessage,
                    showConnections = true,
                    notice = pausedMessage,
                )
            }
            Log.w(CONNECTION_LOG_TAG, "state=suspended reason=reconnect_circuit_breaker")
            return
        }
        val delayMillis = if (immediate) {
            NETWORK_CHANGE_DEBOUNCE_MILLIS
        } else {
            reconnectAttempts.delayMillisForNextAttempt()
        }
        val scheduledConnectionGeneration = sessionRequestTracker.connectionGeneration
        _state.update {
            it.copy(
                connectionStatus = ConnectionStatus.RECONNECTING,
                connectionMessage = "Reconnecting in ${delayMillis / 1_000.0}s…",
                showConnections = false,
                notice = message,
            )
        }
        Log.i(
            CONNECTION_LOG_TAG,
            "state=reconnect_scheduled attempt=$attempt delay_ms=$delayMillis",
        )
        reconnectJob = viewModelScope.launch {
            delay(delayMillis)
            if (
                !shouldMaintainConnection ||
                _state.value.activeConnection?.id != target.id ||
                _state.value.connectionStatus == ConnectionStatus.CONNECTED ||
                connectionJob?.isActive == true ||
                isDeviceIdleMode ||
                sessionRequestTracker.connectionGeneration != scheduledConnectionGeneration
            ) {
                Log.i(CONNECTION_LOG_TAG, "state=reconnect_skipped reason=stale_timer")
                return@launch
            }
            if (!reconnectAttempts.markStarted(attempt)) return@launch
            Log.i(CONNECTION_LOG_TAG, "state=reconnect_started attempt=$attempt")
            startConnectionAttempt(target, isReconnect = true)
        }
    }

    fun newThread() {
        val snapshot = _state.value
        val projectPath = snapshot.selectedProjectPath ?: snapshot.projects.firstOrNull()?.path
        if (snapshot.remoteAccount?.canRunCodex != true) {
            _state.update { it.copy(notice = "Sign in to remote Codex first") }
            return
        }
        if (projectPath.isNullOrBlank()) {
            _state.update { it.copy(notice = "No remote Codex project is available for a new task") }
            return
        }
        if (!captureSelectedSession()) return
        sessionRequestTracker.advanceSelection()
        _state.update { state ->
            state.copy(
                selectedProjectPath = projectPath,
                selectedThreadId = null,
                threadGoal = null,
                isGoalLoading = false,
                goalError = null,
                threadTokenUsage = null,
                timeline = emptyList(),
                olderHistoryCursor = null,
                hasOlderHistory = false,
                isOlderHistoryLoading = false,
                olderHistoryError = null,
                consumedHistoryCursors = emptySet(),
                selectedCollaborationMode = state.defaultCollaborationMode(),
                selectedPermissionProfile = SAFE_PERMISSION_PROFILE,
                approvalPolicy = SAFE_APPROVAL_POLICY,
                approvalsReviewer = SAFE_APPROVALS_REVIEWER,
            )
        }
        if (_state.value.selectedThreadId == null) {
            sessionRegistry = sessionRegistry.clearSelection()
            projectSessionRegistry()
        }
    }

    fun selectProject(project: RemoteProject) {
        if (!captureSelectedSession()) return
        sessionRequestTracker.advanceSelection()
        sessionRegistry = sessionRegistry.clearSelection()
        _state.update {
            it.copy(
                selectedProjectPath = project.path,
                selectedThreadId = null,
                threadGoal = null,
                isGoalLoading = false,
                goalError = null,
                threadTokenUsage = null,
                timeline = emptyList(),
                olderHistoryCursor = null,
                hasOlderHistory = false,
                isOlderHistoryLoading = false,
                olderHistoryError = null,
                consumedHistoryCursors = emptySet(),
                selectedCollaborationMode = it.defaultCollaborationMode(),
                selectedPermissionProfile = SAFE_PERMISSION_PROFILE,
                approvalPolicy = SAFE_APPROVAL_POLICY,
                approvalsReviewer = SAFE_APPROVALS_REVIEWER,
            )
        }
        projectSessionRegistry()
    }

    fun selectThread(thread: RemoteThread) {
        if (_state.value.activeConnection == null) return
        val client = rpc ?: return
        sessionRequestTracker.advanceSelection()
        val loadToken = sessionRequestTracker.beginSessionLoad(thread.id)
        fun isCurrentRequest(): Boolean =
            rpc === client && sessionRequestTracker.isCurrent(loadToken)
        if (!captureSelectedSession()) return
        val previous = _state.value
        val wasCached = thread.id in sessionRegistry.sessions
        val selected = sessionRegistry.selectThread(thread)
        if (!selected.applied) {
            sessionRegistry = selected.registry
            _state.update { it.copy(notice = "This task could not be cached safely.") }
            return
        }
        sessionRegistry = selected.registry
        if (!wasCached) {
            val initialized = sessionRegistry.updateSession(thread.id, markUnread = false) { session ->
                session.copy(
                    settings = previous.defaultSessionSettings(),
                    isTurnRunning = thread.status.isRemoteThreadActive(),
                    streamStatus = if (thread.status.isRemoteThreadActive()) {
                        SessionStreamStatus.RUNNING
                    } else {
                        SessionStreamStatus.IDLE
                    },
                )
            }
            if (!applySessionMutation(initialized, "This task could not be initialized safely.")) return
        }
        val projectPath = previous.projects
            .firstOrNull { project -> project.threads.any { it.id == thread.id } }
            ?.path
            ?: thread.cwd
        _state.update { state ->
            state.withSessionRegistry(sessionRegistry).copy(
                selectedProjectPath = projectPath,
                selectedThreadId = thread.id,
                isGoalLoading = true,
                goalError = null,
                isBusy = true,
            )
        }
        viewModelScope.launch {
            val session = runCatching { client.resumeThread(thread.id, thread.cwd) }
                .getOrElse { error ->
                    if (error.isCancellation() || !isCurrentRequest()) return@launch
                    _state.update { state ->
                        if (state.selectedThreadId == thread.id) {
                            state.copy(
                                isGoalLoading = false,
                                isBusy = false,
                                notice = friendlyError(error),
                            )
                        } else {
                            state
                        }
                    }
                    return@launch
                }
            if (!isCurrentRequest()) return@launch
            if (!captureSelectedSession()) {
                _state.update { state ->
                    if (state.selectedThreadId == thread.id) {
                        state.copy(isGoalLoading = false, isBusy = false)
                    } else {
                        state
                    }
                }
                return@launch
            }
            val resumed = sessionRegistry.updateSession(thread.id, markUnread = false) { cached ->
                val timeline = mergeTimelineHistory(session.timeline, cached.timeline).takeLastWithin(
                    maxItems = sessionRegistry.limits.maxTimelineItems,
                    maxChars = sessionRegistry.limits.maxTimelineChars,
                )
                val resumedTurnId = resolveResumedTurnId(
                    remoteActiveTurnId = session.activeTurnId,
                    cachedIsRunning = cached.isTurnRunning,
                    cachedActiveTurnId = cached.activeTurnId,
                    cachedExpectedTurnId = cached.expectedTurnId,
                )
                val isRunning = session.activeTurnId != null || cached.isTurnRunning
                cached.copy(
                    thread = thread,
                    timeline = timeline,
                    approvalQueue = cached.approvalQueue.bindFileChangeSnapshots(timeline, thread.id),
                    olderHistoryCursor = session.olderHistoryCursor,
                    hasOlderHistory = session.olderHistoryCursor != null,
                    isOlderHistoryLoading = false,
                    olderHistoryError = null,
                    consumedHistoryCursors = emptySet(),
                    settings = cached.settings.copy(
                        model = session.model ?: cached.settings.model,
                        reasoningEffort = session.reasoningEffort,
                        serviceTier = session.serviceTier,
                        collaborationMode = session.collaborationMode ?: cached.settings.collaborationMode,
                    ).withRemoteAuthorization(
                        permissionProfile = session.permissionProfile,
                        approvalPolicy = session.approvalPolicy,
                        approvalsReviewer = session.approvalsReviewer,
                    ),
                    isTurnRunning = isRunning,
                    activeTurnId = resumedTurnId,
                    expectedTurnId = resumedTurnId,
                    streamStatus = if (isRunning) SessionStreamStatus.RUNNING else cached.streamStatus,
                )
            }
            if (!isCurrentRequest()) return@launch
            if (!applySessionMutation(resumed, "Remote task history exceeded safe cache limits.")) {
                _state.update { state ->
                    if (state.selectedThreadId == thread.id) {
                        state.copy(isGoalLoading = false, isBusy = false)
                    } else {
                        state
                    }
                }
                return@launch
            }
            _state.update { state ->
                val projected = state.withSessionRegistry(sessionRegistry)
                if (projected.selectedThreadId == thread.id) {
                    projected.copy(
                        isBusy = false,
                        approvalFileItems = projected.approvalFileItems.recordFileApprovalItems(
                            thread.id,
                            projected.timeline,
                        ),
                    )
                } else {
                    projected
                }
            }
            runCatching { client.getThreadGoal(thread.id) }
                .onSuccess { goal ->
                    if (!isCurrentRequest()) return@onSuccess
                    if (!captureSelectedSession()) return@onSuccess
                    val updated = sessionRegistry.updateSession(thread.id, markUnread = false) {
                        it.copy(goal = goal)
                    }
                    if (!applySessionMutation(updated, "Remote goal state could not be cached safely.")) {
                        return@onSuccess
                    }
                    _state.update { state ->
                        val projected = state.withSessionRegistry(sessionRegistry)
                        if (projected.selectedThreadId == thread.id) {
                            projected.copy(
                                isGoalLoading = false,
                                goalError = null,
                            )
                        } else {
                            projected
                        }
                    }
                }
                .onFailure { error ->
                    if (error.isCancellation() || !isCurrentRequest()) return@onFailure
                    if (!captureSelectedSession()) return@onFailure
                    val updated = sessionRegistry.updateSession(thread.id, markUnread = false) {
                        it.copy(goal = null)
                    }
                    if (!applySessionMutation(updated, "Remote goal state could not be cached safely.")) {
                        return@onFailure
                    }
                    _state.update { state ->
                        val projected = state.withSessionRegistry(sessionRegistry)
                        if (projected.selectedThreadId == thread.id) {
                            if (error.isUnsupportedRpcMethod("thread/goal/get")) {
                                projected.copy(
                                    isGoalLoading = false,
                                    goalError = null,
                                )
                            } else {
                                projected.copy(
                                    isGoalLoading = false,
                                    goalError = friendlyGoalError(error),
                                )
                            }
                        } else {
                            projected
                        }
                    }
                }
        }
    }

    fun loadOlderHistory() {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        val snapshot = _state.value
        val threadId = snapshot.selectedThreadId ?: return
        val cursor = snapshot.olderHistoryCursor ?: return
        if (!snapshot.hasOlderHistory || snapshot.isOlderHistoryLoading || snapshot.isBusy) return
        if (cursor in snapshot.consumedHistoryCursors) {
            _state.update {
                it.copy(
                    olderHistoryCursor = null,
                    hasOlderHistory = false,
                    olderHistoryError = "Remote returned a repeated history cursor; stopped loading",
                )
            }
            captureSelectedSession()
            return
        }

        _state.update { state ->
            if (state.selectedThreadId == threadId && state.olderHistoryCursor == cursor) {
                state.copy(isOlderHistoryLoading = true, olderHistoryError = null)
            } else {
                state
            }
        }
        if (!captureSelectedSession()) return
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            val consumedCursors = snapshot.consumedHistoryCursors + cursor
            runCatching {
                client.loadOlderThreadHistory(threadId, cursor).let { page ->
                    page.copy(
                        nextCursor = CodexRpcClient.checkedNextHistoryCursor(
                            returnedCursor = page.nextCursor,
                            consumedCursors = consumedCursors,
                        ),
                    )
                }
            }.onSuccess { page ->
                if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                val state = _state.value
                if (state.selectedThreadId != threadId || state.olderHistoryCursor != cursor) {
                    return@onSuccess
                }
                val updated = sessionRegistry.updateSession(threadId, markUnread = false) { session ->
                    val timeline = mergeTimelineHistory(page.timeline, session.timeline)
                    session.copy(
                        timeline = timeline,
                        approvalQueue = session.approvalQueue.bindFileChangeSnapshots(timeline, threadId),
                        olderHistoryCursor = page.nextCursor,
                        hasOlderHistory = page.nextCursor != null,
                        isOlderHistoryLoading = false,
                        olderHistoryError = null,
                        consumedHistoryCursors = consumedCursors,
                    )
                }
                if (!applySessionMutation(updated, "Remote task history exceeded safe cache limits.")) {
                    restoreOlderHistoryLoadingAfterRejection(threadId)
                    return@onSuccess
                }
                projectSessionRegistry()
                _state.update { current ->
                    current.copy(
                        approvalFileItems = current.approvalFileItems.recordFileApprovalItems(
                            threadId,
                            page.timeline,
                        ),
                    )
                }
            }.onFailure { error ->
                if (error.isCancellation() || !isCurrentConnection(client, connectionGeneration)) return@onFailure
                val repeatedCursor = error.message.orEmpty().contains("nextCursor", ignoreCase = true)
                val state = _state.value
                if (state.selectedThreadId != threadId || state.olderHistoryCursor != cursor) {
                    return@onFailure
                }
                val updated = sessionRegistry.updateSession(threadId, markUnread = false) { session ->
                    session.copy(
                        olderHistoryCursor = if (repeatedCursor) null else session.olderHistoryCursor,
                        hasOlderHistory = if (repeatedCursor) false else session.hasOlderHistory,
                        isOlderHistoryLoading = false,
                        olderHistoryError = friendlyError(error),
                        consumedHistoryCursors = consumedCursors,
                    )
                }
                if (!applySessionMutation(updated, "Remote task history state could not be cached safely.")) {
                    restoreOlderHistoryLoadingAfterRejection(threadId)
                    return@onFailure
                }
                projectSessionRegistry()
            }
        }
    }

    fun sendMessage(
        text: String,
        selectedMentions: List<ComposerMention> = emptyList(),
        attachments: List<ComposerImageAttachment> = emptyList(),
        asGoal: Boolean = false,
    ) {
        if (rejectNewWorkDuringNetworkHandoff()) return
        val prompt = text.trim()
        if (prompt.isEmpty() && attachments.isEmpty()) return
        if (_state.value.activeConnection == null) return
        val client = rpc ?: return
        val connectionEpoch = sessionRequestTracker.connectionGeneration
        fun isCurrentOperation(): Boolean =
            rpc === client && sessionRequestTracker.isCurrentConnection(connectionEpoch)
        fun requireCurrentOperation() {
            if (!isCurrentOperation()) throw SupersededConnectionException()
        }
        val currentState = _state.value
        if (currentState.remoteAccount?.canRunCodex != true) {
            _state.update { it.copy(notice = "Remote Codex is not signed in") }
            return
        }
        if (asGoal && prompt.isEmpty()) {
            _state.update { it.copy(notice = "Goal must include an objective") }
            return
        }
        if (asGoal && currentState.isTurnRunning) {
            _state.update { it.copy(notice = "Cannot set a goal while the current task is running") }
            return
        }
        val selectedModel = currentState.selectedModel
        if (selectedModel == null) {
            _state.update { it.copy(notice = "Remote returned no available models") }
            return
        }
        val selectedReasoningEffort = currentState.selectedReasoningEffort
        val selectedServiceTier = currentState.selectedServiceTier
        val approvalPolicy = currentState.approvalPolicy
        val approvalsReviewer = currentState.approvalsReviewer
        val permissionProfile = currentState.selectedPermissionProfile
        val collaborationMode = currentState.collaborationModes
            .firstOrNull { it.mode == currentState.selectedCollaborationMode }
        val cwd = currentState.threads.firstOrNull { it.id == currentState.selectedThreadId }
            ?.cwd
            ?.takeIf { it.isNotBlank() }
            ?: currentState.selectedProjectPath?.takeIf { it.isNotBlank() }
        if (cwd == null) {
            _state.update { it.copy(notice = "Select a remote project first") }
            return
        }
        val mentions = resolveComposerMentions(prompt, cwd, selectedMentions, currentState)
        val steeringThreadId = currentState.selectedThreadId.takeIf { currentState.isTurnRunning }
        val steeringTurnId = currentState.activeTurnId.takeIf { currentState.isTurnRunning }
        if (currentState.isTurnRunning && (steeringThreadId == null || steeringTurnId == null)) {
            _state.update { it.copy(notice = "Restoring the running task; wait for the remote turn ID before steering") }
            return
        }
        val draftSelectionToken = if (currentState.selectedThreadId == null) {
            sessionRequestTracker.captureDraftSelection()
        } else {
            null
        }
        val draftSelectionContext = currentState.selectionContext()
        viewModelScope.launch {
            if (!isCurrentOperation()) return@launch
            var messageThreadId = currentState.selectedThreadId
            var turnStartToken: TurnStartToken? = null
            val localItemId = "local-${UUID.randomUUID()}"
            val userItem = TimelineItem(
                id = localItemId,
                kind = TimelineKind.USER,
                body = buildString {
                    append(prompt)
                    attachments.forEach { attachment ->
                        if (isNotEmpty()) append('\n')
                        append("[Image: ${attachment.displayName}]")
                    }
                },
                isGoal = asGoal,
            )
            _state.update {
                it.copy(
                    timeline = it.timeline + userItem,
                    isTurnRunning = if (asGoal) it.isTurnRunning else true,
                    isGoalLoading = if (asGoal) true else it.isGoalLoading,
                    goalError = if (asGoal) null else it.goalError,
                    notice = null,
                )
            }
            if (!captureSelectedSession()) {
                _state.update { state ->
                    state.copy(
                        timeline = state.timeline.filterNot { it.id == localItemId },
                        isTurnRunning = if (asGoal) state.isTurnRunning else false,
                        isGoalLoading = if (asGoal) false else state.isGoalLoading,
                    )
                }
                return@launch
            }
            runCatching {
                if (steeringThreadId != null && steeringTurnId != null) {
                    client.steerTurn(
                        threadId = steeringThreadId,
                        expectedTurnId = steeringTurnId,
                        text = prompt,
                        mentions = mentions,
                        attachments = attachments,
                    )
                    requireCurrentOperation()
                    return@runCatching
                }
                val threadId = currentState.selectedThreadId ?: client.startThread(
                    cwd = cwd,
                    model = selectedModel,
                    serviceTier = selectedServiceTier,
                    approvalPolicy = approvalPolicy,
                    approvalsReviewer = approvalsReviewer,
                    permissionProfile = permissionProfile,
                ).let { started ->
                    requireCurrentOperation()
                    val registered = sessionRegistry.registerThread(started.id)
                    if (!registered.applied) {
                        sessionRegistry = registered.registry
                        error("New remote task could not be cached safely")
                    }
                    sessionRegistry = registered.registry
                    val initialized = sessionRegistry.updateSession(started.id, markUnread = false) { session ->
                        session.copy(
                            timeline = listOf(userItem),
                            isTurnRunning = !asGoal,
                            streamStatus = if (asGoal) SessionStreamStatus.IDLE else SessionStreamStatus.RUNNING,
                            settings = SessionSettings(
                                model = started.model ?: selectedModel,
                                reasoningEffort = selectedReasoningEffort ?: started.reasoningEffort,
                                serviceTier = started.serviceTier ?: selectedServiceTier,
                                collaborationMode = collaborationMode?.mode ?: currentState.selectedCollaborationMode,
                                permissionProfile = permissionProfile,
                                approvalPolicy = approvalPolicy,
                                approvalsReviewer = approvalsReviewer,
                            ),
                        )
                    }
                    if (!applySessionMutation(initialized, "New remote task exceeded safe cache limits.")) {
                        error("New remote task could not be initialized safely")
                    }
                    val shouldSelectStarted = draftSelectionToken != null &&
                        sessionRequestTracker.isCurrent(draftSelectionToken) &&
                        _state.value.matchesSelection(draftSelectionContext)
                    if (shouldSelectStarted) {
                        sessionRequestTracker.advanceSelection()
                        val selected = sessionRegistry.selectThread(
                            threadId = started.id,
                            knownThreadIds = knownSessionThreadIds() + started.id,
                        )
                        if (!applySessionMutation(selected, "New remote task could not be selected safely.")) {
                            error("New remote task could not be selected safely")
                        }
                        _state.update { state ->
                            state.copy(
                                selectedProjectPath = cwd,
                                selectedThreadId = started.id,
                                selectedModel = started.model ?: state.selectedModel,
                                selectedReasoningEffort = selectedReasoningEffort
                                    ?: started.reasoningEffort
                                    ?: state.selectedReasoningEffort,
                                selectedServiceTier = started.serviceTier ?: state.selectedServiceTier,
                            )
                        }
                    }
                    projectSessionRegistry()
                    started.id
                }
                messageThreadId = threadId
                if (asGoal) {
                    val goal = client.setThreadGoal(
                        threadId = threadId,
                        objective = prompt,
                        status = ThreadGoalStatus.ACTIVE,
                    )
                    requireCurrentOperation()
                    if (!captureSelectedSession()) error("Remote goal state could not be cached safely")
                    val updated = sessionRegistry.updateSession(threadId, markUnread = false) {
                        it.copy(goal = goal)
                    }
                    if (!applySessionMutation(updated, "Remote goal state could not be cached safely.")) {
                        error("Remote goal state could not be cached safely")
                    }
                    _state.update { state ->
                        val projected = state.withSessionRegistry(sessionRegistry)
                        if (projected.selectedThreadId == threadId) {
                            projected.copy(isGoalLoading = false, goalError = null)
                        } else {
                            projected
                        }
                    }
                    return@runCatching
                }
                val startToken = sessionRequestTracker.beginTurnStart(threadId)
                turnStartToken = startToken
                val startedTurnId = client.startTurn(
                    threadId = threadId,
                    text = prompt,
                    cwd = cwd,
                    model = selectedModel,
                    reasoningEffort = selectedReasoningEffort,
                    serviceTier = selectedServiceTier,
                    approvalPolicy = approvalPolicy,
                    approvalsReviewer = approvalsReviewer,
                    permissionProfile = permissionProfile,
                    collaborationMode = collaborationMode,
                    mentions = mentions,
                    attachments = attachments,
                )
                requireCurrentOperation()
                if (startedTurnId != null) {
                    when (sessionRequestTracker.resolveTurnStartResponse(startToken, startedTurnId)) {
                        TurnStartResponseDisposition.APPLY -> routeSessionEvent(
                            client,
                            connectionEpoch,
                            AppServerEvent.TurnRunning(threadId, running = true, turnId = startedTurnId),
                        )
                        TurnStartResponseDisposition.ALREADY_OBSERVED,
                        TurnStartResponseDisposition.TERMINAL,
                        TurnStartResponseDisposition.SUPERSEDED,
                        -> Unit
                        TurnStartResponseDisposition.CONFLICT -> failClosedTurnIdentity(
                            client,
                            connectionEpoch,
                            "Remote returned a conflicting turn ID; disconnected to preserve task ownership.",
                        )
                    }
                }
            }.onSuccess {
                if (!isCurrentOperation()) return@onSuccess
                if (steeringThreadId != null) {
                    _state.update { it.copy(notice = "Message added to the running task") }
                }
            }.onFailure { error ->
                if (error.isCancellation() || !isCurrentOperation()) return@onFailure
                val ownsRollback = turnStartToken?.let(sessionRequestTracker::canRollbackTurnStart) ?: true
                if (!ownsRollback) return@onFailure
                if (messageThreadId == null && draftSelectionToken != null &&
                    (!sessionRequestTracker.isCurrent(draftSelectionToken) ||
                        !_state.value.matchesSelection(draftSelectionContext))
                ) {
                    return@onFailure
                }
                if (!captureSelectedSession()) return@onFailure
                val failedThreadId = messageThreadId
                if (failedThreadId != null && failedThreadId in sessionRegistry.sessions) {
                    val failed = sessionRegistry.updateSession(failedThreadId, markUnread = false) { session ->
                        session.copy(
                            timeline = session.timeline.filterNot { item -> item.id == localItemId },
                            isTurnRunning = if (asGoal || steeringThreadId != null) {
                                session.isTurnRunning
                            } else {
                                false
                            },
                            activeTurnId = if (asGoal || steeringThreadId != null) session.activeTurnId else null,
                            expectedTurnId = if (asGoal || steeringThreadId != null) session.expectedTurnId else null,
                            streamStatus = if (asGoal || steeringThreadId != null) {
                                session.streamStatus
                            } else {
                                SessionStreamStatus.FAILED
                            },
                        )
                    }
                    if (!applySessionMutation(failed, "Failed task state could not be cached safely.")) {
                        return@onFailure
                    }
                }
                _state.update { state ->
                    val projected = state.withSessionRegistry(sessionRegistry)
                    if (failedThreadId == null && projected.selectedThreadId == null) {
                        projected.copy(
                            timeline = projected.timeline.filterNot { item -> item.id == localItemId },
                            isTurnRunning = if (asGoal) projected.isTurnRunning else false,
                            activeTurnId = if (asGoal) projected.activeTurnId else null,
                            isGoalLoading = if (asGoal) false else projected.isGoalLoading,
                            goalError = if (asGoal) friendlyGoalError(error) else projected.goalError,
                        )
                    } else if (projected.selectedThreadId == failedThreadId) {
                        projected.copy(
                            isGoalLoading = if (asGoal) false else projected.isGoalLoading,
                            goalError = if (asGoal) friendlyGoalError(error) else projected.goalError,
                        )
                    } else {
                        projected
                    }
                }
                if (asGoal) {
                    _state.update {
                        it.copy(notice = "Failed to set goal: ${friendlyGoalError(error)}")
                    }
                } else {
                    showError(error)
                }
            }
        }
    }

    fun renameThread(thread: RemoteThread, name: String) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            runCatching { client.renameThread(thread.id, trimmedName) }
                .onSuccess {
                    if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                    _state.update { it.withThreadRenamed(thread.id, trimmedName) }
                }
                .onFailure { error ->
                    if (!error.isCancellation() && isCurrentConnection(client, connectionGeneration)) showError(error)
                }
        }
    }

    fun archiveThread(thread: RemoteThread) {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        if (!captureSelectedSession()) return
        if (shouldBlockArchive(thread, sessionRegistry.sessions[thread.id])) {
            _state.update { it.copy(notice = "Stop the current task before archiving it") }
            return
        }
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            runCatching { client.archiveThread(thread.id) }
                .onSuccess {
                    if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                    _state.update { state ->
                        state.withThreadArchived(thread.id).copy(
                            archivedThreads = (listOf(thread) + state.archivedThreads)
                                .distinctBy { it.id }
                                .sortedByDescending { it.updatedAt },
                        )
                    }
                    if (sessionRegistry.selectedThreadId == thread.id) {
                        sessionRequestTracker.advanceSelection()
                        sessionRegistry = sessionRegistry.clearSelection()
                        _state.update { state ->
                            state.copy(
                                selectedPermissionProfile = SAFE_PERMISSION_PROFILE,
                                approvalPolicy = SAFE_APPROVAL_POLICY,
                                approvalsReviewer = SAFE_APPROVALS_REVIEWER,
                            )
                        }
                        projectSessionRegistry()
                    }
                }
                .onFailure { error ->
                    if (!error.isCancellation() && isCurrentConnection(client, connectionGeneration)) showError(error)
                }
        }
    }

    fun loadArchivedThreads() {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            _state.update { it.copy(isArchivedThreadsLoading = true, archivedThreadsError = null) }
            runCatching { client.listArchivedThreads() }
                .onSuccess { archived ->
                    if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                    _state.update {
                        it.copy(
                            archivedThreads = archived,
                            isArchivedThreadsLoading = false,
                            archivedThreadsError = null,
                        )
                    }
                }
                .onFailure { error ->
                    if (error.isCancellation() || !isCurrentConnection(client, connectionGeneration)) return@onFailure
                    _state.update {
                        it.copy(
                            isArchivedThreadsLoading = false,
                            archivedThreadsError = friendlyError(error),
                        )
                    }
                }
        }
    }

    fun unarchiveThread(thread: RemoteThread) {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            runCatching { client.unarchiveThread(thread.id) }
                .onSuccess { restored ->
                    if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                    _state.update { state ->
                        val threads = (listOf(restored) + state.threads).distinctBy { it.id }
                        state.copy(
                            threads = threads,
                            projects = groupThreadsByProject(threads),
                            archivedThreads = state.archivedThreads.filterNot { it.id == restored.id },
                            notice = "Task restored",
                        )
                    }
                }
                .onFailure { error ->
                    if (!error.isCancellation() && isCurrentConnection(client, connectionGeneration)) showError(error)
                }
        }
    }

    fun deleteArchivedThread(thread: RemoteThread) {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            runCatching { client.deleteThread(thread.id) }
                .onSuccess {
                    if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                    _state.update { state ->
                        state.copy(
                            archivedThreads = state.archivedThreads.filterNot { it.id == thread.id },
                            notice = "Task permanently deleted",
                        )
                    }
                }
                .onFailure { error ->
                    if (!error.isCancellation() && isCurrentConnection(client, connectionGeneration)) showError(error)
                }
        }
    }

    fun setThreadPinned(thread: RemoteThread, isPinned: Boolean) {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            runCatching { client.setThreadPinned(thread.id, isPinned) }
                .onSuccess { updated ->
                    if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                    _state.update { state ->
                        val threads = state.threads.map { current ->
                            if (current.id == updated.id) updated else current
                        }
                        state.copy(threads = threads, projects = groupThreadsByProject(threads))
                    }
                }
                .onFailure { error ->
                    if (!error.isCancellation() && isCurrentConnection(client, connectionGeneration)) showError(error)
                }
        }
    }

    fun compactThread() {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        val threadId = _state.value.selectedThreadId ?: run {
            _state.update { it.copy(notice = "This new task has no context to compact") }
            return
        }
        if (_state.value.isTurnRunning) {
            _state.update { it.copy(notice = "Cannot compact context while the task is running") }
            return
        }
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            runCatching { client.compactThread(threadId) }
                .onSuccess {
                    if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                    _state.update { it.copy(notice = "Compacting task context") }
                }
                .onFailure { error ->
                    if (!error.isCancellation() && isCurrentConnection(client, connectionGeneration)) showError(error)
                }
        }
    }

    fun forkThread() {
        if (rejectNewWorkDuringNetworkHandoff()) return
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        val snapshot = _state.value
        val threadId = snapshot.selectedThreadId ?: run {
            _state.update { it.copy(notice = "Open a remote task before continuing to a new one") }
            return
        }
        if (snapshot.isTurnRunning) {
            _state.update { it.copy(notice = "Stop the current task before continuing to a new one") }
            return
        }
        val cwd = snapshot.threads.firstOrNull { it.id == threadId }?.cwd
            ?.takeIf(String::isNotBlank) ?: snapshot.selectedProjectPath.orEmpty()
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            _state.update { it.copy(isBusy = true, notice = null) }
            runCatching {
                client.forkThread(
                    threadId = threadId,
                    cwd = cwd,
                    model = snapshot.selectedModel,
                    serviceTier = snapshot.selectedServiceTier,
                    approvalPolicy = snapshot.approvalPolicy,
                    approvalsReviewer = snapshot.approvalsReviewer,
                    permissionProfile = snapshot.selectedPermissionProfile,
                )
            }.onSuccess { forked ->
                if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                if (!captureSelectedSession()) return@onSuccess
                val shouldSelectFork = _state.value.selectedThreadId == threadId
                val registered = sessionRegistry.registerThread(forked.thread)
                if (!registered.applied) {
                    sessionRegistry = registered.registry
                    _state.update { it.copy(isBusy = false, notice = "The continued task could not be cached safely") }
                    return@onSuccess
                }
                sessionRegistry = registered.registry
                val initialized = sessionRegistry.updateSession(forked.thread.id, markUnread = false) { session ->
                    session.copy(
                        thread = forked.thread,
                        timeline = forked.session.timeline,
                        olderHistoryCursor = forked.session.olderHistoryCursor,
                        hasOlderHistory = forked.session.olderHistoryCursor != null,
                        goal = null,
                        tokenUsage = null,
                        isTurnRunning = forked.session.activeTurnId != null,
                        activeTurnId = forked.session.activeTurnId,
                        expectedTurnId = forked.session.activeTurnId,
                        streamStatus = if (forked.session.activeTurnId != null) {
                            SessionStreamStatus.RUNNING
                        } else {
                            SessionStreamStatus.IDLE
                        },
                        settings = SessionSettings(
                            model = forked.session.model ?: snapshot.selectedModel,
                            reasoningEffort = forked.session.reasoningEffort,
                            serviceTier = forked.session.serviceTier,
                            collaborationMode = forked.session.collaborationMode
                                ?: snapshot.defaultCollaborationMode(),
                        ).withRemoteAuthorization(
                            permissionProfile = forked.session.permissionProfile,
                            approvalPolicy = forked.session.approvalPolicy,
                            approvalsReviewer = forked.session.approvalsReviewer,
                        ),
                    )
                }
                if (!applySessionMutation(initialized, "The continued task exceeded safe cache limits.")) {
                    return@onSuccess
                }
                if (shouldSelectFork) {
                    val selected = sessionRegistry.selectThread(forked.thread)
                    if (!applySessionMutation(selected, "The continued task could not be selected safely.")) {
                        return@onSuccess
                    }
                }
                _state.update { state ->
                    val threads = (listOf(forked.thread) + state.threads).distinctBy { it.id }
                    val updated = state.copy(
                        threads = threads,
                        projects = groupThreadsByProject(threads),
                        isBusy = false,
                        notice = if (shouldSelectFork) {
                            "Continued in a new remote task"
                        } else {
                            "New continued task is ready in the task list"
                        },
                    )
                    val projected = updated.withSessionRegistry(sessionRegistry)
                    if (shouldSelectFork) {
                        projected.copy(
                            selectedProjectPath = forked.thread.cwd.ifBlank {
                                projected.selectedProjectPath.orEmpty()
                            },
                            isGoalLoading = false,
                            goalError = null,
                            approvalFileItems = projected.approvalFileItems.recordFileApprovalItems(
                                forked.thread.id,
                                projected.timeline,
                            ),
                        )
                    } else {
                        projected
                    }
                }
            }.onFailure { error ->
                if (!error.isCancellation() && isCurrentConnection(client, connectionGeneration)) showError(error)
            }
        }
    }

    fun startReview(targetKind: ReviewTargetKind, targetValue: String = "") {
        if (rejectNewWorkDuringNetworkHandoff()) return
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        val snapshot = _state.value
        val threadId = snapshot.selectedThreadId ?: run {
            _state.update { it.copy(notice = "Open a remote task before starting code review") }
            return
        }
        if (snapshot.isTurnRunning) {
            _state.update { it.copy(notice = "Cannot start code review while the current task is running") }
            return
        }
        if (targetKind != ReviewTargetKind.UNCOMMITTED_CHANGES && targetValue.isBlank()) {
            _state.update { it.copy(notice = "Enter a review target") }
            return
        }
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            _state.update { it.copy(isTurnRunning = true, notice = null) }
            if (!captureSelectedSession()) {
                _state.update { it.copy(isTurnRunning = false) }
                return@launch
            }
            val startToken = sessionRequestTracker.beginTurnStart(threadId)
            runCatching { client.startReview(threadId, targetKind, targetValue) }
                .onSuccess { review ->
                    if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                    if (review.threadId != threadId) {
                        failClosedTurnIdentity(
                            client,
                            connectionGeneration,
                            "Remote review response changed task ownership; disconnected safely.",
                        )
                        return@onSuccess
                    }
                    when (sessionRequestTracker.resolveTurnStartResponse(startToken, review.turnId)) {
                        TurnStartResponseDisposition.APPLY -> routeSessionEvent(
                            client,
                            connectionGeneration,
                            AppServerEvent.TurnRunning(threadId, running = true, turnId = review.turnId),
                        )
                        TurnStartResponseDisposition.ALREADY_OBSERVED,
                        TurnStartResponseDisposition.TERMINAL,
                        TurnStartResponseDisposition.SUPERSEDED,
                        -> Unit
                        TurnStartResponseDisposition.CONFLICT -> failClosedTurnIdentity(
                            client,
                            connectionGeneration,
                            "Remote review response returned a conflicting turn ID; disconnected safely.",
                        )
                    }
                }
                .onFailure { error ->
                    if (error.isCancellation() || !isCurrentConnection(client, connectionGeneration) ||
                        !sessionRequestTracker.canRollbackTurnStart(startToken)
                    ) {
                        return@onFailure
                    }
                    val failed = sessionRegistry.updateSession(threadId, markUnread = false) { session ->
                        session.copy(
                            isTurnRunning = false,
                            activeTurnId = null,
                            expectedTurnId = null,
                            streamStatus = SessionStreamStatus.FAILED,
                        )
                    }
                    if (!applySessionMutation(failed, "Failed review state could not be cached safely.")) {
                        return@onFailure
                    }
                    projectSessionRegistry()
                    showError(error)
                }
        }
    }

    fun runInit() {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        val snapshot = _state.value
        val selectionToken = sessionRequestTracker.captureDraftSelection()
        val selectionContext = snapshot.selectionContext()
        if (snapshot.isTurnRunning) {
            _state.update { it.copy(notice = "Cannot run /init while the current task is running") }
            return
        }
        val cwd = snapshot.threads.firstOrNull { it.id == snapshot.selectedThreadId }?.cwd
            ?.takeIf(String::isNotBlank)
            ?: snapshot.selectedProjectPath?.takeIf(String::isNotBlank)
            ?: run {
                _state.update { it.copy(notice = "Select a remote project first") }
                return
            }
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            val agentsPath = remoteChildPath(cwd, "AGENTS.md")
            runCatching { client.remotePathExists(agentsPath) }
                .onSuccess { exists ->
                    if (!isCurrentConnection(client, connectionGeneration) ||
                        !sessionRequestTracker.isCurrent(selectionToken) ||
                        !_state.value.matchesSelection(selectionContext)
                    ) {
                        return@onSuccess
                    }
                    if (exists) {
                        _state.update { it.copy(notice = "AGENTS.md already exists; skipped /init to avoid overwriting it") }
                    } else {
                        sendMessage(INIT_PROMPT)
                    }
                }
                .onFailure { error ->
                    if (!error.isCancellation() && isCurrentConnection(client, connectionGeneration) &&
                        sessionRequestTracker.isCurrent(selectionToken) &&
                        _state.value.matchesSelection(selectionContext)
                    ) {
                        showError(error)
                    }
                }
        }
    }

    fun loadMcpStatus() {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        val threadId = _state.value.selectedThreadId
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            _state.update { it.copy(isMcpStatusLoading = true, mcpStatusError = null) }
            runCatching { client.listMcpServerStatuses(threadId) }
                .onSuccess { servers ->
                    if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                    _state.update {
                        it.copy(mcpServers = servers, isMcpStatusLoading = false, mcpStatusError = null)
                    }
                }
                .onFailure { error ->
                    if (error.isCancellation() || !isCurrentConnection(client, connectionGeneration)) return@onFailure
                    _state.update {
                        it.copy(
                            mcpServers = emptyList(),
                            isMcpStatusLoading = false,
                            mcpStatusError = friendlyError(error),
                        )
                    }
                }
        }
    }

    fun reloadMcpServers() {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        val threadId = _state.value.selectedThreadId
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            _state.update { it.copy(isMcpStatusLoading = true, mcpStatusError = null) }
            runCatching {
                client.reloadMcpServers()
                client.listMcpServerStatuses(threadId)
            }.onSuccess { servers ->
                if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                _state.update {
                    it.copy(mcpServers = servers, isMcpStatusLoading = false, mcpStatusError = null)
                }
            }.onFailure { error ->
                if (error.isCancellation() || !isCurrentConnection(client, connectionGeneration)) return@onFailure
                _state.update {
                    it.copy(isMcpStatusLoading = false, mcpStatusError = friendlyError(error))
                }
            }
        }
    }

    fun startMcpLogin(serverName: String) {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        val threadId = _state.value.selectedThreadId
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            _state.update { it.copy(isMcpLoginStarting = true, mcpStatusError = null) }
            runCatching { client.startMcpOauthLogin(serverName, threadId) }
                .onSuccess { authorizationUrl ->
                    if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                    _state.update {
                        it.copy(
                            isMcpLoginStarting = false,
                            mcpAuthorizationUrl = authorizationUrl,
                            notice = "Complete $serverName authorization in your browser",
                        )
                    }
                }
                .onFailure { error ->
                    if (error.isCancellation() || !isCurrentConnection(client, connectionGeneration)) return@onFailure
                    _state.update {
                        it.copy(isMcpLoginStarting = false, mcpStatusError = friendlyError(error))
                    }
                }
        }
    }

    fun clearMcpAuthorizationUrl() = _state.update { it.copy(mcpAuthorizationUrl = null) }

    fun submitFeedback(classification: String, reason: String) {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        val threadId = _state.value.selectedThreadId
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            _state.update { it.copy(isFeedbackSubmitting = true, feedbackError = null) }
            runCatching { client.submitFeedback(classification, reason, threadId) }
                .onSuccess { feedbackId ->
                    if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                    _state.update {
                        it.copy(
                            isFeedbackSubmitting = false,
                            feedbackError = null,
                            notice = if (feedbackId.isBlank()) {
                                "Feedback submitted"
                            } else {
                                "Feedback submitted: $feedbackId"
                            },
                        )
                    }
                }
                .onFailure { error ->
                    if (error.isCancellation() || !isCurrentConnection(client, connectionGeneration)) return@onFailure
                    val message = friendlyError(error)
                    _state.update {
                        it.copy(isFeedbackSubmitting = false, feedbackError = message, notice = message)
                    }
                }
        }
    }

    fun showGoalRequirement() = _state.update {
        it.copy(notice = "Send the first message to create the remote task, then use /goal to set a goal")
    }

    fun setThreadGoal(objective: String) {
        val trimmedObjective = objective.trim()
        if (trimmedObjective.isEmpty()) {
            _state.update { it.copy(goalError = "Goal cannot be empty") }
            return
        }
        val client = rpc ?: return
        val connectionEpoch = sessionRequestTracker.connectionGeneration
        val threadId = _state.value.selectedThreadId ?: run {
            showGoalRequirement()
            return
        }
        viewModelScope.launch {
            if (rpc !== client || !sessionRequestTracker.isCurrentConnection(connectionEpoch)) return@launch
            _state.update { it.copy(isGoalLoading = true, goalError = null) }
            runCatching {
                client.setThreadGoal(
                    threadId = threadId,
                    objective = trimmedObjective,
                    status = ThreadGoalStatus.ACTIVE,
                )
            }.onSuccess { goal ->
                if (rpc !== client || !sessionRequestTracker.isCurrentConnection(connectionEpoch)) return@onSuccess
                applyGoalResult(threadId, goal)
            }.onFailure { error ->
                if (error.isCancellation() || rpc !== client ||
                    !sessionRequestTracker.isCurrentConnection(connectionEpoch)
                ) return@onFailure
                updateGoalFailure(threadId, "Failed to set goal", error)
            }
        }
    }

    fun setThreadGoalStatus(status: ThreadGoalStatus) {
        val client = rpc ?: return
        val connectionEpoch = sessionRequestTracker.connectionGeneration
        val threadId = _state.value.selectedThreadId ?: run {
            showGoalRequirement()
            return
        }
        viewModelScope.launch {
            if (rpc !== client || !sessionRequestTracker.isCurrentConnection(connectionEpoch)) return@launch
            _state.update { it.copy(isGoalLoading = true, goalError = null) }
            runCatching { client.setThreadGoal(threadId = threadId, status = status) }
                .onSuccess { goal ->
                    if (rpc !== client || !sessionRequestTracker.isCurrentConnection(connectionEpoch)) return@onSuccess
                    applyGoalResult(threadId, goal)
                }
                .onFailure { error ->
                    if (error.isCancellation() || rpc !== client ||
                        !sessionRequestTracker.isCurrentConnection(connectionEpoch)
                    ) return@onFailure
                    updateGoalFailure(threadId, "Failed to update goal", error)
                }
        }
    }

    fun clearThreadGoal() {
        val client = rpc ?: return
        val connectionEpoch = sessionRequestTracker.connectionGeneration
        val threadId = _state.value.selectedThreadId ?: return
        viewModelScope.launch {
            if (rpc !== client || !sessionRequestTracker.isCurrentConnection(connectionEpoch)) return@launch
            _state.update { it.copy(isGoalLoading = true, goalError = null) }
            runCatching { client.clearThreadGoal(threadId) }
                .onSuccess {
                    if (rpc !== client || !sessionRequestTracker.isCurrentConnection(connectionEpoch)) return@onSuccess
                    applyGoalResult(threadId, null)
                }
                .onFailure { error ->
                    if (error.isCancellation() || rpc !== client ||
                        !sessionRequestTracker.isCurrentConnection(connectionEpoch)
                    ) return@onFailure
                    updateGoalFailure(threadId, "Failed to clear goal", error)
                }
        }
    }

    fun showConnectionStatus() {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            _state.update { it.copy(isStatusLoading = true, statusError = null) }
            runCatching { client.readRateLimits() }
                .onSuccess { limits ->
                    if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                    _state.update {
                        it.copy(rateLimits = limits, isStatusLoading = false, statusError = null)
                    }
                }
                .onFailure { error ->
                    if (error.isCancellation() || !isCurrentConnection(client, connectionGeneration)) return@onFailure
                    _state.update { state ->
                        if (error.isUnsupportedRpcMethod("account/rateLimits/read")) {
                            state.copy(rateLimits = null, isStatusLoading = false, statusError = null)
                        } else {
                            state.copy(isStatusLoading = false, statusError = friendlyError(error))
                        }
                    }
                }
        }
    }

    fun interruptTurn() {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        val threadId = _state.value.selectedThreadId ?: return
        val turnId = _state.value.activeTurnId ?: return
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            runCatching { client.interruptTurn(threadId, turnId) }.onFailure { error ->
                if (!error.isCancellation() && isCurrentConnection(client, connectionGeneration)) showError(error)
            }
        }
    }

    fun respondToApproval(
        requestKey: ApprovalQueueKey,
        decision: String,
        answers: Map<String, List<String>> = emptyMap(),
    ) {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        val approval = beginApprovalResponse(requestKey, decision) ?: return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            approvalFlowMutex.withLock {
                if (!isCurrentConnection(client, connectionGeneration)) return@withLock
                runCatching { client.respondToApproval(approval.request, decision, answers) }
                    .onFailure { error ->
                        if (!error.isCancellation() && isCurrentConnection(client, connectionGeneration)) {
                            disconnectInternal(clearActive = false)
                            _state.update { state ->
                                state.copy(
                                    showConnections = true,
                                    notice = "Approval response delivery could not be confirmed; disconnected without retrying.",
                                )
                            }
                        }
                    }
            }
        }
    }

    private fun beginApprovalResponse(requestKey: ApprovalQueueKey, decision: String): OwnedApproval? {
        if (!captureSelectedSession()) return null
        val approval = sessionRegistry.currentApproval
            ?.takeIf { it.key.queueKey == requestKey }
            ?.takeUnless(OwnedApproval::responding)
            ?: return null
        if (!approval.request.supportsDecision(decision)) {
            _state.update { it.copy(notice = "This approval decision is not available.") }
            return null
        }
        val owner = sessionRegistry.sessions[approval.key.threadId] ?: return null
        if (!approvalBelongsToLiveTurn(owner, approval.request)) {
            _state.update { it.copy(notice = "This approval's turn is no longer active; no response was sent.") }
            return null
        }
        if (decision.startsWith("accept")) {
            if (sessionRegistry.selectedThreadId != approval.key.threadId) {
                _state.update { it.copy(notice = "Open the owning task before allowing this action.") }
                return null
            }
            if (!approval.request.canApprove(owner.timeline, approval.key.threadId)) {
                _state.update { it.copy(notice = "Approval details are incomplete; only denial is allowed.") }
                return null
            }
        }
        val responding = sessionRegistry.markApprovalResponding(approval.key)
        if (!responding.applied) return null
        sessionRegistry = responding.registry
        projectSessionRegistry()
        return sessionRegistry.approvalFor(approval.key)
    }

    fun startRemoteLogin() {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        if (_state.value.isLoginStarting || _state.value.remoteDeviceLogin != null) return
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            _state.update { it.copy(isLoginStarting = true, notice = null) }
            runCatching { client.startDeviceLogin() }
                .onSuccess { login ->
                    if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                    _state.update { it.copy(remoteDeviceLogin = login, isLoginStarting = false) }
                }
                .onFailure { error ->
                    if (error.isCancellation() || !isCurrentConnection(client, connectionGeneration)) return@onFailure
                    _state.update { it.copy(isLoginStarting = false) }
                    showError(error)
                }
        }
    }

    fun cancelRemoteLogin() {
        val login = _state.value.remoteDeviceLogin ?: return
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        _state.update { it.copy(remoteDeviceLogin = null, isLoginStarting = false) }
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            runCatching { client.cancelLogin(login.loginId) }.onFailure { error ->
                if (!error.isCancellation() && isCurrentConnection(client, connectionGeneration)) showError(error)
            }
        }
    }

    fun setModel(modelId: String) {
        _state.update { state ->
            val model = state.models.firstOrNull { it.id == modelId } ?: return@update state
            state.copy(
                selectedModel = model.id,
                selectedReasoningEffort = model.preferredReasoningEffort(),
                selectedServiceTier = model.defaultServiceTier,
            )
        }
        captureSelectedSession()
    }

    fun setReasoningEffort(effort: String) {
        _state.update { state ->
            val model = state.models.firstOrNull { it.id == state.selectedModel }
            if (model?.supports(effort) == true) state.copy(selectedReasoningEffort = effort) else state
        }
        if (!captureSelectedSession()) return
    }

    fun setServiceTier(serviceTier: String?) {
        _state.update { state ->
            val model = state.models.firstOrNull { it.id == state.selectedModel } ?: return@update state
            if (serviceTier == null || model.serviceTiers.any { it.id == serviceTier }) {
                state.copy(selectedServiceTier = serviceTier)
            } else {
                state
            }
        }
        captureSelectedSession()
    }

    fun setCollaborationMode(mode: String) {
        _state.update { state ->
            if (state.collaborationModes.any { it.mode == mode }) {
                state.copy(
                    selectedCollaborationMode = mode,
                    notice = if (mode == "plan") "Switched to plan mode" else null,
                )
            } else {
                state.copy(notice = "Remote Codex does not provide $mode mode")
            }
        }
        if (!captureSelectedSession()) return
    }

    fun setPermissionProfile(profileId: String?) {
        _state.update { state ->
            if (profileId == null || profileId in BUILT_IN_PERMISSION_PROFILES ||
                state.permissionProfiles.any { it.id == profileId && it.allowed }
            ) {
                state.copy(selectedPermissionProfile = profileId)
            } else {
                state
            }
        }
        captureSelectedSession()
    }

    fun setPermissionMode(mode: PermissionMode) {
        _state.update { state ->
            when (mode) {
                PermissionMode.ASK -> state.copy(
                    selectedPermissionProfile = ":workspace",
                    approvalPolicy = "on-request",
                    approvalsReviewer = "user",
                )
                PermissionMode.AUTO_REVIEW -> state.copy(
                    selectedPermissionProfile = ":workspace",
                    approvalPolicy = "on-request",
                    approvalsReviewer = "auto_review",
                )
                PermissionMode.FULL_ACCESS -> state.copy(
                    selectedPermissionProfile = ":danger-full-access",
                    approvalPolicy = "never",
                    approvalsReviewer = "user",
                )
                PermissionMode.READ_ONLY -> state.copy(
                    selectedPermissionProfile = ":read-only",
                    approvalPolicy = "on-request",
                    approvalsReviewer = "user",
                )
            }
        }
        if (!captureSelectedSession()) return
    }
    fun loadRemoteDirectory(path: String) {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        if (path.isBlank()) return
        _state.update {
            it.copy(
                remoteDirectoryPath = path,
                remoteDirectoryEntries = emptyList(),
                isRemoteDirectoryLoading = true,
                remoteDirectoryError = null,
            )
        }
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            runCatching { client.readRemoteDirectory(path) }
                .onSuccess { entries ->
                    if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                    _state.update { state ->
                        if (state.remoteDirectoryPath != path) return@update state
                        state.copy(
                            remoteDirectoryEntries = entries.sortedWith(
                                compareBy<RemotePathEntry> { !it.isDirectory }
                                    .thenBy { it.name.lowercase() },
                            ),
                            isRemoteDirectoryLoading = false,
                            remoteDirectoryError = null,
                        )
                    }
                }
                .onFailure { error ->
                    if (error.isCancellation() || !isCurrentConnection(client, connectionGeneration)) return@onFailure
                    _state.update { state ->
                        if (state.remoteDirectoryPath != path) return@update state
                        state.copy(
                            remoteDirectoryEntries = emptyList(),
                            isRemoteDirectoryLoading = false,
                            remoteDirectoryError = friendlyError(error),
                        )
                    }
                }
        }
    }
    fun clearRemoteDirectory() = _state.update {
        it.copy(
            remoteDirectoryPath = null,
            remoteDirectoryEntries = emptyList(),
            isRemoteDirectoryLoading = false,
            remoteDirectoryError = null,
        )
    }
    fun clearNotice() = _state.update { it.copy(notice = null) }

    private fun isCurrentConnection(client: CodexRpcClient, generation: Long): Boolean =
        rpc === client && sessionRequestTracker.isCurrentConnection(generation)

    private fun knownSessionThreadIds(): Set<String> = buildSet {
        addAll(sessionRegistry.sessions.keys)
        addAll(_state.value.threads.map(RemoteThread::id))
        _state.value.selectedThreadId?.let(::add)
    }

    private fun captureSelectedSession(state: AppUiState = _state.value): Boolean {
        val threadId = state.selectedThreadId ?: return true
        val cachedTimeline = state.timeline.takeLastWithin(
            maxItems = sessionRegistry.limits.maxTimelineItems,
            maxChars = sessionRegistry.limits.maxTimelineChars,
        )
        val ready = sessionRegistry.ensureSessionForEvent(
            threadId = threadId,
            knownThreadIds = knownSessionThreadIds() + threadId,
        )
        if (!ready.applied) {
            sessionRegistry = ready.registry
            _state.update { it.copy(isBusy = false, notice = "This task could not be cached safely.") }
            return false
        }
        val thread = state.threads.firstOrNull { it.id == threadId }
        val updated = ready.registry.updateSession(threadId, markUnread = false) { cached ->
            cached.copy(
                thread = thread ?: cached.thread,
                timeline = cachedTimeline,
                olderHistoryCursor = state.olderHistoryCursor,
                hasOlderHistory = state.hasOlderHistory || cachedTimeline.size < state.timeline.size,
                isOlderHistoryLoading = state.isOlderHistoryLoading,
                olderHistoryError = state.olderHistoryError,
                consumedHistoryCursors = state.consumedHistoryCursors,
                goal = state.threadGoal,
                tokenUsage = state.threadTokenUsage,
                settings = SessionSettings(
                    model = state.selectedModel,
                    reasoningEffort = state.selectedReasoningEffort,
                    serviceTier = state.selectedServiceTier,
                    collaborationMode = state.selectedCollaborationMode,
                    permissionProfile = state.selectedPermissionProfile,
                    approvalPolicy = state.approvalPolicy,
                    approvalsReviewer = state.approvalsReviewer,
                ),
                isTurnRunning = state.isTurnRunning,
                activeTurnId = state.activeTurnId,
                expectedTurnId = if (state.isTurnRunning) {
                    state.activeTurnId ?: cached.expectedTurnId
                } else {
                    null
                },
                streamStatus = when {
                    state.isTurnRunning -> SessionStreamStatus.RUNNING
                    cached.streamStatus == SessionStreamStatus.FAILED -> SessionStreamStatus.FAILED
                    else -> SessionStreamStatus.IDLE
                },
            )
        }
        return applySessionMutation(updated, "This task exceeded safe cache limits.")
    }

    private fun projectSessionRegistry() {
        val registry = sessionRegistry
        _state.update { it.withSessionRegistry(registry) }
    }

    private fun rejectNewWorkDuringNetworkHandoff(): Boolean {
        if (!networkHandoffPending) return false
        _state.update {
            it.copy(notice = "Network route changed; wait for SSH reconnection before starting new work.")
        }
        return true
    }

    private fun applySessionMutation(
        mutation: SessionRegistryMutation,
        failureNotice: String,
    ): Boolean {
        sessionRegistry = mutation.registry
        if (mutation.applied) return true
        _state.update { state -> state.copy(isBusy = false, notice = failureNotice) }
        return false
    }

    private fun restoreOlderHistoryLoadingAfterRejection(threadId: String) {
        val restored = sessionRegistry.clearOlderHistoryLoading(threadId)
        if (!restored.applied) {
            disconnectInternal(clearActive = false)
            _state.update { state ->
                state.copy(
                    showConnections = true,
                    notice = "History loading state could not be restored safely; disconnected.",
                )
            }
            return
        }
        sessionRegistry = restored.registry
        projectSessionRegistry()
    }

    private fun failClosedTurnIdentity(
        client: CodexRpcClient,
        connectionGeneration: Long,
        message: String,
    ) {
        if (!isCurrentConnection(client, connectionGeneration)) return
        disconnectInternal(clearActive = false)
        _state.update { state ->
            state.copy(
                showConnections = true,
                notice = message,
            )
        }
    }

    private fun routeSessionEvent(
        client: CodexRpcClient,
        connectionGeneration: Long,
        event: AppServerEvent,
    ): Boolean {
        if (!isCurrentConnection(client, connectionGeneration)) return false
        if (!captureSelectedSession()) return false
        val result = SessionEventRouter.route(
            registry = sessionRegistry,
            event = event,
            knownThreadIds = knownSessionThreadIds(),
        )
        sessionRegistry = result.registry
        projectSessionRegistry()
        val rejection = (result.disposition as? SessionRouteDisposition.Rejected)?.rejection
        if (rejection?.disconnectRecommended == true && isCurrentConnection(client, connectionGeneration)) {
            disconnectInternal(clearActive = false)
            _state.update { state ->
                state.copy(
                    showConnections = true,
                    notice = "Remote session ownership could not be verified; disconnected without approving.",
                )
            }
        }
        return result.disposition is SessionRouteDisposition.Applied
    }

    private fun observeEvents(client: CodexRpcClient, connectionGeneration: Long) {
        eventJob?.cancel()
        eventJob = viewModelScope.launch {
            client.events.collect { event ->
                if (!isCurrentConnection(client, connectionGeneration)) return@collect
                when (event) {
                    is AppServerEvent.ItemUpsert -> approvalFlowMutex.withLock {
                        routeSessionEvent(client, connectionGeneration, event)
                    }
                    is AppServerEvent.AgentDelta -> routeSessionEvent(client, connectionGeneration, event)
                    is AppServerEvent.PlanDelta -> routeSessionEvent(client, connectionGeneration, event)
                    is AppServerEvent.ReasoningDelta -> routeSessionEvent(client, connectionGeneration, event)
                    is AppServerEvent.OutputDelta -> routeSessionEvent(client, connectionGeneration, event)
                    is AppServerEvent.TurnRunning -> {
                        val threadId = event.threadId?.takeIf(String::isNotBlank)
                        val turnId = event.turnId?.takeIf(String::isNotBlank)
                        if (event.running && threadId != null && turnId != null &&
                            !sessionRequestTracker.canObserveTurnStarted(threadId, turnId)
                        ) {
                            failClosedTurnIdentity(
                                client,
                                connectionGeneration,
                                "Remote repeated or changed a completed turn ID; disconnected safely.",
                            )
                            return@collect
                        }
                        val applied = routeSessionEvent(client, connectionGeneration, event)
                        if (applied) {
                            if (threadId != null && turnId != null) {
                                if (event.running) {
                                    sessionRequestTracker.observeTurnStarted(threadId, turnId)
                                } else {
                                    sessionRequestTracker.observeTurnCompleted(threadId, turnId)
                                }
                            }
                        }
                        if (applied && !event.running) {
                            refreshThreads()
                        }
                    }
                    is AppServerEvent.Approval -> approvalFlowMutex.withLock {
                        routeSessionEvent(client, connectionGeneration, event)
                    }
                    is AppServerEvent.ApprovalResolved -> {
                        approvalFlowMutex.withLock {
                            routeSessionEvent(client, connectionGeneration, event)
                        }
                    }
                    AppServerEvent.AccountChanged -> refreshRemoteAccount()
                    is AppServerEvent.ThreadStarted -> {
                        registerStartedThread(event)
                        refreshThreads()
                    }
                    AppServerEvent.ThreadsChanged -> refreshThreads()
                    AppServerEvent.SkillsChanged -> refreshComposerCatalog(forceReload = true)
                    is AppServerEvent.GoalUpdated -> if (routeSessionEvent(client, connectionGeneration, event)) {
                        _state.update { state ->
                            if (state.selectedThreadId == event.threadId) {
                                state.copy(isGoalLoading = false, goalError = null)
                            } else {
                                state
                            }
                        }
                    }
                    is AppServerEvent.GoalCleared -> if (routeSessionEvent(client, connectionGeneration, event)) {
                        _state.update { state ->
                            if (state.selectedThreadId == event.threadId) {
                                state.copy(isGoalLoading = false, goalError = null)
                            } else {
                                state
                            }
                        }
                    }
                    is AppServerEvent.TokenUsageUpdated -> routeSessionEvent(client, connectionGeneration, event)
                    is AppServerEvent.RateLimitsUpdated -> _state.update {
                        it.copy(rateLimits = event.rateLimits, isStatusLoading = false, statusError = null)
                    }
                    is AppServerEvent.ContextCompacted -> {
                        if (routeSessionEvent(client, connectionGeneration, event) &&
                            _state.value.selectedThreadId == event.threadId
                        ) {
                            _state.update { it.copy(notice = "Task context compacted") }
                        }
                    }
                    is AppServerEvent.McpLoginCompleted -> {
                        _state.update {
                            it.copy(
                                isMcpLoginStarting = false,
                                notice = if (event.success) {
                                    "${event.name} authorization completed"
                                } else {
                                    event.error ?: "${event.name} authorization was not completed"
                                },
                            )
                        }
                        if (event.success) reloadMcpServers()
                    }
                    is AppServerEvent.ThreadSettingsUpdated -> routeSessionEvent(client, connectionGeneration, event)
                    is AppServerEvent.LoginCompleted -> {
                        if (event.success) {
                            _state.update { it.copy(remoteDeviceLogin = null, isLoginStarting = false) }
                            refreshRemoteAccount()
                        } else {
                            _state.update {
                                it.copy(
                                    remoteDeviceLogin = null,
                                    isLoginStarting = false,
                                    notice = event.error ?: "Remote Codex sign-in failed",
                                )
                            }
                        }
                    }
                    is AppServerEvent.Failure -> {
                        val message = friendlyError(IllegalStateException(event.message))
                        if (event.threadId == null) {
                            if (rpc === client) {
                                if (event.kind == FailureKind.TRANSPORT && shouldMaintainConnection) {
                                    val hadUnconfirmedWork =
                                        (_state.value.connectionStatus == ConnectionStatus.CONNECTED &&
                                            event.hadPendingRequests) ||
                                        hasRunningTurn() ||
                                        hasPendingApproval()
                                    if (suspendForAmbiguousApprovalDelivery()) return@collect
                                    if (hadUnconfirmedWork) {
                                        reconnectHadUnconfirmedWork = true
                                        Log.w(
                                            CONNECTION_LOG_TAG,
                                            "state=transport_interrupted in_flight_work_unconfirmed=true",
                                        )
                                    }
                                    rememberSelectionForReconnect()
                                    disconnectInternal(clearActive = false, suspendMaintenance = false)
                                    scheduleReconnect(message)
                                } else {
                                    disconnectInternal(clearActive = false)
                                    _state.update { it.afterGlobalAppServerFailure(message) }
                                }
                            }
                        } else {
                            val applied = routeSessionEvent(client, connectionGeneration, event)
                            if (applied && _state.value.selectedThreadId == event.threadId) {
                                _state.update { state -> state.copy(notice = message) }
                            }
                        }
                    }
                    is AppServerEvent.FatalProtocolError -> {
                        if (rpc === client) {
                            disconnectInternal(clearActive = false)
                            _state.update { state ->
                                state.copy(
                                    showConnections = true,
                                    notice = event.message,
                                )
                            }
                        }
                    }
                    is AppServerEvent.Warning -> _state.update { it.copy(notice = event.message) }
                    is AppServerEvent.Diagnostic -> {
                        if (event.message.contains("not found", ignoreCase = true) ||
                            event.message.contains("not recognized", ignoreCase = true)
                        ) {
                            _state.update { it.copy(notice = "Remote login shell cannot find the codex command: ${event.message}") }
                        }
                    }
                }
            }
        }
    }

    private fun refreshRemoteAccount() {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            runCatching {
                val account = client.readAccount()
                val models = client.listModels()
                account to models
            }.onSuccess { (account, models) ->
                if (!isCurrentConnection(client, connectionGeneration)) return@onSuccess
                _state.update { state ->
                    val selected = models.firstOrNull { it.id == state.selectedModel }
                        ?: models.firstOrNull { it.isDefault }
                        ?: models.firstOrNull()
                    val effort = state.selectedReasoningEffort
                        ?.takeIf { selected?.supports(it) == true }
                        ?: selected?.preferredReasoningEffort()
                    val serviceTier = state.selectedServiceTier
                        ?.takeIf { tier -> selected?.serviceTiers?.any { it.id == tier } == true }
                        ?: selected?.defaultServiceTier
                    state.copy(
                        remoteAccount = account,
                        models = models,
                        selectedModel = selected?.id,
                        selectedReasoningEffort = effort,
                        selectedServiceTier = serviceTier,
                    )
                }
            }.onFailure { error ->
                if (!error.isCancellation() && isCurrentConnection(client, connectionGeneration)) showError(error)
            }
        }
    }

    private fun updateGoalFailure(
        threadId: String,
        action: String,
        error: Throwable,
        loading: Boolean = false,
    ) {
        val message = friendlyGoalError(error)
        _state.update { state ->
            if (state.selectedThreadId == threadId) {
                state.copy(
                    isGoalLoading = loading,
                    goalError = message,
                    notice = "$action: $message",
                )
            } else {
                state
            }
        }
    }

    private fun applyGoalResult(threadId: String, goal: com.codex.remote.domain.ThreadGoal?) {
        if (!captureSelectedSession()) return
        val updated = sessionRegistry.updateSession(threadId, markUnread = false) {
            it.copy(goal = goal)
        }
        if (!applySessionMutation(updated, "Remote goal state could not be cached safely.")) return
        _state.update { state ->
            val projected = state.withSessionRegistry(sessionRegistry)
            if (projected.selectedThreadId == threadId) {
                projected.copy(isGoalLoading = false, goalError = null)
            } else {
                projected
            }
        }
    }

    private fun friendlyGoalError(error: Throwable): String {
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .firstOrNull { it.isNotBlank() }
            ?: error::class.java.simpleName
        return when {
            message.contains("goals feature is disabled", ignoreCase = true) ->
                "Goals are not enabled on remote Codex. Set goals = true under [features] in remote config.toml, then reconnect."
            message.contains("ephemeral thread does not support goals", ignoreCase = true) ->
                "This task is not persistent yet. Send the first message before setting a goal."
            error.isUnsupportedRpcMethod("thread/goal/get") ||
                error.isUnsupportedRpcMethod("thread/goal/set") ||
                error.isUnsupportedRpcMethod("thread/goal/clear") ->
                "The remote Codex version does not support Goals. Update remote Codex first."
            message.contains("method not found", ignoreCase = true) ||
                message.contains("unknown method", ignoreCase = true) ->
                "The remote Codex version does not support Goals. Update remote Codex first."
            else -> friendlyError(error)
        }
    }

    private fun refreshThreads() {
        val client = rpc ?: return
        if (_state.value.activeConnection == null) return
        val connectionEpoch = sessionRequestTracker.connectionGeneration
        viewModelScope.launch {
            runCatching { client.listThreads() }
                .onSuccess { threads ->
                    if (rpc !== client || sessionRequestTracker.connectionGeneration != connectionEpoch) {
                        return@onSuccess
                    }
                    if (!captureSelectedSession()) return@onSuccess
                    for (thread in threads.filter { it.id in sessionRegistry.sessions }) {
                        val refreshed = sessionRegistry.refreshThreadMetadata(thread)
                        if (!applySessionMutation(refreshed, "Task metadata could not be refreshed safely.")) {
                            return@onSuccess
                        }
                    }
                    val projects = groupThreadsByProject(threads)
                    val previousSelection = _state.value.selectionContext()
                    _state.update { state ->
                        val selectedPath = state.selectedProjectPath
                            ?.takeIf { path -> projects.any { it.path == path } }
                            ?: projects.firstOrNull()?.path
                        val selectedThreadId = state.selectedThreadId
                            ?.takeIf { id -> threads.any { it.id == id } }
                        state.copy(
                            threads = threads,
                            projects = projects,
                            selectedProjectPath = selectedPath,
                            selectedThreadId = selectedThreadId,
                            threadGoal = if (selectedThreadId == null) null else state.threadGoal,
                            isGoalLoading = if (selectedThreadId == null) false else state.isGoalLoading,
                            goalError = if (selectedThreadId == null) null else state.goalError,
                            threadTokenUsage = if (selectedThreadId == null) null else state.threadTokenUsage,
                            timeline = if (selectedThreadId == null && state.selectedThreadId != null) {
                                emptyList()
                            } else {
                                state.timeline
                            },
                            olderHistoryCursor = if (selectedThreadId == null) null else state.olderHistoryCursor,
                            hasOlderHistory = if (selectedThreadId == null) false else state.hasOlderHistory,
                            isOlderHistoryLoading = if (selectedThreadId == null) false else state.isOlderHistoryLoading,
                            olderHistoryError = if (selectedThreadId == null) null else state.olderHistoryError,
                            consumedHistoryCursors = if (selectedThreadId == null) emptySet() else state.consumedHistoryCursors,
                            connectionMessage = connectionSummary(
                                projects.size,
                                threads.size,
                                state.remoteServer?.codexVersion.orEmpty(),
                            ),
                        )
                    }
                    val currentSelection = _state.value.selectionContext()
                    sessionRequestTracker.reconcileSelection(
                        previousThreadId = previousSelection.threadId,
                        previousProjectPath = previousSelection.projectPath,
                        currentThreadId = currentSelection.threadId,
                        currentProjectPath = currentSelection.projectPath,
                    )
                    if (_state.value.selectedThreadId == null) {
                        if (previousSelection.threadId != null) {
                            _state.update { state ->
                                state.copy(
                                    selectedPermissionProfile = SAFE_PERMISSION_PROFILE,
                                    approvalPolicy = SAFE_APPROVAL_POLICY,
                                    approvalsReviewer = SAFE_APPROVALS_REVIEWER,
                                )
                            }
                        }
                        sessionRegistry = sessionRegistry.clearSelection()
                    }
                    projectSessionRegistry()
                }
        }
    }

    private fun registerStartedThread(event: AppServerEvent.ThreadStarted) {
        if (!captureSelectedSession()) return
        val registered = sessionRegistry.registerThread(event.threadId, event.thread)
        sessionRegistry = registered.registry
        if (!registered.applied) {
            _state.update { state ->
                state.copy(notice = "A newly started remote task could not be cached safely")
            }
            return
        }
        event.thread?.let { started ->
            _state.update { state ->
                val threads = (listOf(started) + state.threads).distinctBy(RemoteThread::id)
                state.copy(
                    threads = threads,
                    projects = groupThreadsByProject(threads),
                )
            }
        }
        projectSessionRegistry()
    }

    private suspend fun loadComposerCatalog(
        client: CodexRpcClient,
        cwds: List<String>,
        forceReload: Boolean = false,
    ): ComposerCatalog {
        val distinctCwds = cwds.distinct()
        val skills = runCatching {
            withTimeout(45_000) { client.listSkills(distinctCwds, forceReload) }
        }
        val plugins = runCatching {
            withTimeout(45_000) { client.listInstalledPlugins(distinctCwds) }
        }
        skills.exceptionOrNull()?.let { error -> if (error.isCancellation()) throw error }
        plugins.exceptionOrNull()?.let { error -> if (error.isCancellation()) throw error }
        val errors = listOfNotNull(
            skills.exceptionOrNull()?.message?.let { "Skills: $it" },
            plugins.exceptionOrNull()?.message?.let { "Plugins: $it" },
        )
        return ComposerCatalog(
            skills = skills.getOrDefault(emptyList()),
            plugins = plugins.getOrDefault(emptyList()),
            error = errors.takeIf { it.isNotEmpty() }?.joinToString(" · "),
        )
    }

    private fun refreshComposerCatalog(forceReload: Boolean = false) {
        val client = rpc ?: return
        val connectionGeneration = sessionRequestTracker.connectionGeneration
        val cwds = _state.value.projects.map { it.path }.filter(String::isNotBlank)
        _state.update { it.copy(isComposerCatalogLoading = true, composerCatalogError = null) }
        viewModelScope.launch {
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            val catalog = loadComposerCatalog(client, cwds, forceReload)
            if (!isCurrentConnection(client, connectionGeneration)) return@launch
            _state.update {
                it.copy(
                    skills = catalog.skills,
                    plugins = catalog.plugins,
                    isComposerCatalogLoading = false,
                    composerCatalogError = catalog.error,
                )
            }
        }
    }

    private fun showError(error: Throwable) {
        _state.update { it.copy(isBusy = false, notice = friendlyError(error)) }
    }

    private fun closeClientInBackground(client: CodexRpcClient?) {
        if (client == null) return
        client.beginClose()
        transportCleanupScope.launch {
            runCatching { client.close() }
        }
    }

    override fun onCleared() {
        val detachedClient = rpc
        rpc = null
        closeClientInBackground(detachedClient)
        super.onCleared()
    }
}

internal data class SessionSelectionContext(
    val threadId: String?,
    val projectPath: String?,
)

internal fun AppUiState.selectionContext(): SessionSelectionContext = SessionSelectionContext(
    threadId = selectedThreadId,
    projectPath = selectedProjectPath,
)

internal fun AppUiState.matchesSelection(context: SessionSelectionContext): Boolean =
    selectedThreadId == context.threadId && selectedProjectPath == context.projectPath

internal fun resolveResumedTurnId(
    remoteActiveTurnId: String?,
    cachedIsRunning: Boolean,
    cachedActiveTurnId: String?,
    cachedExpectedTurnId: String?,
): String? = remoteActiveTurnId?.takeIf(String::isNotBlank)
    ?: if (cachedIsRunning) {
        cachedActiveTurnId?.takeIf(String::isNotBlank)
            ?: cachedExpectedTurnId?.takeIf(String::isNotBlank)
    } else {
        null
    }

internal fun SessionRegistry.clearOlderHistoryLoading(threadId: String): SessionRegistryMutation =
    updateSession(threadId, markUnread = false) { session ->
        session.copy(isOlderHistoryLoading = false)
    }

internal fun friendlyError(error: Throwable): String {
    val causeMessages = generateSequence(error) { it.cause }
        .mapNotNull { cause -> cause.message?.takeIf { it.isNotBlank() } }
        .toList()
    val message = causeMessages.firstOrNull() ?: error::class.java.simpleName
    return when {
        causeMessages.any { causeMessage ->
            causeMessage.contains("Software caused connection abort", ignoreCase = true) ||
                causeMessage.contains("connection abort", ignoreCase = true) ||
                causeMessage.contains("ECONNABORTED", ignoreCase = true)
        } -> "SSH 연결이 중단되었습니다. 휴대폰 네트워크와 중계 서버 연결을 확인하고 다시 시도하세요."
        causeMessages.any { causeMessage ->
            causeMessage.contains("not logged in", ignoreCase = true) ||
                causeMessage.contains("OpenAI authentication", ignoreCase = true)
        } -> "Remote Codex is not signed in. Sign in from the app or run codex login on the remote host."
        causeMessages.any { it.contains("auth", ignoreCase = true) } ->
            "SSH authentication failed. Check the username and credentials. $message"
        causeMessages.any { it.contains("timed out", ignoreCase = true) } ->
            "Connection timed out. Check the host, port, VPN, and firewall."
        causeMessages.any { it.contains("refused", ignoreCase = true) } ->
            "SSH connection was refused. Confirm that sshd is listening."
        else -> message
    }
}

internal fun AppUiState.withSessionRegistry(registry: SessionRegistry): AppUiState {
    val indicators = registry.sessions.mapValues { (_, session) ->
        ThreadSessionIndicator(
            isRunning = session.isTurnRunning,
            approvalCount = session.approvalQueue.entries.size,
            unreadCount = session.unreadCount,
            hasFailure = session.streamStatus == SessionStreamStatus.FAILED,
        )
    }
    val visibleApprovals = registry.visibleApprovalQueue(approvalQueue)
    val session = registry.selectedSession
        ?: return copy(
            sessionIndicators = indicators,
            approvalQueue = visibleApprovals,
        )
    val authorization = session.settings.safeAuthorizationOrDefault()
    val defaults = defaultSessionSettings()
    val configuredModel = session.settings.model
        ?.takeIf { id -> models.any { it.id == id } }
        ?: defaults.model
    val model = models.firstOrNull { it.id == configuredModel }
    return copy(
        selectedThreadId = session.threadId,
        sessionIndicators = indicators,
        timeline = session.timeline,
        olderHistoryCursor = session.olderHistoryCursor,
        hasOlderHistory = session.hasOlderHistory,
        isOlderHistoryLoading = session.isOlderHistoryLoading,
        olderHistoryError = session.olderHistoryError,
        consumedHistoryCursors = session.consumedHistoryCursors,
        threadGoal = session.goal,
        threadTokenUsage = session.tokenUsage,
        isTurnRunning = session.isTurnRunning,
        activeTurnId = session.activeTurnId,
        approvalQueue = visibleApprovals,
        selectedModel = configuredModel,
        selectedReasoningEffort = session.settings.reasoningEffort
            ?.takeIf { effort -> model?.supports(effort) == true }
            ?: model?.preferredReasoningEffort()
            ?: defaults.reasoningEffort,
        selectedServiceTier = session.settings.serviceTier
            ?.takeIf { tier -> model?.serviceTiers?.any { it.id == tier } == true }
            ?: model?.defaultServiceTier
            ?: defaults.serviceTier,
        selectedCollaborationMode = session.settings.collaborationMode
            ?.takeIf { mode -> collaborationModes.any { it.mode == mode } }
            ?: defaults.collaborationMode
            ?: "default",
        selectedPermissionProfile = authorization.permissionProfile,
        approvalPolicy = requireNotNull(authorization.approvalPolicy),
        approvalsReviewer = requireNotNull(authorization.approvalsReviewer),
    )
}

private fun List<TimelineItem>.takeLastWithin(maxItems: Int, maxChars: Long): List<TimelineItem> {
    var start = size
    var retainedItems = 0
    var retainedChars = 0L
    for (index in lastIndex downTo 0) {
        if (retainedItems >= maxItems) break
        val itemChars = this[index].retainedCharacterCount()
        if (itemChars > maxChars - retainedChars) break
        retainedItems += 1
        retainedChars += itemChars
        start = index
    }
    return subList(start, size).toList()
}

private data class ConnectionBootstrap(
    val server: RemoteServerInfo,
    val account: RemoteAccount,
    val models: List<RemoteModel>,
    val threads: List<RemoteThread>,
    val collaborationModes: List<RemoteCollaborationMode>,
    val permissionProfiles: List<com.codex.remote.domain.RemotePermissionProfile>,
)

private data class ReconnectSelection(
    val projectPath: String?,
    val threadId: String?,
)

private class SupersededConnectionException : Exception("Connection attempt was superseded")

private fun Throwable.isCancellation(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is CancellationException && it !is TimeoutCancellationException
    }

internal fun Throwable.isRetryableConnectionFailure(): Boolean {
    val causes = generateSequence(this) { it.cause }.toList()
    if (causes.any {
            it is UnknownHostKeyException ||
                it is HostKeyChangedException ||
                it is RemoteCodexUnavailableException ||
                it is SecurityException
        }
    ) {
        return false
    }
    if (causes.any { cause ->
            val type = cause::class.java.simpleName.lowercase()
            val message = cause.message.orEmpty().lowercase()
            type.contains("userauth") ||
                message.contains("authentication") ||
                message.contains("auth fail") ||
                message.contains("permission denied") ||
                message.contains("private key") ||
                message.contains("password")
        }
    ) {
        return false
    }
    causes.filterIsInstance<SSHException>().forEach { error ->
        when (error.disconnectReason) {
            DisconnectReason.CONNECTION_LOST -> return true
            DisconnectReason.UNKNOWN -> Unit
            else -> return false
        }
    }
    if (causes.any { cause ->
            val message = cause.message.orEmpty().lowercase()
            message.contains("protocol error") ||
                message.contains("protocol version") ||
                message.contains("key exchange") ||
                message.contains("algorithm negotiation") ||
                message.contains("no matching") ||
                message.contains("mac error") ||
                message.contains("host key")
        }
    ) {
        return false
    }
    if (causes.any { it is IOException || it is TimeoutCancellationException }) {
        return true
    }
    return causes.any { cause ->
        val message = cause.message.orEmpty().lowercase()
        cause is RpcException && message.contains("connection closed") ||
            message.contains("connection reset") ||
            message.contains("broken pipe") ||
            message.contains("timed out") ||
            message.contains("timeout") ||
            message.contains("network is unreachable") ||
            message.contains("no route to host") ||
            message.contains("stream was interrupted")
    }
}

internal fun AppUiState.afterGlobalAppServerFailure(message: String): AppUiState =
    afterDisconnect(clearActive = false).copy(
        connectionStatus = ConnectionStatus.ERROR,
        connectionMessage = message,
        showConnections = true,
        notice = message,
    )

internal fun AppUiState.afterDisconnect(clearActive: Boolean): AppUiState = copy(
    activeConnection = if (clearActive) null else activeConnection,
    connectionStatus = ConnectionStatus.DISCONNECTED,
    connectionMessage = "",
    threads = if (clearActive) emptyList() else threads,
    archivedThreads = if (clearActive) emptyList() else archivedThreads,
    isArchivedThreadsLoading = false,
    archivedThreadsError = null,
    projects = if (clearActive) emptyList() else projects,
    sessionIndicators = emptyMap(),
    selectedProjectPath = if (clearActive) null else selectedProjectPath,
    selectedThreadId = null,
    threadGoal = null,
    isGoalLoading = false,
    goalError = null,
    timeline = emptyList(),
    olderHistoryCursor = null,
    hasOlderHistory = false,
    isOlderHistoryLoading = false,
    olderHistoryError = null,
    consumedHistoryCursors = emptySet(),
    models = emptyList(),
    selectedModel = null,
    selectedReasoningEffort = null,
    selectedServiceTier = null,
    collaborationModes = emptyList(),
    selectedCollaborationMode = "default",
    permissionProfiles = emptyList(),
    selectedPermissionProfile = SAFE_PERMISSION_PROFILE,
    approvalPolicy = SAFE_APPROVAL_POLICY,
    approvalsReviewer = SAFE_APPROVALS_REVIEWER,
    remoteServer = null,
    remoteAccount = null,
    remoteDeviceLogin = null,
    isLoginStarting = false,
    mcpServers = emptyList(),
    isMcpStatusLoading = false,
    mcpStatusError = null,
    isMcpLoginStarting = false,
    mcpAuthorizationUrl = null,
    isFeedbackSubmitting = false,
    feedbackError = null,
    rateLimits = null,
    threadTokenUsage = null,
    isStatusLoading = false,
    statusError = null,
    isTurnRunning = false,
    activeTurnId = null,
    isBusy = false,
    approvalQueue = ApprovalQueue(),
    approvalFileItems = emptyMap(),
    pendingHostKeyFingerprint = null,
)

private const val SAFE_PERMISSION_PROFILE = ":workspace"
private const val SAFE_APPROVAL_POLICY = "on-request"
private const val SAFE_APPROVALS_REVIEWER = "user"
private const val NETWORK_CHANGE_DEBOUNCE_MILLIS = 500L
private const val NETWORK_HANDOFF_RECHECK_MILLIS = 1_000L
private const val NETWORK_PENDING_RPC_GRACE_MILLIS = 5_000L
private const val RECONNECT_STABILITY_WINDOW_MILLIS = 30_000L
private const val CONNECTION_LOG_TAG = "CodexRemoteConnection"

private fun SessionSettings.safeAuthorizationOrDefault(): SessionSettings {
    val profile = permissionProfile?.takeIf(String::isNotBlank)
    val policy = approvalPolicy?.takeIf(String::isNotBlank)
    val reviewer = approvalsReviewer?.takeIf(String::isNotBlank)
    return if (profile != null && policy != null && reviewer != null) {
        copy(
            permissionProfile = profile,
            approvalPolicy = policy,
            approvalsReviewer = reviewer,
        )
    } else {
        copy(
            permissionProfile = SAFE_PERMISSION_PROFILE,
            approvalPolicy = SAFE_APPROVAL_POLICY,
            approvalsReviewer = SAFE_APPROVALS_REVIEWER,
        )
    }
}

internal fun SessionSettings.withRemoteAuthorization(
    permissionProfile: String?,
    approvalPolicy: String?,
    approvalsReviewer: String?,
): SessionSettings = copy(
    permissionProfile = permissionProfile,
    approvalPolicy = approvalPolicy,
    approvalsReviewer = approvalsReviewer,
).safeAuthorizationOrDefault()

internal const val MAX_CACHED_APPROVAL_FILE_ITEMS = 256
internal const val MAX_CACHED_APPROVAL_FILE_CHARS = 4L * 1024L * 1024L

private fun Map<ApprovalFileItemKey, TimelineItem>.recordFileApprovalItems(
    threadId: String,
    items: List<TimelineItem>,
): Map<ApprovalFileItemKey, TimelineItem> = items.fold(this) { cached, item ->
    cached.recordFileApprovalItem(threadId, item)
}

internal fun Map<ApprovalFileItemKey, TimelineItem>.recordFileApprovalItem(
    threadId: String?,
    item: TimelineItem,
): Map<ApprovalFileItemKey, TimelineItem> {
    val exactThreadId = threadId?.takeIf(String::isNotBlank) ?: return this
    val exactTurnId = item.turnId?.takeIf(String::isNotBlank) ?: return this
    if (item.id.isBlank()) return this
    val key = ApprovalFileItemKey(exactThreadId, exactTurnId, item.id)
    val updated = LinkedHashMap(this)
    updated.remove(key)
    item.fileApprovalSnapshotOrNull()?.let { snapshot ->
        if (snapshot.approvalFileSnapshotRetainedCharCount <= MAX_CACHED_APPROVAL_FILE_CHARS) {
            updated[key] = snapshot
        }
    }
    var retainedChars = updated.values.fold(0L) { total, snapshot ->
        val snapshotChars = snapshot.approvalFileSnapshotRetainedCharCount
        if (total > Long.MAX_VALUE - snapshotChars) Long.MAX_VALUE else total + snapshotChars
    }
    while (
        updated.size > MAX_CACHED_APPROVAL_FILE_ITEMS ||
        retainedChars > MAX_CACHED_APPROVAL_FILE_CHARS
    ) {
        val oldest = updated.keys.firstOrNull() ?: break
        val removed = updated.remove(oldest) ?: continue
        retainedChars = if (retainedChars == Long.MAX_VALUE) {
            updated.values.fold(0L) { total, snapshot ->
                val snapshotChars = snapshot.approvalFileSnapshotRetainedCharCount
                if (total > Long.MAX_VALUE - snapshotChars) Long.MAX_VALUE else total + snapshotChars
            }
        } else {
            (retainedChars - removed.approvalFileSnapshotRetainedCharCount).coerceAtLeast(0L)
        }
    }
    return updated
}

internal fun List<SavedConnection>.lastUsedConnectionOrNull(): SavedConnection? =
    maxByOrNull(SavedConnection::lastUsedAt)?.takeIf { it.lastUsedAt > 0 }

internal fun List<SavedConnection>.desiredConnectionOrNull(desiredConnectionId: String?): SavedConnection? =
    desiredConnectionId?.let { desiredId -> firstOrNull { it.id == desiredId } }

internal fun Throwable.isUnsupportedRpcMethod(method: String): Boolean =
    generateSequence(this) { it.cause }.any { error ->
        val message = error.message.orEmpty()
        (error as? com.codex.remote.data.rpc.RpcException)?.code == -32601 ||
            (message.contains(method, ignoreCase = true) &&
                listOf("unsupported method", "method not found", "unknown method", "not implemented")
                    .any { marker -> message.contains(marker, ignoreCase = true) })
    }

private data class ComposerCatalog(
    val skills: List<com.codex.remote.domain.RemoteSkill>,
    val plugins: List<com.codex.remote.domain.RemotePlugin>,
    val error: String?,
)

internal fun resolveComposerMentions(
    prompt: String,
    cwd: String,
    selectedMentions: List<ComposerMention>,
    state: AppUiState,
): List<ComposerMention> {
    val selected = selectedMentions.filter { prompt.containsComposerToken(it.token) }
    val skills = state.skills
        .filter { skill -> skill.enabled && (skill.cwds.isEmpty() || cwd in skill.cwds) }
        .filter { prompt.containsComposerToken(it.composerToken()) }
        .map { skill ->
            ComposerMention(ComposerMentionKind.SKILL, skill.name, skill.path, skill.composerToken())
        }
    val plugins = state.plugins
        .filter { it.enabled && prompt.containsComposerToken(it.composerToken()) }
        .map { plugin ->
            ComposerMention(ComposerMentionKind.PLUGIN, plugin.displayName, plugin.mentionPath, plugin.composerToken())
        }
    return (selected + skills + plugins).distinctBy { "${it.kind}:${it.path}" }
}

private fun RemoteModel.supports(effort: String): Boolean =
    supportedReasoningEfforts.any { it.value == effort }

private fun RemoteModel.preferredReasoningEffort(): String? =
    defaultReasoningEffort?.takeIf(::supports)
        ?: supportedReasoningEfforts.firstOrNull()?.value

private fun AppUiState.defaultCollaborationMode(): String =
    collaborationModes.firstOrNull { it.mode == "default" }?.mode
        ?: collaborationModes.firstOrNull()?.mode
        ?: "default"

internal fun AppUiState.defaultSessionSettings(): SessionSettings {
    val model = models.firstOrNull(RemoteModel::isDefault) ?: models.firstOrNull()
    return SessionSettings(
        model = model?.id,
        reasoningEffort = model?.preferredReasoningEffort(),
        serviceTier = model?.defaultServiceTier,
        collaborationMode = defaultCollaborationMode(),
        permissionProfile = SAFE_PERMISSION_PROFILE,
        approvalPolicy = SAFE_APPROVAL_POLICY,
        approvalsReviewer = SAFE_APPROVALS_REVIEWER,
    )
}

private fun String.isActiveTimelineStatus(): Boolean =
    equals("inProgress", ignoreCase = true) ||
        equals("in_progress", ignoreCase = true) ||
        equals("running", ignoreCase = true) ||
    equals("started", ignoreCase = true)

internal fun List<TimelineItem>.withRunningItemsCompleted(): List<TimelineItem> = map { item ->
    if (item.status.isActiveTimelineStatus()) item.copy(status = "completed") else item
}

private fun String.isRemoteThreadActive(): Boolean =
    equals("active", ignoreCase = true) || equals("inProgress", ignoreCase = true)

internal fun shouldBlockArchive(thread: RemoteThread, session: SessionState?): Boolean =
    thread.status.isRemoteThreadActive() || session?.hasProtectedWork == true

internal fun approvalBelongsToLiveTurn(owner: SessionState, request: ApprovalRequest): Boolean {
    val turnId = request.turnId?.takeIf(String::isNotBlank) ?: return false
    return owner.isTurnRunning && (turnId == owner.activeTurnId || turnId == owner.expectedTurnId)
}

private fun connectionSummary(projects: Int, threads: Int, codexVersion: String): String = buildString {
    append("Imported $projects projects and $threads sessions")
    if (codexVersion.isNotBlank()) append(" · Codex $codexVersion")
}

internal fun remoteChildPath(root: String, child: String): String {
    val separator = if ('\\' in root && '/' !in root) '\\' else '/'
    val normalizedRoot = root.trimEnd('/', '\\')
    return if (normalizedRoot.isEmpty() && separator == '/') "/$child" else "$normalizedRoot$separator$child"
}

private val INIT_PROMPT = """
    Generate a file named AGENTS.md that serves as a contributor guide for this repository.
    Produce a clear, concise, and well-structured document with descriptive headings and actionable explanations.

    Requirements:
    - Title the document "Repository Guidelines".
    - Use Markdown headings for structure and keep the document around 200-400 words.
    - Describe project structure, build/test commands, coding style, testing guidelines, and commit/PR conventions.
    - Keep guidance specific to this repository and include concise examples where useful.
    - Add other relevant sections such as security, configuration, architecture, or agent instructions when appropriate.
""".trimIndent()

private val BUILT_IN_PERMISSION_PROFILES = setOf(":workspace", ":danger-full-access", ":read-only")
