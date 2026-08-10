package com.codex.remote.connection

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.codex.remote.CodexRemoteApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CodexRemoteApplication::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SshConnectionServiceTest {
    private var controller: ServiceController<SshConnectionService>? = null

    @After
    fun tearDown() {
        controller?.destroy()
    }

    @Test
    fun serviceImmediatelyEntersForegroundWithVisibleDisconnectAction() {
        val service = Robolectric.buildService(SshConnectionService::class.java)
            .also { controller = it }
            .create()
            .get()

        val shadowService = shadowOf(service)
        val notification = shadowService.lastForegroundNotification

        assertEquals(1_017, shadowService.lastForegroundNotificationId)
        assertTrue(shadowService.isLastForegroundNotificationAttached)
        assertNotNull(notification)
        assertNotEquals(0, notification.smallIcon.resId)
        assertEquals(1, notification.actions.size)
        assertEquals("Disconnect", notification.actions.single().title.toString())

        val notificationManager = service.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel("ssh_connection")
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun manifestDeclaresSpecialUseAndKeepsServiceWhenTaskIsRemoved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, SshConnectionService::class.java),
            PackageManager.GET_META_DATA,
        )

        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, serviceInfo.foregroundServiceType)
        assertFalse(serviceInfo.flags and ServiceInfo.FLAG_STOP_WITH_TASK != 0)
    }
}
