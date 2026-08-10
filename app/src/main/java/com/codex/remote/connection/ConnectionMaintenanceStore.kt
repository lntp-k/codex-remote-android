package com.codex.remote.connection

import android.content.Context

/**
 * Persists only the ID of the user-requested connection while maintenance is
 * enabled. Credentials remain in [com.codex.remote.data.store.ConnectionStore].
 *
 * A synchronous commit is intentional: START_STICKY may recreate the process
 * immediately after the foreground service is requested, so the target must be
 * durable before service startup is considered successful.
 */
internal class ConnectionMaintenanceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun desiredConnectionId(): String? = runCatching {
        preferences
            .getString(KEY_DESIRED_CONNECTION_ID, null)
            ?.takeIf(String::isNotBlank)
    }.getOrNull()

    fun remember(connectionId: String): Boolean {
        require(connectionId.isNotBlank()) { "connectionId must not be blank" }
        val previous = desiredConnectionId()
        val committed = runCatching {
            preferences.edit()
                .putString(KEY_DESIRED_CONNECTION_ID, connectionId)
                .commit()
        }.getOrDefault(false)
        if (!committed) {
            val rollback = preferences.edit()
            if (previous == null) rollback.remove(KEY_DESIRED_CONNECTION_ID)
            else rollback.putString(KEY_DESIRED_CONNECTION_ID, previous)
            rollback.apply()
        }
        return committed
    }

    fun clear(): Boolean = runCatching {
        preferences.edit()
            .remove(KEY_DESIRED_CONNECTION_ID)
            .commit()
    }.getOrDefault(false)

    private companion object {
        const val PREFERENCES_NAME = "connection_maintenance"
        const val KEY_DESIRED_CONNECTION_ID = "desired_connection_id"
    }
}
