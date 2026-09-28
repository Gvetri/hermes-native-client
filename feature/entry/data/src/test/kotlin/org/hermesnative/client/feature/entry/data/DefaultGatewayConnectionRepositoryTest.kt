package org.hermesnative.client.feature.entry.data

import org.hermesnative.client.feature.entry.domain.GatewayConnection
import org.hermesnative.client.feature.entry.domain.GatewayCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultGatewayConnectionRepositoryTest {
    @Test
    fun loads_and_saves_the_non_secret_gateway_connection() {
        val dataSource = InMemoryGatewayConnectionDataSource()
        val repository = DefaultGatewayConnectionRepository(dataSource)
        val connection = GatewayConnection("https://gateway.example/profile")

        repository.save(connection)

        assertEquals(connection, repository.load())
        assertEquals("https://gateway.example/profile", dataSource.loadEndpoint())
    }

    @Test
    fun keeps_an_opt_in_credential_in_process_memory_and_clears_it_with_the_connection() {
        val dataSource = InMemoryGatewayConnectionDataSource()
        val repository = DefaultGatewayConnectionRepository(dataSource)
        val connection = GatewayConnection("https://gateway.example/profile", "memory-only-token")

        repository.save(connection)

        assertEquals(connection, repository.load())

        repository.clear()

        assertNull(repository.load())
    }

    @Test
    fun failed_endpoint_replacement_restores_the_working_credential() {
        val dataSource = FailingGatewayConnectionDataSource("https://gateway.example/profile")
        val credentialStore = InMemoryGatewayCredentialStore("working-token")
        val repository = DefaultGatewayConnectionRepository(dataSource, credentialStore)

        val failure =
            runCatching {
                repository.save(GatewayConnection("https://gateway.example/profile", "replacement-token"))
            }.exceptionOrNull()

        assertTrue(failure != null)
        assertEquals("working-token", credentialStore.load())
        assertEquals("https://gateway.example/profile", dataSource.loadEndpoint())
    }

    @Test
    fun clearing_the_connection_clears_the_endpoint_even_when_credential_cleanup_fails() {
        val dataSource = InMemoryGatewayConnectionDataSource("https://gateway.example/profile")
        val credentialStore =
            object : GatewayCredentialStore {
                override fun load(): String? = "working-token"

                override fun save(credential: String) = Unit

                override fun clear(): Unit = error("credential cleanup failed")
            }
        val repository = DefaultGatewayConnectionRepository(dataSource, credentialStore)

        val failure = runCatching { repository.clear() }.exceptionOrNull()

        assertTrue(failure != null)
        assertNull(dataSource.loadEndpoint())
    }

    private class FailingGatewayConnectionDataSource(
        private var endpoint: String?,
    ) : GatewayConnectionDataSource {
        override fun loadEndpoint(): String? = endpoint

        override fun saveEndpoint(endpoint: String): Unit = error("endpoint write failed")

        override fun clearEndpoint() {
            endpoint = null
        }
    }
}
