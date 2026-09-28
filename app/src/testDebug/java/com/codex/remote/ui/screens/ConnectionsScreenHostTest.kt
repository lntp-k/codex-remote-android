package com.codex.remote.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.remote.domain.AppUiState
import com.codex.remote.domain.AuthType
import com.codex.remote.domain.SavedConnection
import com.codex.remote.ui.theme.CodexRemoteTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h400dp-xxhdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class ConnectionsScreenHostTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun connectionEditorPrimaryActionIsVisibleInHeaderOnCompactPhone() {
        composeRule.setContent {
            CodexRemoteTheme {
                ConnectionsScreen(
                    state = AppUiState(
                        showConnectionEditor = true,
                        isRestoringLastConnection = false,
                    ),
                    onBack = {},
                    onAdd = {},
                    onEdit = {},
                    onDelete = {},
                    onConnect = {},
                    onSave = { _, _ -> },
                    onCloseEditor = {},
                    onDismissNotice = {},
                    onExportLogs = {},
                )
            }
        }

        composeRule.onNodeWithText("Connect").assertIsDisplayed()
    }

    @Test
    fun savedHostSecurityNoticeUsesSupportedAppLanguage() {
        composeRule.setContent {
            CodexRemoteTheme {
                ConnectionsScreen(
                    state = AppUiState(
                        savedConnections = listOf(
                            SavedConnection(
                                id = "test-host",
                                name = "Test host",
                                host = "192.0.2.10",
                                username = "codex-user",
                                authType = AuthType.PRIVATE_KEY,
                            ),
                        ),
                        isRestoringLastConnection = false,
                    ),
                    onBack = {},
                    onAdd = {},
                    onEdit = {},
                    onDelete = {},
                    onConnect = {},
                    onSave = { _, _ -> },
                    onCloseEditor = {},
                    onDismissNotice = {},
                    onExportLogs = {},
                )
            }
        }

        composeRule.onNodeWithText(
            "Credentials are encrypted with Android Keystore. The first connection pins the host key; a changed key blocks future connections.",
        ).assertExists()
    }
}
