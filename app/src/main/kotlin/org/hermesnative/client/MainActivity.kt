package org.hermesnative.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.hermesnative.client.feature.entry.presentation.EntryScreen
import org.hermesnative.client.feature.entry.presentation.HermesTheme
import org.hermesnative.client.feature.entry.wiring.EntryWiring

class MainActivity : ComponentActivity() {
    private val entryStateHolder by lazy(EntryWiring::createEntryStateHolder)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by entryStateHolder.uiState.collectAsState()
            HermesTheme {
                EntryScreen(
                    state = state,
                    onEvent = entryStateHolder::onEvent,
                )
            }
        }
    }
}
