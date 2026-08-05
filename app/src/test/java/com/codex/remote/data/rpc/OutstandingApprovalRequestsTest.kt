package com.codex.remote.data.rpc

import com.codex.remote.domain.RpcRequestId
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OutstandingApprovalRequestsTest {
    @Test
    fun boundedLineReaderAcceptsTheLimitAndNormalizesCrLf() {
        val reader = BufferedReader(StringReader("abcd\r\nnext"))

        assertEquals("abcd", reader.readBoundedLine(4))
        assertEquals("next", reader.readBoundedLine(4))
        assertEquals(null, reader.readBoundedLine(4))
    }

    @Test
    fun boundedLineReaderRejectsContentPastTheLimit() {
        val reader = BufferedReader(StringReader("abcde\n"))

        val error = runCatching { reader.readBoundedLine(4) }.exceptionOrNull()

        assertTrue(error is AppServerLineTooLongException)
    }

    @Test
    fun duplicateArrivalInvalidatesTheConnectionBeforeEitherRequestCanBeAnswered() = runTest {
        val requests = OutstandingApprovalRequests()
        val id = RpcRequestId.Text("approval-1")

        assertTrue(requests.reserve(id))
        assertFalse(requests.reserve(id))

        var responseSent = false
        val error = runCatching {
            requests.respondAndTrackUntilResolved(id) { responseSent = true }
        }.exceptionOrNull()

        assertTrue(error is RpcException)
        assertFalse(responseSent)
    }

    @Test
    fun duplicateArrivalDuringResponseFlushImmediatelyInvalidatesTheConnection() = runTest {
        val requests = OutstandingApprovalRequests()
        val id = RpcRequestId.Number(7)
        val sendStarted = CompletableDeferred<Unit>()
        val allowFlushToComplete = CompletableDeferred<Unit>()
        assertTrue(requests.reserve(id))

        val response = async {
            runCatching {
                requests.respondAndTrackUntilResolved(id) {
                    sendStarted.complete(Unit)
                    allowFlushToComplete.await()
                }
            }.exceptionOrNull()
        }
        sendStarted.await()
        val reuse = async { requests.reserve(id) }
        yield()

        assertTrue(reuse.isCompleted)
        assertFalse(reuse.await())
        allowFlushToComplete.complete(Unit)
        assertTrue(response.await() is RpcException)
        assertFalse(requests.reserve(RpcRequestId.Number(8)))
    }

    @Test
    fun sameIdCanBeReusedOnlyAfterTheServerResolutionArrives() = runTest {
        val requests = OutstandingApprovalRequests()
        val id = RpcRequestId.Number(7)
        assertTrue(requests.reserve(id))

        requests.respondAndTrackUntilResolved(id) {}
        assertTrue(requests.resolve(id))

        assertTrue(requests.reserve(id))
    }

    @Test
    fun retainedRequestIdsCannotExceedTheAggregateTrackingBudget() {
        val requests = OutstandingApprovalRequests()
        val halfBudget = (MAX_TRACKED_APPROVAL_ID_CHARS / 2L).toInt()

        assertTrue(requests.reserve(RpcRequestId.Text("a".repeat(halfBudget))))
        assertFalse(requests.reserve(RpcRequestId.Text("b".repeat(halfBudget + 1))))
        assertFalse(requests.reserve(RpcRequestId.Text("later")))
    }

    @Test
    fun retainedApprovalPayloadsCannotExceedThePreQueueTrackingBudget() {
        val requests = OutstandingApprovalRequests()
        val halfBudget = MAX_TRACKED_APPROVAL_RETAINED_CHARS / 2L

        assertTrue(requests.reserve(RpcRequestId.Text("first"), "thread-1", halfBudget))
        assertFalse(requests.reserve(RpcRequestId.Text("second"), "thread-2", halfBudget + 1L))
        assertFalse(requests.reserve(RpcRequestId.Text("later"), "thread-3", 1L))
    }

    @Test
    fun serverResolutionDuringResponseFlushReleasesTheIdAfterDeliveryCompletes() = runTest {
        val requests = OutstandingApprovalRequests()
        val id = RpcRequestId.Number(9)
        val sendStarted = CompletableDeferred<Unit>()
        val allowFlushToComplete = CompletableDeferred<Unit>()
        assertTrue(requests.reserve(id))

        val response = async {
            runCatching {
                requests.respondAndTrackUntilResolved(id) {
                    sendStarted.complete(Unit)
                    allowFlushToComplete.await()
                }
            }.exceptionOrNull()
        }
        sendStarted.await()
        assertTrue(requests.resolve(id))
        allowFlushToComplete.complete(Unit)

        assertEquals(null, response.await())
        assertTrue(requests.reserve(id))
    }

    @Test
    fun failedResponseInvalidatesTheConnectionAndCannotBeRetried() = runTest {
        val requests = OutstandingApprovalRequests()
        val id = RpcRequestId.Text("approval-failed")
        assertTrue(requests.reserve(id))

        try {
            requests.respondAndTrackUntilResolved(id) { throw IOException("partial flush") }
            fail("Expected the transport failure")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("partial flush"))
        }

        var retrySent = false
        val retryError = runCatching {
            requests.respondAndTrackUntilResolved(id) { retrySent = true }
        }.exceptionOrNull()
        assertTrue(retryError is RpcException)
        assertFalse(retrySent)
        assertFalse(requests.reserve(RpcRequestId.Text("approval-later")))
    }

    @Test
    fun unknownServerRequestInvalidatesTheTrackerAndBecomesAFatalProtocolError() = runTest {
        val requests = OutstandingApprovalRequests()
        val id = RpcRequestId.Text("unknown-request")

        val event = trackedServerRequestEvent(
            requests = requests,
            id = id,
            method = "future/privilegedOperation",
            params = buildJsonObject {},
        )

        assertTrue(event is AppServerEvent.FatalProtocolError)
        assertFalse(requests.reserve(RpcRequestId.Text("approval-later")))
    }

    @Test
    fun compositeApprovalSizeAcceptsTheExactLimitAndRejectsOneMoreCharacter() {
        val id = RpcRequestId.Text("a")
        val method = "item/commandExecution/requestApproval"
        fun paramsForTotal(totalChars: Long) = buildJsonObject {
            val jsonEnvelopeChars = "{\"reason\":\"\"}".length
            val contentChars = totalChars - id.displayValue.length - method.length - jsonEnvelopeChars
            put("reason", "x".repeat(contentChars.toInt()))
        }

        val exactParams = paramsForTotal(MAX_APPROVAL_MESSAGE_CHARS)
        assertEquals(
            MAX_APPROVAL_MESSAGE_CHARS,
            id.displayValue.length.toLong() + method.length + exactParams.toString().length,
        )
        assertTrue(
            trackedServerRequestEvent(OutstandingApprovalRequests(), id, method, exactParams) is AppServerEvent.Approval,
        )

        val oversizedRequests = OutstandingApprovalRequests()
        val oversizedParams = paramsForTotal(MAX_APPROVAL_MESSAGE_CHARS + 1)
        assertTrue(
            trackedServerRequestEvent(oversizedRequests, id, method, oversizedParams) is
                AppServerEvent.FatalProtocolError,
        )
        assertFalse(oversizedRequests.reserve(RpcRequestId.Text("approval-later")))
    }

    @Test
    fun trackedResolutionReleasesTheRequestAndRejectsUnexpectedIds() {
        val requests = OutstandingApprovalRequests()
        val id = RpcRequestId.Text("approval-resolved")
        assertTrue(requests.reserve(id, "thread-1"))

        val event = trackedServerRequestResolvedEvent(
            requests,
            buildJsonObject {
                put("threadId", "thread-1")
                put("requestId", "approval-resolved")
            },
        )

        assertEquals(AppServerEvent.ApprovalResolved("thread-1", id), event)
        assertTrue(requests.reserve(id))

        val unexpected = trackedServerRequestResolvedEvent(
            OutstandingApprovalRequests(),
            buildJsonObject {
                put("threadId", "thread-1")
                put("requestId", "missing")
            },
        )
        assertTrue(unexpected is AppServerEvent.FatalProtocolError)
    }

    @Test
    fun resolutionThreadMustMatchTheOriginalApprovalThread() {
        val requests = OutstandingApprovalRequests()
        val id = RpcRequestId.Text("approval-thread-bound")
        assertTrue(requests.reserve(id, "thread-expected"))

        val event = trackedServerRequestResolvedEvent(
            requests,
            buildJsonObject {
                put("threadId", "thread-other")
                put("requestId", "approval-thread-bound")
            },
        )

        assertTrue(event is AppServerEvent.FatalProtocolError)
        assertFalse(requests.reserve(RpcRequestId.Text("later")))
    }

    @Test
    fun oversizedTextRequestIdInvalidatesTheTrackerBeforeReservation() {
        val requests = OutstandingApprovalRequests()
        val event = trackedServerRequestEvent(
            requests = requests,
            id = RpcRequestId.Text("x".repeat(MAX_APPROVAL_MESSAGE_CHARS.toInt())),
            method = "item/fileChange/requestApproval",
            params = buildJsonObject {},
        )

        assertTrue(event is AppServerEvent.FatalProtocolError)
        assertFalse(requests.reserve(RpcRequestId.Text("approval-later")))
    }

    @Test
    fun presentButNonStringOrIntegerRequestIdIsAProtocolError() {
        try {
            CodexRpcClient.requireRequestId(JsonPrimitive(1.5))
            fail("Expected an invalid JSON-RPC request ID error")
        } catch (error: RpcException) {
            assertTrue(error.message!!.contains("request id", ignoreCase = true))
        }
    }
}
