package org.hermesnative.client.feature.entry.wiring

import org.hermesnative.client.feature.entry.application.LoadEntryState
import org.hermesnative.client.feature.entry.data.DefaultGatewayConnectionRepository
import org.hermesnative.client.feature.entry.data.InMemoryGatewayConnectionDataSource
import org.hermesnative.client.feature.entry.presentation.EntryStateHolder

object EntryWiring {
    fun createEntryStateHolder(): EntryStateHolder {
        val dataSource = InMemoryGatewayConnectionDataSource()
        val repository = DefaultGatewayConnectionRepository(dataSource)
        val initialState = LoadEntryState(repository).execute()
        return EntryStateHolder(initialState)
    }
}
