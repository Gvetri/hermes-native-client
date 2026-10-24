package org.hermesnative.client

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.XmlRes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hermesnative.client.feature.entry.domain.GatewayHistoryMessage
import org.hermesnative.client.feature.entry.domain.HistoryCacheKey
import org.hermesnative.client.feature.entry.domain.SessionId
import org.hermesnative.client.feature.entry.wiring.AndroidKeyStoreHistoryCacheStorage
import org.hermesnative.client.feature.entry.wiring.buildHistoryCacheStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class SecureHistoryCacheStorageTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val cacheKey = HistoryCacheKey("https://gateway.example", SessionId("secure-session"))

    @Before
    fun clearBeforeTest() {
        buildHistoryCacheStore(context).clear()
    }

    @After
    fun clearAfterTest() {
        buildHistoryCacheStore(context).clear()
    }

    @Test
    fun secure_default_storage_round_trips_only_ciphertext_in_no_backup_and_keystore() {
        val store = buildHistoryCacheStore(context)

        store.write(cacheKey, listOf(message("fixture-secret-transcript")))

        val recordFile = File(context.noBackupFilesDir, AndroidKeyStoreHistoryCacheStorage.CACHE_FILE_NAME)
        val rawRecord = recordFile.readBytes().toString(StandardCharsets.UTF_8)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(AndroidKeyStoreHistoryCacheStorage.KEY_ALIAS, null) as? SecretKey

        assertEquals("fixture-secret-transcript", store.read(cacheKey).single().content)
        assertTrue(recordFile.isFile)
        assertFalse(rawRecord.contains("fixture-secret-transcript"))
        assertNotNull(key)
        assertNull(key?.encoded)
        assertTrue(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0)
        assertEquals(context.noBackupFilesDir, recordFile.parentFile)
    }

    @Test
    fun wholesale_clearing_the_history_cache_removes_ciphertext_and_keystore_key() {
        val store = buildHistoryCacheStore(context)
        store.write(cacheKey, listOf(message("fixture-secret-transcript")))

        store.clear()

        val recordFile = File(context.noBackupFilesDir, AndroidKeyStoreHistoryCacheStorage.CACHE_FILE_NAME)
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        assertFalse(recordFile.exists())
        assertFalse(keyStore.containsAlias(AndroidKeyStoreHistoryCacheStorage.KEY_ALIAS))
        assertTrue(store.read(cacheKey).isEmpty())
    }

    @Test
    fun a_cleared_cache_re_populates_from_later_writes_through_a_fresh_key() {
        val store = buildHistoryCacheStore(context)
        store.write(cacheKey, listOf(message("fixture-secret-transcript")))
        store.clear()

        store.write(cacheKey, listOf(message("replacement transcript")))

        assertEquals("replacement transcript", store.read(cacheKey).single().content)
    }

    @Test
    fun a_missing_keystore_key_makes_cached_content_unreadable_until_the_next_write() {
        val store = buildHistoryCacheStore(context)
        store.write(cacheKey, listOf(message("fixture-secret-transcript")))
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
            deleteEntry(AndroidKeyStoreHistoryCacheStorage.KEY_ALIAS)
        }

        assertTrue(store.read(cacheKey).isEmpty())

        store.write(cacheKey, listOf(message("replacement transcript")))

        assertEquals("replacement transcript", store.read(cacheKey).single().content)
    }

    @Test
    fun backup_rules_exclude_the_history_cache_record_location() {
        val exclusions = declaredExclusions(R.xml.backup_rules)

        assertTrue(exclusions.contains("full-backup-content:root:no_backup/"))
    }

    @Test
    fun cloud_backup_and_device_transfer_rules_exclude_the_history_cache_record_location() {
        val exclusions = declaredExclusions(R.xml.data_extraction_rules)

        assertTrue(exclusions.contains("cloud-backup:root:no_backup/"))
        assertTrue(exclusions.contains("device-transfer:root:no_backup/"))
    }

    private fun message(content: String): GatewayHistoryMessage {
        return GatewayHistoryMessage(id = "message-1", role = "user", content = content)
    }

    private fun declaredExclusions(
        @XmlRes resourceId: Int,
    ): List<String> {
        val parser = context.resources.getXml(resourceId)
        val exclusions = mutableListOf<String>()
        var section = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "full-backup-content", "cloud-backup", "device-transfer" -> section = parser.name
                    "exclude" -> {
                        val domain = parser.getAttributeValue(null, "domain")
                        val path = parser.getAttributeValue(null, "path")
                        exclusions += "$section:$domain:$path"
                    }
                }
            }
            event = parser.next()
        }
        return exclusions
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
