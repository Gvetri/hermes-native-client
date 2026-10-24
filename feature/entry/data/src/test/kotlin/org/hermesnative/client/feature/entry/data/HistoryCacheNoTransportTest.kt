package org.hermesnative.client.feature.entry.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HistoryCacheNoTransportTest {
    private val repositoryRoot =
        File(
            requireNotNull(System.getProperty("fixture.repositoryRoot")) {
                "fixture.repositoryRoot must identify the repository root"
            },
        )

    @Test
    fun every_cache_storage_source_is_free_of_transport_and_network_references() {
        val cacheSources =
            repositoryRoot.resolve("feature/entry").walkTopDown().filter { file ->
                file.name.contains("HistoryCache") && file.invariantSeparatorsPath.contains("/src/main/")
            }.toList()
        assertTrue("cache storage sources must exist to be guarded", cacheSources.isNotEmpty())

        val violations =
            cacheSources.flatMap { source ->
                source.readLines().mapIndexedNotNull { index, line ->
                    if (TRANSPORT_MARKERS.any { marker -> line.contains(marker, ignoreCase = true) }) {
                        "${source.relativeTo(repositoryRoot)}:${index + 1}"
                    } else {
                        null
                    }
                }
            }

        assertEquals(emptyList<String>(), violations)
    }

    private companion object {
        val TRANSPORT_MARKERS =
            listOf(
                "okhttp",
                "okio",
                "GatewayTransport",
                "GatewayEventStream",
                "HttpURLConnection",
                "java.net",
                "Socket",
            )
    }
}
