package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.hermesnative.client.feature.entry.domain.RunPresentationState

@Composable
internal fun SessionDetailComposerTail(
    state: OpenSessionUiState,
    mutation: SessionMutationUiState?,
    canSubmit: Boolean,
    displayedMessages: List<SessionMessageUiState>,
    onEvent: (EntryUiEvent) -> Unit,
) {
    val tailScrollState = rememberScrollState()
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val tailMaxHeight = paneTailMaxHeight(maxHeight)
        Column(modifier = composerTailModifier(displayedMessages.isEmpty(), tailMaxHeight, tailScrollState)) {
            Spacer(modifier = Modifier.height(12.dp))
            val composerEnabled = !state.isRefreshing && mutation?.pendingAction == null
            val sendEnabled =
                composerEnabled &&
                    !state.isReconciliationInProgress &&
                    state.latestRunState != RunPresentationState.UNCERTAIN &&
                    state.sendErrorCategory != MessageSendErrorCategory.UNCERTAIN &&
                    !state.hasUnresolvedSubmission &&
                    canSubmit &&
                    state.composerText.isNotBlank()
            OutlinedTextField(
                value = state.composerText,
                onValueChange = { onEvent(EntryUiEvent.ComposerTextChanged(it)) },
                label = { Text("Message") },
                placeholder = { Text("Write a message") },
                modifier = Modifier.fillMaxWidth(),
                enabled = composerEnabled,
                singleLine = false,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions =
                    KeyboardActions(
                        onSend = {
                            if (sendEnabled) onEvent(EntryUiEvent.SendMessageClicked)
                        },
                    ),
            )
            Spacer(modifier = Modifier.height(8.dp))
            ComposerSendButton(state = state, sendEnabled = sendEnabled, onEvent = onEvent)
            if (state.isSending) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Sending message…",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            state.sendErrorCategory?.let { category ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = category.safeMessage,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        }
    }
}

private fun composerTailModifier(
    empty: Boolean,
    tailMaxHeight: Dp,
    scrollState: ScrollState,
): Modifier =
    Modifier.testTag("conversation-composer-tail").then(
        if (empty) {
            Modifier
        } else {
            Modifier.fillMaxWidth()
                .heightIn(max = tailMaxHeight)
                .verticalScroll(scrollState)
        },
    )

@Composable
private fun ComposerSendButton(
    state: OpenSessionUiState,
    sendEnabled: Boolean,
    onEvent: (EntryUiEvent) -> Unit,
) {
    Button(
        onClick = { onEvent(EntryUiEvent.SendMessageClicked) },
        enabled = sendEnabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(
            text =
                when {
                    state.sendErrorCategory == MessageSendErrorCategory.UNCERTAIN ||
                        state.hasUnresolvedSubmission -> "Refresh history to resolve"
                    state.sendErrorCategory != null -> "Try again"
                    else -> "Send"
                },
        )
    }
}
