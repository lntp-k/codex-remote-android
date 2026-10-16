package com.codex.remote

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.codex.remote.data.rpc.CodexRpcClient
import com.codex.remote.data.ssh.ActiveSshTransport
import com.codex.remote.data.transport.AppServerMessageChannel
import com.codex.remote.domain.*
import com.codex.remote.session.SessionRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the actual private refreshThreads coroutine; no copied reconciliation code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@OptIn(ExperimentalCoroutinesApi::class)
class NewThreadRefreshRaceTest {
    @Test fun staleListCompletingAfterNewSelectionMustPreserveSelectedConversation() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        // Test only: no credentials are used; satisfy the eager Android keystore creation.
        java.security.Security.addProvider(object : java.security.Provider("QaKeyStore", 1.0, "Synthetic test keystore") {
            init { put("KeyStore.AndroidKeyStore", "sun.security.pkcs12.PKCS12KeyStore") }
        })
        val vm = AppViewModel(RuntimeEnvironment.getApplication())
        val sent = CountDownLatch(1)
        val transport = allocate(ActiveSshTransport::class.java) as ActiveSshTransport
        field(transport, "messageChannel").set(transport, object : AppServerMessageChannel {
            override fun readMessage(maxChars: Int): String? = null
            override fun writeMessage(message: String) {
                assertTrue(message.contains("thread/list"))
                sent.countDown()
            }
            override fun close() {}
        })
        val client = CodexRpcClient(transport)
        try {
            field(vm, "rpc").set(vm, client)
            @Suppress("UNCHECKED_CAST")
            val state = field(vm, "_state").get(vm) as MutableStateFlow<AppUiState>
            val connection = SavedConnection(name = "synthetic", host = "127.0.0.1", username = "qa", authType = AuthType.PASSWORD)
            val oldThread = RemoteThread(id = "old", title = "Old", cwd = "/workspace", updatedAt = 1L, status = "idle")
            state.value = AppUiState(activeConnection = connection, selectedProjectPath = "/workspace", threads = listOf(oldThread))
            AppViewModel::class.java.getDeclaredMethod("refreshThreads").apply { isAccessible = true }.invoke(vm)
            runCurrent()
            assertTrue("thread/list must be in flight", sent.await(5, TimeUnit.SECONDS))
            // A real thread/start response selects a new session while that list is pending.
            val item = TimelineItem(id = "local-new", kind = TimelineKind.USER, body = "new prompt")
            var registry = SessionRegistry().registerThread(RemoteThread(id = "new", title = "New", cwd = "/workspace", updatedAt = 2L, status = "active")).registry
            registry = registry.updateSession("new", markUnread = false) { it.copy(timeline = listOf(item), isTurnRunning = true, activeTurnId = "turn-new") }.registry
            registry = registry.selectThread("new").registry
            field(vm, "sessionRegistry").set(vm, registry)
            state.value = state.value.copy(selectedThreadId = "new", timeline = listOf(item), isTurnRunning = true, activeTurnId = "turn-new")
            @Suppress("UNCHECKED_CAST")
            val pending = field(client, "pending").get(client) as Map<RpcRequestId, CompletableDeferred<JsonObject>>
            assertEquals("exactly one suspended list request", 1, pending.size)
            pending.values.single().complete(Json.parseToJsonElement("""{"data":[{"id":"old","preview":"Old","cwd":"/workspace","updatedAt":1}],"nextCursor":null}""").jsonObject)
            advanceUntilIdle()
            assertEquals("stale list must not deselect the newly created task", "new", state.value.selectedThreadId)
            assertEquals("stale list must retain the new prompt", listOf(item), state.value.timeline)
            assertEquals("/workspace", state.value.selectedProjectPath)
            assertEquals(setOf("old", "new"), state.value.threads.map { it.id }.toSet())
            assertTrue(state.value.isTurnRunning)
            // A later explicit removal is authoritative even when lists can be stale.
            AppViewModel::class.java.getDeclaredMethod("removeRemoteThread", String::class.java)
                .apply { isAccessible = true }.invoke(vm, "new")
            assertNull(state.value.selectedThreadId)
            assertTrue(state.value.timeline.isEmpty())
            assertFalse(state.value.isTurnRunning)
            assertNull(state.value.activeTurnId)
        } finally {
            vm.viewModelScope.cancel()
            (field(client, "scope").get(client) as CoroutineScope).cancel()
            Dispatchers.resetMain()
            java.security.Security.removeProvider("QaKeyStore")
        }
    }
    private fun field(value: Any, name: String) = value.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private fun allocate(type: Class<*>): Any {
        val cls = Class.forName("sun.misc.Unsafe")
        val singleton = cls.getDeclaredField("theUnsafe").let { it.isAccessible = true; it.get(null) }
        return cls.getMethod("allocateInstance", Class::class.java).invoke(singleton, type)
    }
}
