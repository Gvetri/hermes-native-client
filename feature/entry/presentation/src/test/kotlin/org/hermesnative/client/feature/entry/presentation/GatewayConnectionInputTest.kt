package org.hermesnative.client.feature.entry.presentation

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import org.hermesnative.client.feature.entry.application.EntryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class GatewayConnectionInputTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun credential_requests_a_password_keyboard_without_correction_or_capitalization() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = setupState(), onEvent = {})
            }
        }
        composeTestRule.onNodeWithText("Bearer credential").performClick()
        composeTestRule.waitForIdle()

        val info = currentEditorInfo()

        assertEquals(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            info.inputType and (InputType.TYPE_MASK_CLASS or InputType.TYPE_MASK_VARIATION),
        )
        assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertEquals(
            0,
            info.inputType and
                (
                    InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                        InputType.TYPE_TEXT_FLAG_CAP_WORDS or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                ),
        )
    }

    @Test
    fun showing_and_hiding_the_credential_preserves_its_value_and_password_keyboard() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = setupState(), onEvent = events::add)
            }
        }

        composeTestRule.onNodeWithText("Bearer credential")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeTestRule.onNodeWithContentDescription("Show credential").performClick()
        composeTestRule.onNodeWithText("Bearer credential")
            .assertTextContains("synthetic-token")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
            .performClick()
        val info = currentEditorInfo()
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, info.inputType and InputType.TYPE_MASK_VARIATION)
        composeTestRule.onNodeWithContentDescription("Hide credential").performClick()
        composeTestRule.onNodeWithText("Bearer credential")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertTrue(events.isEmpty())
    }

    @Test
    fun an_unverified_address_does_not_offer_connection_removal() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = setupState(), onEvent = {})
            }
        }

        composeTestRule.onNodeWithText("Remove Gateway Connection").assertDoesNotExist()
    }

    @Test
    fun a_saved_connection_can_be_removed_before_reverification() {
        val holder =
            EntryStateHolder(
                EntryState(isGatewayConnectionConfigured = true, configuredEndpoint = "https://gateway.example"),
            )
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = holder.uiState.value, onEvent = events::add)
            }
        }

        composeTestRule.onNodeWithText("Remove Gateway Connection").assertHasClickAction().performClick()
        assertEquals(listOf(EntryUiEvent.RemoveGatewayConnectionClicked), events)
        holder.close()
    }

    @Test
    fun setup_has_one_task_heading_and_an_https_address_example() {
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = setupState().copy(endpoint = ""), onEvent = {})
            }
        }

        composeTestRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertCountEquals(1)
        composeTestRule.onNodeWithText("Example: https://gateway.example").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w411dp-h480dp")
    fun verification_and_recovery_scroll_above_the_keyboard_at_large_font_scale() {
        val state = mutableStateOf(setupState())
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                HermesTheme {
                    EntryScreen(state = state.value, onEvent = {})
                }
            }
        }
        composeTestRule.onNodeWithText("Bearer credential").performScrollTo().performClick()
        val windowHeight = composeTestRule.activity.window.decorView.height
        val imeInset = windowHeight / 2
        composeTestRule.runOnUiThread {
            val insets =
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeInset))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build()
            composeTestRule.activity.window.decorView.dispatchApplyWindowInsets(insets.toWindowInsets())
        }
        composeTestRule.waitForIdle()

        fun assertAboveKeyboard(text: String) {
            val action = composeTestRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            val bottom = action.fetchSemanticsNode().boundsInWindow.bottom
            assertTrue("$text must be above the keyboard (bottom=$bottom)", bottom <= windowHeight - imeInset + 1f)
        }

        assertAboveKeyboard("Verify Gateway Connection")
        composeTestRule.runOnIdle {
            state.value = state.value.copy(errorCategory = EntryErrorCategory.AUTHENTICATION_FAILED)
        }
        assertAboveKeyboard("Try again")
        assertAboveKeyboard(EntryErrorCategory.AUTHENTICATION_FAILED.safeMessage)
    }

    @Test
    fun address_next_moves_to_the_credential_without_verifying() {
        val events = mutableListOf<EntryUiEvent>()
        composeTestRule.setContent {
            HermesTheme {
                EntryScreen(state = setupState(), onEvent = events::add)
            }
        }
        composeTestRule.onNodeWithText("Gateway HTTPS endpoint").performClick()
        val addressInfo = currentEditorInfo()
        assertEquals(InputType.TYPE_TEXT_VARIATION_URI, addressInfo.inputType and InputType.TYPE_MASK_VARIATION)
        assertEquals(EditorInfo.IME_ACTION_NEXT, addressInfo.imeOptions and EditorInfo.IME_MASK_ACTION)
        composeTestRule.onNodeWithText("Gateway HTTPS endpoint").performImeAction()
        composeTestRule.onNodeWithText("Bearer credential").assertIsFocused()
        val credentialInfo = currentEditorInfo()
        assertEquals(EditorInfo.IME_ACTION_DONE, credentialInfo.imeOptions and EditorInfo.IME_MASK_ACTION)
        composeTestRule.onNodeWithText("Bearer credential").performImeAction()
        assertTrue(events.isEmpty())
    }

    private fun currentEditorInfo(): EditorInfo {
        val info = EditorInfo()
        composeTestRule.runOnUiThread {
            val editor = composeTestRule.activity.window.decorView.findInputEditor()
            assertNotNull("The focused credential must have an input editor", editor)
            assertNotNull(editor!!.onCreateInputConnection(info))
        }
        return info
    }

    private fun setupState(): EntryUiState =
        EntryUiState(
            title = "Verify a Hermes Gateway",
            supportingText = "Enter one profile-specific HTTPS endpoint and bearer credential.",
            actionLabel = "Verify Gateway Connection",
            connectionSetupRequested = true,
            endpoint = "https://gateway.example",
            bearerCredential = "synthetic-token",
        )

    private fun View.findInputEditor(): View? {
        if (onCheckIsTextEditor()) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).findInputEditor()?.let { return it }
            }
        }
        return null
    }
}
