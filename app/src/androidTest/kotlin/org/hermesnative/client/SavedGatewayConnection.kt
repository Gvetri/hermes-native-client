package org.hermesnative.client

import androidx.test.platform.app.InstrumentationRegistry
import org.hermesnative.client.feature.entry.wiring.SharedPreferencesGatewayConnectionDataSource

/**
 * Device tests must not depend on a Gateway connection that an earlier run left on the same
 * device: the app persists one in SharedPreferences and the entry screen differs while it is
 * present, so a test that assumes the first-use screen fails only on a used emulator.
 */
internal fun clearSavedGatewayConnection() {
    SharedPreferencesGatewayConnectionDataSource(
        InstrumentationRegistry.getInstrumentation().targetContext,
    ).clearEndpoint()
}
