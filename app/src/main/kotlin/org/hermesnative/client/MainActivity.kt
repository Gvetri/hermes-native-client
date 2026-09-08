package org.hermesnative.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.hermesnative.client.feature.entry.presentation.EntryScreen
import org.hermesnative.client.feature.entry.presentation.EntryStateHolder
import org.hermesnative.client.feature.entry.presentation.HermesTheme
import org.hermesnative.client.feature.entry.wiring.EntryWiring

class MainActivity : ComponentActivity() {
    private lateinit var entryStateHolder: EntryStateHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entryStateHolder = EntryWiring.createEntryStateHolder(applicationContext)
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

    override fun onDestroy() {
        entryStateHolder.close()
        super.onDestroy()
    }
}
