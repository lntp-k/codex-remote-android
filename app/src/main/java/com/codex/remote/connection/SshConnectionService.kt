package com.codex.remote.connection

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.codex.remote.AppViewModel
import com.codex.remote.CodexRemoteApplication
import com.codex.remote.MainActivity
import com.codex.remote.R
import com.codex.remote.logging.AppLog

/**
 * Keeps a user-requested SSH session alive independently of Activity lifetime.
 * The actual SSH client remains owned by the process-wide AppViewModel.
 */
class SshConnectionService : Service() {
    private lateinit var applicationViewModel: AppViewModel
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var powerManager: PowerManager
    private var networkCallbackRegistered = false
    private var deviceIdleReceiverRegistered = false
    private val networkStateLock = Any()
    private var availableDefaultNetworkId: Long? = null
    private var hasReceivedNetworkCallback = false
    private var networkReportGeneration = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val initialNetworkSnapshot = Runnable {
        val activeNetwork = connectivityManager.activeNetwork
        val report = synchronized(networkStateLock) {
            if (hasReceivedNetworkCallback) {
                null
            } else {
                hasReceivedNetworkCallback = true
                networkReportGeneration += 1
                if (activeNetwork == null) {
                    availableDefaultNetworkId = null
                    InitialNetworkReport(
                        generation = networkReportGeneration,
                        networkId = null,
                    )
                } else {
                    availableDefaultNetworkId = activeNetwork.networkHandle
                    InitialNetworkReport(
                        generation = networkReportGeneration,
                        networkId = activeNetwork.networkHandle,
                    )
                }
            }
        }
        report?.let(::dispatchNetworkReport)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val networkId = network.networkHandle
            val report = synchronized(networkStateLock) {
                hasReceivedNetworkCallback = true
                if (availableDefaultNetworkId == networkId) {
                    null
                } else {
                    availableDefaultNetworkId = networkId
                    networkReportGeneration += 1
                    InitialNetworkReport(networkReportGeneration, networkId)
                }
            }
            report?.let(::dispatchNetworkReport)
        }

        override fun onLost(network: Network) {
            val networkId = network.networkHandle
            val report = synchronized(networkStateLock) {
                hasReceivedNetworkCallback = true
                if (availableDefaultNetworkId == networkId) {
                    availableDefaultNetworkId = null
                    networkReportGeneration += 1
                    InitialNetworkReport(networkReportGeneration, networkId = null)
                } else {
                    null
                }
            }
            report?.let(::dispatchNetworkReport)
        }
    }

    private val deviceIdleModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) {
                applicationViewModel.onDeviceIdleModeChanged(powerManager.isDeviceIdleMode)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.i(SERVICE_LOG_TAG, "onCreate")
        isRunning = true
        powerManager = getSystemService(PowerManager::class.java)
        createNotificationChannel()
        enterForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            if (!::applicationViewModel.isInitialized) {
                applicationViewModel = (application as CodexRemoteApplication).appViewModel
            }
            applicationViewModel.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (ConnectionMaintenanceStore(this).desiredConnectionId() == null) {
            isRunning = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!::applicationViewModel.isInitialized) {
            applicationViewModel = (application as CodexRemoteApplication).appViewModel
        }
        if (!deviceIdleReceiverRegistered) registerDeviceIdleModeReceiver()
        applicationViewModel.onDeviceIdleModeChanged(powerManager.isDeviceIdleMode)
        if (!networkCallbackRegistered) registerDefaultNetworkCallback()
        return START_STICKY
    }

    override fun onDestroy() {
        AppLog.i(SERVICE_LOG_TAG, "onDestroy")
        isRunning = false
        if (networkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        if (deviceIdleReceiverRegistered) {
            runCatching { unregisterReceiver(deviceIdleModeReceiver) }
            deviceIdleReceiverRegistered = false
        }
        synchronized(networkStateLock) {
            networkReportGeneration += 1
            availableDefaultNetworkId = null
        }
        mainHandler.removeCallbacks(initialNetworkSnapshot)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.ssh_connection_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.ssh_connection_notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun enterForeground() {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            OPEN_APP_REQUEST_CODE,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            DISCONNECT_REQUEST_CODE,
            Intent(this, SshConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.ssh_connection_notification_title))
            .setContentText(getString(R.string.ssh_connection_notification_text))
            .setContentIntent(openAppPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.disconnect),
                disconnectPendingIntent,
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun registerDefaultNetworkCallback() {
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        networkCallbackRegistered = true
        mainHandler.postDelayed(initialNetworkSnapshot, INITIAL_NETWORK_SNAPSHOT_DELAY_MILLIS)
    }

    private fun registerDeviceIdleModeReceiver() {
        ContextCompat.registerReceiver(
            this,
            deviceIdleModeReceiver,
            IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED),
            // This is a system broadcast. Ignore sender-provided data and always
            // re-read PowerManager so an untrusted broadcast cannot forge state.
            ContextCompat.RECEIVER_EXPORTED,
        )
        deviceIdleReceiverRegistered = true
    }

    private fun dispatchNetworkReport(report: InitialNetworkReport) {
        mainHandler.post {
            val isCurrent = synchronized(networkStateLock) {
                networkReportGeneration == report.generation &&
                    availableDefaultNetworkId == report.networkId
            }
            if (!isCurrent || !::applicationViewModel.isInitialized) return@post
            if (report.networkId != null) {
                applicationViewModel.onDefaultNetworkAvailable(report.networkId)
            } else {
                // The service has already rejected stale onLost callbacks and this
                // generation is the authoritative final default-network snapshot.
                applicationViewModel.onDefaultNetworkUnavailable()
            }
        }
    }

    companion object {
        @Volatile
        private var isRunning = false

        private const val SERVICE_LOG_TAG = "SshConnectionService"
        private const val ACTION_START = "com.codex.remote.connection.action.START"
        private const val ACTION_DISCONNECT = "com.codex.remote.connection.action.DISCONNECT"
        private const val NOTIFICATION_CHANNEL_ID = "ssh_connection"
        private const val NOTIFICATION_ID = 1_017
        private const val OPEN_APP_REQUEST_CODE = 1_018
        private const val DISCONNECT_REQUEST_CODE = 1_019
        private const val INITIAL_NETWORK_SNAPSHOT_DELAY_MILLIS = 1_000L

        fun start(context: Context): Boolean {
            if (isRunning) return true
            val intent = Intent(context, SshConnectionService::class.java).setAction(ACTION_START)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (error: SecurityException) {
                AppLog.e(SERVICE_LOG_TAG, "start_failed reason=security_exception", error)
                false
            } catch (error: RuntimeException) {
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    error::class.java.name == "android.app.ForegroundServiceStartNotAllowedException"
                ) {
                    AppLog.e(SERVICE_LOG_TAG, "start_failed reason=fgs_start_not_allowed", error)
                    false
                } else {
                    AppLog.e(SERVICE_LOG_TAG, "start_failed reason=unexpected", error)
                    throw error
                }
            }
        }

        fun stop(context: Context) {
            isRunning = false
            context.stopService(Intent(context, SshConnectionService::class.java))
        }
    }

    private data class InitialNetworkReport(
        val generation: Long,
        val networkId: Long?,
    )
}
