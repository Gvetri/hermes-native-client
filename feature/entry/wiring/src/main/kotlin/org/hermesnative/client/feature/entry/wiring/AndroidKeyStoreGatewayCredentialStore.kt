package org.hermesnative.client.feature.entry.wiring

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.hermesnative.client.feature.entry.data.AesGcmGatewayCredentialCipher
import org.hermesnative.client.feature.entry.data.EncryptedGatewayCredentialRecord
import org.hermesnative.client.feature.entry.domain.GatewayConnectionPersistenceException
import org.hermesnative.client.feature.entry.domain.GatewayCredentialStore
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AndroidKeyStoreGatewayCredentialStore(
    context: Context,
) : GatewayCredentialStore {
    private val credentialFile =
        File(
            context.applicationContext.noBackupFilesDir,
            CREDENTIAL_FILE_NAME,
        )
    private val atomicFile = AtomicFile(credentialFile)

    override fun load(): String? =
        synchronized(storageLock) {
            runCatching {
                val record = EncryptedGatewayCredentialRecord.decode(atomicFile.readFully())
                AesGcmGatewayCredentialCipher(::loadKey).decrypt(record)
            }.getOrNull()
        }

    override fun save(credential: String) {
        synchronized(storageLock) {
            val encryptedRecord =
                runCatching {
                    AesGcmGatewayCredentialCipher(::loadOrCreateKey).encrypt(credential).encode()
                }.getOrElse { error ->
                    throw GatewayConnectionPersistenceException(error)
                }
            val stream =
                try {
                    atomicFile.startWrite()
                } catch (error: IOException) {
                    throw GatewayConnectionPersistenceException(error)
                }
            try {
                stream.write(encryptedRecord)
                stream.flush()
                atomicFile.finishWrite(stream)
            } catch (error: IOException) {
                runCatching { atomicFile.failWrite(stream) }
                throw GatewayConnectionPersistenceException(error)
            }
        }
    }

    override fun clear() {
        synchronized(storageLock) {
            val credentialFiles =
                listOf(
                    credentialFile,
                    File("${credentialFile.path}.bak"),
                    File("${credentialFile.path}.new"),
                )
            val hasCredentialRecord = credentialFiles.any(File::exists)
            var failure: Throwable? = null
            credentialFiles.forEach { file ->
                if (file.exists() && !file.delete()) {
                    failure = IllegalStateException("Could not remove the Gateway credential record.")
                }
            }
            try {
                val keyStore = loadKeyStore()
                if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
            } catch (error: GeneralSecurityException) {
                if (hasCredentialRecord) failure = error
            } catch (error: IOException) {
                if (hasCredentialRecord) failure = error
            }
            failure?.let { throw GatewayConnectionPersistenceException(it) }
        }
    }

    private fun loadKey(): SecretKey =
        loadKeyStore().getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("Gateway credential key is unavailable.")

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = loadKeyStore()
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        ).setKeySize(KEY_SIZE_BITS)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }

    companion object {
        const val CREDENTIAL_FILE_NAME = "gateway_credential.enc"
        const val KEY_ALIAS = "gateway_credential_key_v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
        private val storageLock = Any()
    }
}
