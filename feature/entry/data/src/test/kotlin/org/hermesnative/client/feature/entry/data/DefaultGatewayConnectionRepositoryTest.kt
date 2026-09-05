package org.hermesnative.client.feature.entry.data

import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultGatewayConnectionRepositoryTest {
    @Test
    fun reports_the_datasource_connection_state() {
        val repository =
            DefaultGatewayConnectionRepository(
                FakeGatewayConnectionDataSource(isConfigured = true),
            )

        assertTrue(repository.hasConfiguredConnection())
    }

    private class FakeGatewayConnectionDataSource(
        private val isConfigured: Boolean,
    ) : GatewayConnectionDataSource {
        override fun hasConfiguredConnection(): Boolean = isConfigured
    }
}
