package com.codex.remote.ui.screens

import com.codex.remote.domain.FileChangeSummary
import com.codex.remote.domain.ApprovalKind
import com.codex.remote.domain.ApprovalRequest
import com.codex.remote.domain.RpcRequestId
import com.codex.remote.domain.aggregateFileChangePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalDiffPreviewTest {
    @Test
    fun aggregatePreviewPreservesSmallChangesExactly() {
        val changes = listOf(
            FileChangeSummary("app/A.kt", "update", "+one\n-two", movePath = "app/C.kt"),
            FileChangeSummary("app/B.kt", "update", "context"),
        )

        val preview = aggregateFileChangePreview(changes)

        assertEquals(listOf("+one\n-two", "context"), preview.files.map { it.diff })
        assertEquals(listOf("app/A.kt", "app/B.kt"), preview.files.map { it.path })
        assertEquals("app/C.kt", preview.files.first().movePath)
        assertEquals(3, preview.renderedLineCount)
        assertEquals(16, preview.renderedCharCount)
        assertEquals(24, preview.renderedTargetCharCount)
        assertEquals(0, preview.hiddenTargetCount)
        assertFalse(preview.diffTruncated)
        assertFalse(preview.targetsTruncated)
        assertTrue(preview.fullyReviewable)
        assertTrue(fileApproval(changes).canApprove(emptyList()))
    }

    @Test
    fun aggregatePreviewSharesOneLineBudgetAcrossAllFiles() {
        val changes = listOf(
            FileChangeSummary(
                "app/First.kt",
                "update",
                (1..150).joinToString("\n") { "+first-$it" },
            ),
            FileChangeSummary(
                "app/Second.kt",
                "update",
                (1..100).joinToString("\n") { "+second-$it" },
            ),
        )

        val preview = aggregateFileChangePreview(changes)

        assertEquals(200, preview.renderedLineCount)
        assertEquals(150, preview.files[0].diff.lineSequence().count())
        assertEquals(50, preview.files[1].diff.lineSequence().count())
        assertTrue(preview.files[1].diff.contains("+second-50"))
        assertFalse(preview.files[1].diff.contains("+second-51"))
        assertTrue(preview.diffTruncated)
        assertFalse(preview.targetsTruncated)
        assertFalse(preview.fullyReviewable)
    }

    @Test
    fun aggregatePreviewCountsCarriageReturnLineBreaksTowardTheSameLimit() {
        val changes = listOf(
            FileChangeSummary(
                "app/CarriageReturns.kt",
                "update",
                (1..250).joinToString("\r") { "+line-$it" },
            ),
        )

        val preview = aggregateFileChangePreview(changes)

        assertEquals(200, preview.renderedLineCount)
        assertEquals(200, preview.files.single().diff.lineSequence().count())
        assertTrue(preview.diffTruncated)
        assertFalse(preview.fullyReviewable)
    }

    @Test
    fun aggregatePreviewSharesOneCharacterBudgetAcrossAllFiles() {
        val changes = listOf(
            FileChangeSummary("app/First.kt", "update", "a".repeat(32_760)),
            FileChangeSummary("app/Second.kt", "update", "b".repeat(20)),
        )

        val preview = aggregateFileChangePreview(changes)

        assertEquals(32_768, preview.renderedCharCount)
        assertEquals(32_760, preview.files[0].diff.length)
        assertEquals(8, preview.files[1].diff.length)
        assertTrue(preview.files[1].diffTruncated)
        assertTrue(preview.diffTruncated)
        assertFalse(preview.fullyReviewable)
    }

    @Test
    fun aggregatePreviewNeverSplitsASurrogatePairAtTheCharacterLimit() {
        val changes = listOf(
            FileChangeSummary(
                "app/Unicode.kt",
                "update",
                "a".repeat(32_767) + "😀tail",
            ),
        )

        val preview = aggregateFileChangePreview(changes)

        assertEquals(32_767, preview.renderedCharCount)
        assertFalse(preview.files.single().diff.last().isHighSurrogate())
        assertTrue(preview.diffTruncated)
        assertFalse(preview.fullyReviewable)
    }

    @Test
    fun aggregatePreviewBoundsEachTargetLabelAndRejectsTheApproval() {
        val path = "p".repeat(4_097)
        val changes = listOf(FileChangeSummary(path, "update", ""))

        val preview = aggregateFileChangePreview(changes)

        assertEquals(4_096, preview.files.single().path.length)
        assertEquals(4_096, preview.renderedTargetCharCount)
        assertTrue(preview.files.single().targetTruncated)
        assertTrue(preview.targetTruncated)
        assertFalse(preview.fullyReviewable)
        assertFalse(fileApproval(changes).canApprove(emptyList()))
    }

    @Test
    fun aggregatePreviewNeverSplitsASurrogatePairInATargetLabel() {
        val path = "p".repeat(4_095) + "😀tail"
        val changes = listOf(FileChangeSummary(path, "update", ""))

        val preview = aggregateFileChangePreview(changes)

        assertEquals(4_095, preview.files.single().path.length)
        assertFalse(preview.files.single().path.last().isHighSurrogate())
        assertTrue(preview.files.single().targetTruncated)
        assertFalse(preview.fullyReviewable)
    }

    @Test
    fun aggregatePreviewSharesOneTargetLabelBudgetAcrossAllFiles() {
        val changes = (1..9).map { index ->
            FileChangeSummary(index.toString().repeat(4_096), "update", "")
        }

        val preview = aggregateFileChangePreview(changes)

        assertEquals(32_768, preview.renderedTargetCharCount)
        assertEquals(4_096, preview.files[7].path.length)
        assertEquals("", preview.files[8].path)
        assertTrue(preview.files[8].targetTruncated)
        assertFalse(preview.fullyReviewable)
    }

    @Test
    fun aggregatePreviewCapsTargetNodesAndReportsEveryHiddenTarget() {
        val changes = (1..201).map { index ->
            FileChangeSummary("app/File$index.kt", "update", "")
        }

        val preview = aggregateFileChangePreview(changes)

        assertEquals(200, preview.files.size)
        assertEquals("app/File200.kt", preview.files.last().path)
        assertEquals(1, preview.hiddenTargetCount)
        assertTrue(preview.targetsTruncated)
        assertFalse(preview.fullyReviewable)
    }

    @Test
    fun fileApprovalRejectsEveryAggregatePreviewTruncationClass() {
        val tooManyLines = listOf(
            FileChangeSummary(
                "app/Lines.kt",
                "update",
                (1..201).joinToString("\n") { "+line-$it" },
            ),
        )
        val tooManyChars = listOf(
            FileChangeSummary("app/Chars.kt", "update", "x".repeat(32_769)),
        )
        val tooManyTargets = (1..201).map { index ->
            FileChangeSummary("app/File$index.kt", "update", "")
        }

        assertFalse(fileApproval(tooManyLines).canApprove(emptyList()))
        assertFalse(fileApproval(tooManyChars).canApprove(emptyList()))
        assertFalse(fileApproval(tooManyTargets).canApprove(emptyList()))
    }

    private fun fileApproval(changes: List<FileChangeSummary>) = ApprovalRequest(
        requestId = RpcRequestId.Text("request-reviewability"),
        kind = ApprovalKind.FILE_CHANGE,
        title = "Allow file changes?",
        detail = "Apply patch",
        rawMethod = "item/fileChange/requestApproval",
        fileChanges = changes,
        availableDecisions = listOf("accept", "decline"),
    )
}
