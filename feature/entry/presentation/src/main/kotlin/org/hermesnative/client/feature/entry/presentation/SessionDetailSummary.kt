package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
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
    val groupRunWithRefresh =
        LocalConfiguration.current.screenWidthDp >= TWO_PANE_MIN_WIDTH_DP && state.latestRun != null
    if (groupRunWithRefresh) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RefreshHistoryButton(
                state = state,
                mutation = detail.mutation,
                listRequestActive = detail.listRequestActive,
                onEvent = onEvent,
                modifier = Modifier,
            )
            Column(modifier = Modifier.weight(1f)) {
                LatestRunSection(state = state, retryEnabled = retryEnabled, onEvent = onEvent)
            }
        }
    } else {
        RefreshHistoryButton(
            state = state,
            mutation = detail.mutation,
            listRequestActive = detail.listRequestActive,
            onEvent = onEvent,
            modifier = Modifier.fillMaxWidth(),
        )
    }
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
    if (!groupRunWithRefresh && state.latestRun != null) {
        Spacer(modifier = Modifier.height(12.dp))
        LatestRunSection(state = state, retryEnabled = retryEnabled, onEvent = onEvent)
    }
}

@Composable
private fun RefreshHistoryButton(
    state: OpenSessionUiState,
    mutation: SessionMutationUiState?,
    listRequestActive: Boolean,
    onEvent: (EntryUiEvent) -> Unit,
    modifier: Modifier,
) {
    TextButton(
        onClick = { onEvent(EntryUiEvent.RefreshSessionsClicked) },
        enabled = !state.isRefreshing && !listRequestActive && mutation?.pendingAction == null,
        modifier = modifier.heightIn(min = 48.dp),
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
    if (state.isCached) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "The displayed Session history is cached on this device. It is not live.",
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Latest Run",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            RunIdDisclosure(run.id)
        }
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
    onTrailingContentHeightChanged: (Int) -> Unit = {},
) {
    val spacing = LocalHermesDesignTokens.current.spacing
    val roleLabel =
        message.role?.takeIf(String::isNotBlank)?.let { role ->
            when (role) {
                "user" -> "User"
                "assistant" -> "Assistant"
                else -> role
            }
        } ?: "Role unavailable"
    val eligibleContent = message.content?.takeIf { it.isNotBlank() && message.failureSafeMessage == null }
    val bottomPadding = with(LocalDensity.current) { spacing.l.roundToPx() }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(spacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = roleLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Spacer(modifier = Modifier.width(spacing.l))
                MessageActionMenu(messageId = message.id, payload = eligibleContent, actions = actions)
            }
            eligibleContent?.let { content ->
                Spacer(modifier = Modifier.height(4.dp))
                MarkdownContent(markdown = content, actions = actions)
            }
            Column(
                modifier =
                    Modifier.onSizeChanged {
                        onTrailingContentHeightChanged(if (eligibleContent == null) 0 else it.height + bottomPadding)
                    },
            ) {
                MessageRunMetadata(message = message)
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
    }
}

@Composable
private fun MessageRunMetadata(message: SessionMessageUiState) {
    message.runId?.let { runId ->
        RunIdDisclosure(runId)
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
