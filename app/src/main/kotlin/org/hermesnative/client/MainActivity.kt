package org.hermesnative.client

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.hermesnative.client.feature.entry.presentation.EntryScreen
import org.hermesnative.client.feature.entry.presentation.EntryStateHolder
import org.hermesnative.client.feature.entry.presentation.EntryUiEvent
import org.hermesnative.client.feature.entry.presentation.HermesTheme
import org.hermesnative.client.feature.entry.wiring.EntryWiring

class MainActivity : ComponentActivity() {
    private lateinit var entryStateHolder: EntryStateHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entryStateHolder = EntryWiring.createEntryStateHolder(applicationContext)
        setContent {
            val notificationPermissionLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    entryStateHolder.onEvent(EntryUiEvent.RunStatusNotificationPermissionResult(granted))
                }
            LaunchedEffect(Unit) {
                // The state holder invokes this callback only when a runtime
                // permission request is actually required before enabling.
                entryStateHolder.requestRunStatusNotificationPermission = {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
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
