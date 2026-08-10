package com.codex.remote

import com.codex.remote.domain.AuthType
import com.codex.remote.domain.SavedConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionRestorePolicyTest {
    private val previous = connection(id = "previous", lastUsedAt = 99L)
    private val desired = connection(id = "desired", lastUsedAt = 1L)
    private val connections = listOf(previous, desired)

    @Test
    fun explicitDisconnectMarkerDoesNotFallBackToTheLastUsedConnection() {
        assertNull(connections.desiredConnectionOrNull(desiredConnectionId = null))
    }

    @Test
    fun stickyRecoveryChoosesTheExactPersistedTargetRatherThanTheMostRecentHost() {
        assertEquals(desired, connections.desiredConnectionOrNull(desiredConnectionId = desired.id))
    }

    @Test
    fun deletedPersistedTargetDoesNotSelectAnotherHost() {
        assertNull(connections.desiredConnectionOrNull(desiredConnectionId = "missing"))
    }

    private fun connection(id: String, lastUsedAt: Long) = SavedConnection(
        id = id,
        name = id,
        host = "$id.example",
        username = "user",
        authType = AuthType.PASSWORD,
        lastUsedAt = lastUsedAt,
    )
}
