package com.codex.remote

import com.codex.remote.domain.ApprovalFileItemKey
import com.codex.remote.domain.FileChangeSummary
import com.codex.remote.domain.TimelineItem
import com.codex.remote.domain.TimelineKind
import com.codex.remote.domain.approvalFileSnapshotRetainedCharCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalFileCacheTest {
    @Test
    fun cacheStoresOnlyTheMinimalFullyReviewableSnapshot() {
        val item = fileItem("patch-1", "turn-1").copy(
            title = "unneeded title",
            body = "unneeded body",
            expanded = true,
            isGoal = true,
        )

        val cached = emptyMap<ApprovalFileItemKey, TimelineItem>()
            .recordFileApprovalItem("thread-1", item)
            .values
            .single()

        assertEquals("", cached.title)
        assertEquals("", cached.body)
        assertFalse(cached.expanded)
        assertFalse(cached.isGoal)
        assertEquals(item.fileChanges, cached.fileChanges)
        assertTrue(cached.approvalFileSnapshotRetainedCharCount <= MAX_CACHED_APPROVAL_FILE_CHARS)
    }

    @Test
    fun unsafeReplacementEvictsTheEarlierSafeSnapshotForTheSameIdentity() {
        val safe = fileItem("patch-1", "turn-1")
        val key = ApprovalFileItemKey("thread-1", "turn-1", "patch-1")
        val cached = emptyMap<ApprovalFileItemKey, TimelineItem>()
            .recordFileApprovalItem("thread-1", safe)
        val oversized = safe.copy(
            fileChanges = listOf(
                FileChangeSummary(
                    path = "sensitive.kt",
                    kind = "update",
                    diff = "x".repeat(com.codex.remote.domain.FILE_CHANGE_PREVIEW_MAX_CHARS + 1),
                ),
            ),
        )

        val replaced = cached.recordFileApprovalItem("thread-1", oversized)

        assertFalse(key in replaced)
        assertTrue(replaced.isEmpty())
    }

    @Test
    fun cacheEnforcesBothAggregateContentAndEntryCountBounds() {
        val largeKind = "k".repeat((MAX_CACHED_APPROVAL_FILE_CHARS / 2L + 1_024L).toInt())
        val first = fileItem("patch-large-1", "turn-large-1", kind = largeKind)
        val second = fileItem("patch-large-2", "turn-large-2", kind = largeKind)
        var cached = emptyMap<ApprovalFileItemKey, TimelineItem>()
            .recordFileApprovalItem("thread-1", first)
            .recordFileApprovalItem("thread-1", second)

        assertFalse(ApprovalFileItemKey("thread-1", "turn-large-1", "patch-large-1") in cached)
        assertTrue(ApprovalFileItemKey("thread-1", "turn-large-2", "patch-large-2") in cached)
        assertTrue(cached.values.sumOf { it.approvalFileSnapshotRetainedCharCount } <= MAX_CACHED_APPROVAL_FILE_CHARS)

        repeat(MAX_CACHED_APPROVAL_FILE_ITEMS + 1) { index ->
            cached = cached.recordFileApprovalItem(
                "thread-1",
                fileItem("patch-$index", "turn-$index"),
            )
        }

        assertEquals(MAX_CACHED_APPROVAL_FILE_ITEMS, cached.size)
        assertFalse(ApprovalFileItemKey("thread-1", "turn-large-2", "patch-large-2") in cached)
    }

    private fun fileItem(
        id: String,
        turnId: String,
        kind: String = "update",
    ) = TimelineItem(
        id = id,
        kind = TimelineKind.FILE_CHANGE,
        status = "inProgress",
        turnId = turnId,
        fileChanges = listOf(FileChangeSummary("safe.kt", kind, "+safe")),
    )
}
