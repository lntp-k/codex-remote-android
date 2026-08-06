package com.codex.remote.ui.screens

import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.RemoteThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalOwnerPresentationTest {
    @Test
    fun allowRequiresTheSelectedTasksExactActiveTurn() {
        val live = AppUiState(
            selectedThreadId = "thread-a",
            isTurnRunning = true,
            activeTurnId = "turn-a",
        )

        assertTrue(approvalMatchesSelectedActiveTurn("thread-a", "turn-a", live))
        assertFalse(approvalMatchesSelectedActiveTurn("thread-b", "turn-a", live))
        assertFalse(approvalMatchesSelectedActiveTurn("thread-a", "turn-b", live))
        assertFalse(
            approvalMatchesSelectedActiveTurn(
                "thread-a",
                "turn-a",
                live.copy(isTurnRunning = false, activeTurnId = null),
            ),
        )
    }

    @Test
    fun selectedOwnerRetainsExactTaskIdentity() {
        val thread = remoteThread(id = "thread-b", title = "Background review", cwd = "/workspace/b")

        val owner = approvalOwnerPresentation(
            threadId = thread.id,
            selectedThreadId = thread.id,
            threads = listOf(thread),
        )

        requireNotNull(owner)
        assertEquals("thread-b", owner.threadId)
        assertEquals("Background review", owner.title)
        assertEquals("/workspace/b", owner.projectPath)
        assertTrue(owner.isSelected)
        assertSame(thread, owner.thread)
    }

    @Test
    fun backgroundOwnerIsExplicitAndCannotBeTreatedAsSelected() {
        val selected = remoteThread(id = "thread-a", title = "Selected", cwd = "/workspace/a")
        val background = remoteThread(id = "thread-b", title = "Background", cwd = "/workspace/b")

        val owner = approvalOwnerPresentation(
            threadId = background.id,
            selectedThreadId = selected.id,
            threads = listOf(selected, background),
        )

        requireNotNull(owner)
        assertEquals("Background", owner.title)
        assertFalse(owner.isSelected)
        assertSame(background, owner.thread)
    }

    @Test
    fun unknownOwnerUsesExactIdAndRequestPathButRemainsUnselected() {
        val owner = approvalOwnerPresentation(
            threadId = "thread-remote",
            selectedThreadId = "thread-a",
            threads = emptyList(),
            fallbackPath = "/workspace/remote",
        )

        requireNotNull(owner)
        assertEquals("Task thread-remote", owner.title)
        assertEquals("/workspace/remote", owner.projectPath)
        assertFalse(owner.isSelected)
        assertNull(owner.thread)
    }

    @Test
    fun unknownOwnerCannotBecomeSelectableFromAnIdMatchAlone() {
        val owner = approvalOwnerPresentation(
            threadId = "thread-remote",
            selectedThreadId = "thread-remote",
            threads = emptyList(),
            fallbackPath = "/workspace/remote",
        )

        requireNotNull(owner)
        assertFalse(owner.isSelected)
        assertNull(owner.thread)
    }

    @Test
    fun missingOwnerIdentityFailsClosed() {
        assertNull(
            approvalOwnerPresentation(
                threadId = null,
                selectedThreadId = "thread-a",
                threads = emptyList(),
            ),
        )
        assertNull(
            approvalOwnerPresentation(
                threadId = "",
                selectedThreadId = "thread-a",
                threads = emptyList(),
            ),
        )
    }

    private fun remoteThread(id: String, title: String, cwd: String) = RemoteThread(
        id = id,
        title = title,
        cwd = cwd,
        updatedAt = 1L,
        status = "idle",
    )
}
