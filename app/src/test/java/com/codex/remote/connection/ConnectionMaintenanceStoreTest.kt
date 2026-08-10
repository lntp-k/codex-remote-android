package com.codex.remote.connection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ConnectionMaintenanceStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = ConnectionMaintenanceStore(context)

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun desiredConnectionSurvivesAStoreRecreationUntilExplicitlyCleared() {
        assertTrue(store.remember("connection-b"))

        assertEquals("connection-b", ConnectionMaintenanceStore(context).desiredConnectionId())
        assertTrue(ConnectionMaintenanceStore(context).clear())
        assertNull(store.desiredConnectionId())
    }

    @Test
    fun blankConnectionIdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            store.remember("  ")
        }
    }
}
