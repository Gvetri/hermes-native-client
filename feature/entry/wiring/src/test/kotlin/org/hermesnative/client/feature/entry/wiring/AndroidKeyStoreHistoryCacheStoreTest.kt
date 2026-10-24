package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import org.hermesnative.client.feature.entry.data.AesGcmHistoryCacheCipher
import org.hermesnative.client.feature.entry.data.BoundedHistoryCacheStore
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.HistoryCacheKey
import org.hermesnative.client.feature.entry.domain.HistoryCacheStore
import org.hermesnative.client.feature.entry.domain.SessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidKeyStoreHistoryCacheStoreTest {
    private val cipherKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private fun newStore(): BoundedHistoryCacheStore = storeFor(RuntimeEnvironment.getApplication())

    @Test
    fun the_cache_blob_lives_in_the_no_backup_dir_and_holds_no_plaintext() {
        val context = RuntimeEnvironment.getApplication()
        val store = storeFor(context)
        val cacheKey = HistoryCacheKey("https://gateway.example/a", SessionId("session-1"))

        store.write(cacheKey, listOf(message("m1", "plaintext transcript marker")))

        val file = File(context.noBackupFilesDir, AndroidKeyStoreHistoryCacheStorage.CACHE_FILE_NAME)
        assertTrue(file.isFile)
        assertEquals(context.noBackupFilesDir, file.parentFile)
        val raw = file.readBytes().toString(StandardCharsets.ISO_8859_1)
        assertFalse(raw.contains("plaintext transcript"))
        assertFalse(raw.contains("session-1"))
        assertEquals("plaintext transcript marker", store.read(cacheKey).single().content)
    }

    @Test
    fun equivalent_spellings_of_one_endpoint_share_the_cache_and_other_endpoints_do_not() {
        val store = newStore()
        val written = HistoryCacheKey("https://gateway.example/a", SessionId("session-1"))
        val alias = HistoryCacheKey("https://GATEWAY.example:443/a/", SessionId("session-1"))
        val other = HistoryCacheKey("https://gateway.example/b", SessionId("session-1"))
        val messages = listOf(message("m1", "shared namespace content"), message("m2", "second line"))

        store.write(written, messages)

        assertEquals(messages, store.read(alias))
        assertTrue(store.read(other).isEmpty())
    }

    @Test
    fun clearing_one_endpoint_purges_only_that_endpoints_namespace() {
        val store = newStore()
        val endpoint = "https://gateway.example/a"
        val purgedKey = HistoryCacheKey(endpoint, SessionId("session-1"))
        val aliasKey = HistoryCacheKey("https://GATEWAY.example:443/a/", SessionId("session-1"))
        val keptKey = HistoryCacheKey("https://gateway.example/b", SessionId("session-1"))

        store.write(purgedKey, listOf(message("m1", "purged endpoint content")))
        store.write(keptKey, listOf(message("m2", "kept endpoint content")))

        store.clearEndpoint(endpoint)

        assertTrue(store.read(purgedKey).isEmpty())
        assertTrue(store.read(aliasKey).isEmpty())
        assertEquals(listOf(message("m2", "kept endpoint content")), store.read(keptKey))
    }

    @Test
    fun the_factory_wires_a_store_that_reads_empty_before_any_content_is_cached() {
        val store: HistoryCacheStore = buildHistoryCacheStore(RuntimeEnvironment.getApplication())

        assertTrue(store.read(HistoryCacheKey("https://gateway.example/a", SessionId("session-1"))).isEmpty())
    }

    @Test
    fun the_namespace_is_the_sha256_of_the_normalized_endpoint_with_a_trim_fallback() {
        assertEquals(
            "192a7aef3684b4a4ca681d15e7d3ee103f0e89b93441790907174b9035e8bafd",
            historyCacheEndpointNamespace("https://GATEWAY.example:443/a/"),
        )
        assertEquals(
            "1f5982e38e2b8ea34c7ca9d580481292421314f4f33ce24309224df7186621e9",
            historyCacheEndpointNamespace("  gateway invalid  "),
        )
    }

    @Test
    fun the_cache_key_alias_is_not_the_saved_credential_alias() {
        assertNotEquals(AndroidKeyStoreGatewayCredentialStore.KEY_ALIAS, AndroidKeyStoreHistoryCacheStorage.KEY_ALIAS)
    }

    private fun storeFor(context: Context): BoundedHistoryCacheStore =
        BoundedHistoryCacheStore(
            storage = AndroidKeyStoreHistoryCacheStorage(context),
            cipher = AesGcmHistoryCacheCipher { cipherKey },
            endpointNamespace = ::historyCacheEndpointNamespace,
        )

    private fun message(
        id: String,
        content: String,
    ): GatewayHistoryMessage = GatewayHistoryMessage(id = id, role = "user", content = content)
}
