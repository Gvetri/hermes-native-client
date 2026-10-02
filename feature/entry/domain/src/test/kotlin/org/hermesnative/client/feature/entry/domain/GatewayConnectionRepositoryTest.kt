package org.hermesnative.client.feature.entry.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayConnectionRepositoryTest {
    @Test
    fun a_repository_without_a_stored_connection_reports_no_configured_connection() {
        val empty =
            object : GatewayConnectionRepository {
                override fun load(): GatewayConnection? = null

                override fun save(connection: GatewayConnection) = Unit
            }

        assertFalse(empty.hasConfiguredConnection())
    }

    @Test
    fun a_repository_with_a_stored_connection_reports_a_configured_connection() {
        val connection = GatewayConnection(endpoint = "http://127.0.0.1:8000", bearerCredential = null)
        val configured =
            object : GatewayConnectionRepository {
                override fun load(): GatewayConnection? = connection

                override fun save(connection: GatewayConnection) = Unit
            }

        assertTrue(configured.hasConfiguredConnection())
    }
}
