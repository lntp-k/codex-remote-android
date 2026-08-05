package com.codex.remote.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalQueueTest {
    @Test
    fun concurrentApprovalsStayQueuedAndCompletingOneKeepsTheOther() {
        val first = approval("request-1", "git status")
        val second = approval("request-2", "./gradlew test")

        val queued = ApprovalQueue().enqueue(first).enqueue(second)

        assertEquals(listOf("request-1", "request-2"), queued.requests.map { it.requestId.displayValue })
        assertEquals("request-1", queued.current?.requestId?.displayValue)

        val firstKey = queued.currentEntry!!.key
        val secondKey = queued.entries[1].key
        val responding = queued.markResponding(firstKey)
        assertNull(responding.requestForResponse(firstKey))
        assertEquals("request-2", responding.requestForResponse(secondKey)?.requestId?.displayValue)

        val completed = responding
            .enqueue(approval("request-3", "git diff"))
            .complete(firstKey)
        assertEquals(listOf("request-2", "request-3"), completed.requests.map { it.requestId.displayValue })
        assertEquals("request-2", completed.current?.requestId?.displayValue)
        assertFalse(firstKey in completed.respondingKeys)
    }

    @Test
    fun serverResolutionCompletesOnlyTheMatchingWireRequestId() {
        val queued = ApprovalQueue()
            .enqueue(approval("request-1", "git status"))
            .enqueue(approval("request-2", "./gradlew test"))
        val firstKey = queued.currentEntry!!.key
        val responding = queued.markResponding(firstKey)

        val resolved = responding.complete(RpcRequestId.Text("request-1"))

        assertEquals(listOf("request-2"), resolved.requests.map { it.requestId.displayValue })
        assertFalse(firstKey in resolved.respondingKeys)
        assertSame(resolved, resolved.complete(RpcRequestId.Text("missing")))
    }

    @Test
    fun staleOrDuplicateUiCallbacksNeverFallBackToTheCurrentRequest() {
        val beforeCompletion = ApprovalQueue()
            .enqueue(approval("request-1", "git status"))
            .enqueue(approval("request-2", "rm -rf build"))
        val staleKey = beforeCompletion.currentEntry!!.key
        val currentKey = beforeCompletion.entries[1].key
        val queued = beforeCompletion.complete(staleKey)

        assertNull(queued.requestForResponse(staleKey))
        assertEquals(
            "request-2",
            queued.requestForResponse(currentKey)?.requestId?.displayValue,
        )

        val unchanged = queued.markResponding(staleKey)
        assertSame(queued, unchanged)
        assertEquals("request-2", unchanged.current?.requestId?.displayValue)
    }

    @Test
    fun duplicateRequestIdCannotReplaceTheOriginalAuthorizationContext() {
        val original = approval("request-1", "git status")
        val replaced = original.copy(detail = "rm -rf /srv/app")

        val first = ApprovalQueue().enqueue(original)
        val result = first.enqueueResult(replaced)
        val queued = result.queue

        assertEquals(ApprovalEnqueueStatus.DUPLICATE_ACTIVE_ID, result.status)
        assertEquals(1, queued.requests.size)
        assertEquals("git status", queued.current?.detail)
    }

    @Test
    fun respondingRequestCannotBeClaimedAgainOnTheSameConnection() {
        val request = approval("request-1", "git status")
        val queued = ApprovalQueue().enqueue(request)
        val key = queued.currentEntry!!.key
        val responding = queued.markResponding(key)

        assertEquals(request, responding.current)
        assertNull(responding.requestForResponse(key))
        assertTrue(key in responding.respondingKeys)
    }

    @Test
    fun reusedWireIdGetsADistinctEntryOnlyAfterTheFirstEntryCompletes() {
        val first = approval("request-reused", "git status")
        val second = approval("request-reused", "./gradlew test")
        val firstQueued = ApprovalQueue().enqueue(first)
        val firstKey = firstQueued.currentEntry!!.key

        val responding = firstQueued.markResponding(firstKey)
        val prematureReuse = responding.enqueueResult(second)

        assertEquals(ApprovalEnqueueStatus.DUPLICATE_ACTIVE_ID, prematureReuse.status)
        assertSame(responding, prematureReuse.queue)

        val reused = responding.complete(firstKey).enqueue(second)
        val secondKey = reused.currentEntry!!.key
        assertNotEquals(firstKey, secondKey)
        assertEquals(first.requestId, second.requestId)
        assertEquals(second, reused.current)
        assertNull(reused.requestForResponse(firstKey))
        assertEquals(second, reused.requestForResponse(secondKey))
    }

    @Test
    fun staleKeyFromAnEarlierConnectionCannotClaimTheSameWireId() {
        val oldQueue = ApprovalQueue().enqueue(approval("request-1", "old command"))
        val newQueue = ApprovalQueue().enqueue(approval("request-1", "new command"))
        val oldKey = oldQueue.currentEntry!!.key
        val newKey = newQueue.currentEntry!!.key

        assertNotEquals(oldKey, newKey)
        assertSame(newQueue, newQueue.markResponding(oldKey))
        assertEquals("new command", newQueue.current?.detail)
    }

    @Test
    fun queueRejectsTheFirstRequestBeyondItsHardLimit() {
        var queue = ApprovalQueue()
        repeat(ApprovalQueue.MAX_PENDING_REQUESTS) { index ->
            queue = queue.enqueue(approval("request-$index", "command-$index"))
        }

        val result = queue.enqueueResult(approval("overflow", "overflow-command"))

        assertEquals(ApprovalEnqueueStatus.CAPACITY_EXCEEDED, result.status)
        assertSame(queue, result.queue)
        assertEquals(ApprovalQueue.MAX_PENDING_REQUESTS, result.queue.entries.size)
    }

    @Test
    fun queueRejectsAggregateRetainedContentAndCompletionReclaimsTheBudget() {
        val payload = "x".repeat((ApprovalQueue.MAX_RETAINED_CHARS / 2L).toInt())
        val first = approval("large-1", payload)
        val second = approval("large-2", payload)

        val firstResult = ApprovalQueue().enqueueResult(first)
        val overflow = firstResult.queue.enqueueResult(second)

        assertEquals(ApprovalEnqueueStatus.ENQUEUED, firstResult.status)
        assertEquals(ApprovalEnqueueStatus.CAPACITY_EXCEEDED, overflow.status)
        assertSame(firstResult.queue, overflow.queue)

        val reclaimed = firstResult.queue.complete(firstResult.queue.currentEntry!!.key)
        val retried = reclaimed.enqueueResult(second)
        assertEquals(ApprovalEnqueueStatus.ENQUEUED, retried.status)
        assertEquals(second, retried.queue.current)
    }

    @Test
    fun fileSnapshotBindingCannotGrowTheQueuePastItsRetainedContentBudget() {
        val pending = fileApproval()
        val paddingChars = (
            pending.rawParams.length.toLong() + ApprovalQueue.MAX_RETAINED_CHARS -
                pending.retainedCharCount - 1_024L
            ).toInt()
        val padded = pending.copy(rawParams = "x".repeat(paddingChars))
        assertTrue(padded.retainedCharCount < ApprovalQueue.MAX_RETAINED_CHARS)
        val queue = ApprovalQueue().enqueue(padded)
        val safeButTooLargeForRemainingBudget = TimelineItem(
            id = "patch-7",
            kind = TimelineKind.FILE_CHANGE,
            status = "inProgress",
            turnId = "turn-a",
            fileChanges = listOf(
                FileChangeSummary("app/src/Main.kt", "update", "+" + "x".repeat(2_048)),
            ),
        )

        val bound = queue.bindFileChangeSnapshot("thread-a", safeButTooLargeForRemainingBudget)

        assertSame(queue, bound)
        assertTrue(bound.current!!.fileChanges.isEmpty())
        assertFalse(bound.current!!.canApprove(emptyList()))
    }

    @Test
    fun oversizedReplacementMakesAnAlreadyBoundFileApprovalDenyOnly() {
        val request = fileApproval()
        val safe = TimelineItem(
            id = "patch-7",
            kind = TimelineKind.FILE_CHANGE,
            status = "inProgress",
            turnId = "turn-a",
            fileChanges = listOf(FileChangeSummary("safe.kt", "update", "+safe")),
        )
        val bound = request.bindFileChangesSnapshot("thread-a", safe)
        val oversized = safe.copy(
            fileChanges = listOf(
                FileChangeSummary(
                    "sensitive.kt",
                    "update",
                    "x".repeat(FILE_CHANGE_PREVIEW_MAX_CHARS + 1),
                ),
            ),
        )

        val replaced = bound.bindFileChangesSnapshot("thread-a", oversized)

        assertFalse(replaced.canApprove(emptyList()))
        assertEquals(listOf("decline"), replaced.availableDecisions)
        assertEquals(listOf("safe.kt"), replaced.fileChanges.map { it.path })
    }

    @Test
    fun fileApprovalIsBlockedUntilItsExactTimelineItemProvidesTargets() {
        val request = ApprovalRequest(
            requestId = RpcRequestId.Text("request-file"),
            kind = ApprovalKind.FILE_CHANGE,
            title = "Allow file changes?",
            detail = "Apply patch",
            rawMethod = "item/fileChange/requestApproval",
            threadId = "thread-a",
            turnId = "turn-a",
            itemId = "patch-7",
        )
        val otherItem = TimelineItem(
            id = "patch-8",
            kind = TimelineKind.FILE_CHANGE,
            turnId = "turn-a",
            fileChanges = listOf(FileChangeSummary("wrong.kt", "update", "+wrong")),
        )
        val wrongTurn = TimelineItem(
            id = "patch-7",
            kind = TimelineKind.FILE_CHANGE,
            turnId = "turn-b",
            fileChanges = listOf(FileChangeSummary("stale.kt", "update", "+stale")),
        )
        val matchingItem = TimelineItem(
            id = "patch-7",
            kind = TimelineKind.FILE_CHANGE,
            turnId = "turn-a",
            status = "inProgress",
            fileChanges = listOf(FileChangeSummary("app/src/Main.kt", "update", "-old\n+new")),
        )
        val incompleteItem = matchingItem.copy(fileChangesComplete = false)

        val unbound = request.bindFileChangesSnapshot(listOf(otherItem, wrongTurn), "thread-a")
        assertTrue(unbound.resolvedFileChanges(listOf(matchingItem), "thread-a").isEmpty())
        assertFalse(unbound.canApprove(listOf(matchingItem), "thread-a"))

        assertFalse(request.bindFileChangesSnapshot(listOf(incompleteItem), "thread-a").canApprove(emptyList()))
        val bound = request.bindFileChangesSnapshot(listOf(otherItem, wrongTurn, matchingItem), "thread-a")
        assertEquals(
            listOf("app/src/Main.kt"),
            bound.resolvedFileChanges(emptyList(), "thread-a").map { it.path },
        )
        assertTrue(bound.canApprove(emptyList(), "thread-a"))
        assertFalse(request.bindFileChangesSnapshot(listOf(matchingItem), selectedThreadId = "thread-b").canApprove(emptyList()))
    }

    @Test
    fun fileApprovalUsesTheImmutableSnapshotShownToTheUser() {
        val request = ApprovalRequest(
            requestId = RpcRequestId.Text("request-file"),
            kind = ApprovalKind.FILE_CHANGE,
            title = "Allow file changes?",
            detail = "Apply patch",
            rawMethod = "item/fileChange/requestApproval",
            threadId = "thread-a",
            turnId = "turn-a",
            itemId = "patch-7",
        )
        val benign = TimelineItem(
            id = "patch-7",
            kind = TimelineKind.FILE_CHANGE,
            turnId = "turn-a",
            status = "inProgress",
            fileChanges = listOf(FileChangeSummary("safe.kt", "update", "+safe")),
        )
        val changedAfterDisplay = benign.copy(
            fileChanges = listOf(FileChangeSummary("sensitive.kt", "delete", "secret")),
        )

        val bound = request.bindFileChangesSnapshot(listOf(benign), "thread-a")

        assertEquals(listOf("safe.kt"), bound.resolvedFileChanges(listOf(changedAfterDisplay), "thread-a").map { it.path })
        assertTrue(bound.canApprove(listOf(changedAfterDisplay), "thread-a"))

        val pendingQueue = ApprovalQueue().enqueue(request.bindFileChangesSnapshot(emptyList(), "thread-a"))
        assertFalse(pendingQueue.current!!.canApprove(emptyList()))
        val queueBoundOnFirstCompleteItem = pendingQueue.bindFileChangeSnapshots(listOf(benign), "thread-a")
        val queueAfterLaterReplacement = queueBoundOnFirstCompleteItem.bindFileChangeSnapshots(
            listOf(changedAfterDisplay),
            "thread-a",
        )
        assertEquals(
            listOf("safe.kt"),
            queueAfterLaterReplacement.current!!.fileChanges.map { it.path },
        )
        assertFalse(queueAfterLaterReplacement.current!!.canApprove(emptyList()))
        assertEquals(listOf("decline"), queueAfterLaterReplacement.current!!.availableDecisions)

        val malformedReplacement = benign.copy(fileChangesComplete = false)
        val queueAfterMalformedReplacement = queueBoundOnFirstCompleteItem.bindFileChangeSnapshots(
            listOf(malformedReplacement),
            "thread-a",
        )
        assertFalse(queueAfterMalformedReplacement.current!!.canApprove(emptyList()))
        assertEquals(listOf("decline"), queueAfterMalformedReplacement.current!!.availableDecisions)

        val backgroundQueue = pendingQueue.bindFileChangeSnapshot("thread-a", benign)
        assertTrue(backgroundQueue.current!!.canApprove(emptyList()))
        assertEquals(listOf("safe.kt"), backgroundQueue.current!!.fileChanges.map { it.path })
    }

    private fun approval(requestId: String, detail: String) = ApprovalRequest(
        requestId = RpcRequestId.Text(requestId),
        kind = ApprovalKind.COMMAND,
        title = "Allow command?",
        detail = detail,
        rawMethod = "item/commandExecution/requestApproval",
    )

    private fun fileApproval() = ApprovalRequest(
        requestId = RpcRequestId.Text("request-file"),
        kind = ApprovalKind.FILE_CHANGE,
        title = "Allow file changes?",
        detail = "Apply patch",
        rawMethod = "item/fileChange/requestApproval",
        threadId = "thread-a",
        turnId = "turn-a",
        itemId = "patch-7",
    )
}
