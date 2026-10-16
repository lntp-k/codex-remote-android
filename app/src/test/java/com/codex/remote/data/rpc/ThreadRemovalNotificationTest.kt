package com.codex.remote.data.rpc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadRemovalNotificationTest {
    @Test
    fun archiveAndDeleteRetainTheExactAuthoritativeIdentity() {
        for (method in listOf("thread/archived", "thread/deleted")) {
            assertEquals(
                AppServerEvent.ThreadRemoved("remote-thread-1"),
                parse(method, """{"threadId":"remote-thread-1"}"""),
            )
        }
    }

    @Test
    fun unloadingAndMetadataChangesAreNotRemoval() {
        for (method in listOf("thread/closed", "thread/status/changed", "thread/name/updated", "thread/unarchived")) {
            assertEquals(
                AppServerEvent.ThreadsChanged,
                parse(method, """{"threadId":"remote-thread-1"}"""),
            )
        }
    }

    @Test
    fun malformedRemovalIdentityFallsBackToRefreshWithoutTargetingAnotherTask() {
        for (method in listOf("thread/archived", "thread/deleted")) {
            for (params in listOf(
                "{}", """{"threadId":null}""", """{"threadId":7}""",
                """{"threadId":true}""", """{"threadId":[]}""", """{"threadId":{}}""",
                """{"threadId":""}""", """{"threadId":" "}""",
                """{"id":"remote-thread-1"}""", """{"thread":{"id":"remote-thread-1"}}""",
            )) {
                assertEquals("$method $params", AppServerEvent.ThreadsChanged, parse(method, params))
            }
        }
    }

    private fun parse(method: String, params: String) =
        CodexRpcClient.threadListChangeEvent(method, Json.parseToJsonElement(params).jsonObject)
}
