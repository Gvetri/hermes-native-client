package org.hermesnative.client.feature.entry.presentation

import org.hermesnative.client.feature.entry.domain.SessionId

/**
 * Fixtures shared by the Session shell tests: an entry state that reports a connected
 * Gateway and a minimal Session row.
 */
internal fun entryState(sessionList: SessionListUiState): EntryUiState =
    EntryUiState(
        title = "Gateway connected",
        supportingText = "Connected",
        actionLabel = "Connected",
        isConnected = true,
        sessionList = sessionList,
    )

internal fun session(
    id: String,
    title: String,
): SessionItemUiState = SessionItemUiState(id = SessionId(id), title = title, preview = null, pinned = false)
