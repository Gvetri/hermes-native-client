package org.hermesnative.client

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.XmlRes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hermesnative.client.feature.entry.wiring.AndroidKeyStoreGatewayCredentialStore
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
class SecureGatewayCredentialStorageTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val store: AndroidKeyStoreGatewayCredentialStore
        get() = AndroidKeyStoreGatewayCredentialStore(context)

    @Before
    fun clearBeforeTest() {
        store.clear()
    }

    @After
    fun clearAfterTest() {
        store.clear()
    }

    @Test
    fun opt_in_storage_round_trips_only_ciphertext_in_no_backup_and_keystore() {
        val credential = "fixture-secret-credential"

        store.save(credential)

        val recordFile = File(context.noBackupFilesDir, AndroidKeyStoreGatewayCredentialStore.CREDENTIAL_FILE_NAME)
        val rawRecord = recordFile.readBytes().toString(StandardCharsets.UTF_8)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey(AndroidKeyStoreGatewayCredentialStore.KEY_ALIAS, null) as? SecretKey

        assertEquals(credential, store.load())
        assertTrue(recordFile.isFile)
        assertFalse(rawRecord.contains(credential))
        assertNotNull(key)
        assertNull(key?.encoded)
        assertTrue(context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP == 0)
        assertEquals(context.noBackupFilesDir, recordFile.parentFile)
    }

    @Test
    fun clearing_the_gateway_connection_removes_ciphertext_and_keystore_key() {
        store.save("fixture-secret-credential")

        store.clear()

        val recordFile = File(context.noBackupFilesDir, AndroidKeyStoreGatewayCredentialStore.CREDENTIAL_FILE_NAME)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertFalse(recordFile.exists())
        assertFalse(keyStore.containsAlias(AndroidKeyStoreGatewayCredentialStore.KEY_ALIAS))
        assertNull(store.load())
    }

    @Test
    fun a_missing_keystore_key_requires_credential_reentry_after_restore() {
        store.save("fixture-secret-credential")
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(AndroidKeyStoreGatewayCredentialStore.KEY_ALIAS)
        }

        assertNull(store.load())
    }

    @Test
    fun backup_rules_exclude_the_credential_record_location() {
        val exclusions = declaredExclusions(R.xml.backup_rules)

        assertTrue(exclusions.contains("full-backup-content:root:no_backup/"))
    }

    @Test
    fun cloud_backup_and_device_transfer_rules_exclude_the_credential_record_location() {
        val exclusions = declaredExclusions(R.xml.data_extraction_rules)

        assertTrue(exclusions.contains("cloud-backup:root:no_backup/"))
        assertTrue(exclusions.contains("device-transfer:root:no_backup/"))
    }

    /**
     * The record lives in [Context.getNoBackupFilesDir], which the Android
     * backup domains call `no_backup/`. Every declared exclusion therefore has
     * to name that path in the section that governs one transfer mechanism.
     */
    private fun declaredExclusions(
        @XmlRes resourceId: Int,
    ): List<String> {
        val parser = context.resources.getXml(resourceId)
        val exclusions = mutableListOf<String>()
        var section = parser.name.orEmpty()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "cloud-backup", "device-transfer" -> section = parser.name
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
}
