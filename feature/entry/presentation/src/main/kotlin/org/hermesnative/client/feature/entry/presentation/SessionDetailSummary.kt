package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.hermesnative.client.feature.entry.domain.RunId
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun SessionDetailSummary(
    detail: SessionDetailState,
    retryEnabled: Boolean,
    onEvent: (EntryUiEvent) -> Unit,
) {
    val state = detail.content
    RefreshHistoryButton(
        state = state,
        mutation = detail.mutation,
        listRequestActive = detail.listRequestActive,
        onEvent = onEvent,
    )
    SessionSummaryStatusTexts(
        state = state,
        listIsStale = detail.listIsStale,
        listErrorCategory = detail.listErrorCategory,
    )
    SessionActionControls(
        session = state.session,
        mutation = detail.mutation,
        enabled = detail.actionsEnabled && detail.mutation?.pendingAction == null,
        onEvent = onEvent,
        showMenu = false,
    )
    LatestRunSection(state = state, retryEnabled = retryEnabled, onEvent = onEvent)
}

@Composable
private fun RefreshHistoryButton(
    state: OpenSessionUiState,
    mutation: SessionMutationUiState?,
    listRequestActive: Boolean,
    onEvent: (EntryUiEvent) -> Unit,
) {
    OutlinedButton(
        onClick = { onEvent(EntryUiEvent.RefreshSessionsClicked) },
        enabled = !state.isRefreshing && !listRequestActive && mutation?.pendingAction == null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text = "Refresh history")
    }
}

@Composable
private fun SessionSummaryStatusTexts(
    state: OpenSessionUiState,
    listIsStale: Boolean,
    listErrorCategory: SessionListErrorCategory?,
) {
    if (state.isRefreshing) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text =
                if (state.isReconciliationInProgress) {
                    "Refreshing Run and Session state…"
                } else {
                    "Refreshing Session history…"
                },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    if (state.isStale || listIsStale) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text =
                if (state.isStale) {
                    "The displayed Session history may be stale."
                } else {
                    "The displayed Session data may be stale."
                },
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
    state.errorCategory?.let { category ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = category.safeMessage,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
    listErrorCategory?.let { category ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = category.safeMessage,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
}

@Composable
private fun LatestRunSection(
    state: OpenSessionUiState,
    retryEnabled: Boolean,
    onEvent: (EntryUiEvent) -> Unit,
) {
    state.latestRun?.let { run ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "Latest Run: ${run.id.value}")
        state.latestRunState?.let { runState ->
            Text(
                text = "Run state: ${runState.label}",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } ?: Text(text = "Run status: ${run.status.stableRunStatusLabel()}")
        if (state.latestRunRetryAvailable) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onEvent(EntryUiEvent.RetryRunClicked(run.id)) },
                enabled = retryEnabled,
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Text(text = "Try again")
            }
        }
    }
}

@Composable
internal fun SessionMessageContent(
    message: SessionMessageUiState,
    retryEnabled: Boolean,
    onRetry: (RunId) -> Unit,
    actions: MessageActions = rememberMessageActions(),
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        message.role?.takeIf(String::isNotBlank)?.let { role ->
            Text(text = "Role: $role")
        }
        message.content
            ?.takeIf { message.failureSafeMessage == null && it.isNotBlank() }
            ?.let { content ->
                MarkdownContent(markdown = content, actions = actions)
                Spacer(modifier = Modifier.height(4.dp))
                MessageActionControls(content = content, actions = actions)
            }
        message.runId?.let { runId ->
            Text(text = "Run ID: ${runId.value}")
        }
        message.runState?.let { runState ->
            Text(
                text = "Run state: ${runState.label}",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } ?: message.runStatus?.let { status ->
            Text(text = "Run status: ${status.stableRunStatusLabel()}")
        }
        if (message.isStreaming) {
            Text(
                text = "Streaming response…",
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (message.streamInterrupted) {
            Text(
                text = "Stream interrupted. Run result is uncertain.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }
        MessageFailureSection(message = message)
        MessageRetryButton(message = message, retryEnabled = retryEnabled, onRetry = onRetry)
        if (message.failureSafeMessage == null) {
            message.runResult?.let { result ->
                Text(text = "Run result: $result")
            }
        }
        message.timestamp?.let { timestamp ->
            Text(text = "Timestamp: ${formatGatewayTimestamp(timestamp)}")
        }
    }
}

@Composable
private fun MessageFailureSection(message: SessionMessageUiState) {
    message.failureSafeMessage?.let { safeMessage ->
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = safeMessage,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        )
    }
    var showTechnicalDetail by remember(message.id) { mutableStateOf(false) }
    message.failureTechnicalDetail?.let { technicalDetail ->
        TextButton(onClick = { showTechnicalDetail = !showTechnicalDetail }) {
            Text(text = if (showTechnicalDetail) "Hide technical detail" else "Show technical detail")
        }
        if (showTechnicalDetail) {
            Text(
                text = technicalDetail,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun MessageRetryButton(
    message: SessionMessageUiState,
    retryEnabled: Boolean,
    onRetry: (RunId) -> Unit,
) {
    if (message.retryAvailable) {
        message.runId?.let { runId ->
            OutlinedButton(
                onClick = { onRetry(runId) },
                enabled = retryEnabled,
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                Text(text = "Try again")
            }
        }
    }
}

private fun String.stableRunStatusLabel(): String =
    when (lowercase(Locale.ROOT)) {
        "queued" -> "Queued"
        "running", "starting", "in_progress" -> "Running"
        "completed", "succeeded" -> "Completed"
        "failed", "error" -> "Failed"
        "cancelled", "canceled" -> "Cancelled"
        else -> "Unavailable"
    }

private fun formatGatewayTimestamp(timestamp: java.time.Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(timestamp)
