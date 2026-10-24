package org.hermesnative.client.feature.entry.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Arrays
import javax.crypto.spec.SecretKeySpec

class HistoryCacheCryptoTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test
    fun encrypts_and_round_trips_a_record_blob_through_an_authenticated_record() {
        val cipher = AesGcmHistoryCacheCipher { key }
        val blob = "cached-transcript-bytes".toByteArray(StandardCharsets.UTF_8)

        val record = cipher.encrypt(blob)
        val decoded = EncryptedHistoryCacheRecord.decode(record.encode())

        assertArrayEquals(blob, cipher.decrypt(decoded))
        assertEquals(EncryptedHistoryCacheRecord.FORMAT_VERSION, decoded.version)
        assertTrue(decoded.initializationVector.isNotEmpty())
        assertTrue(!Arrays.equals(blob, decoded.ciphertext))
    }

    @Test
    fun tampering_with_the_record_fails_authenticated_decryption() {
        val cipher = AesGcmHistoryCacheCipher { key }
        val record = cipher.encrypt("cached-transcript-bytes".toByteArray(StandardCharsets.UTF_8))
        val tamperedCiphertext = record.ciphertext.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }

        val failure =
            runCatching {
                cipher.decrypt(record.copy(ciphertext = tamperedCiphertext))
            }.exceptionOrNull()

        assertTrue(failure != null)
    }

    @Test
    fun record_encoding_preserves_the_iv_and_ciphertext_without_plaintext() {
        val cipher = AesGcmHistoryCacheCipher { key }
        val plaintext = "the quick brown transcript".toByteArray(StandardCharsets.UTF_8)

        val record = cipher.encrypt(plaintext)
        val decoded = EncryptedHistoryCacheRecord.decode(record.encode())

        assertArrayEquals(record.initializationVector, decoded.initializationVector)
        assertArrayEquals(record.ciphertext, decoded.ciphertext)
        assertTrue(!String(record.encode(), StandardCharsets.UTF_8).contains("transcript"))
    }

    @Test
    fun a_record_from_the_credential_associated_data_does_not_decrypt_as_cache_content() {
        val cacheCipher = AesGcmHistoryCacheCipher { key }
        val cacheRecord = cacheCipher.encrypt("cached-transcript-bytes".toByteArray(StandardCharsets.UTF_8))
        val foreignCipher = AesGcmGatewayCredentialCipher { key }

        val failure =
            runCatching {
                String(cacheCipher.decrypt(cacheRecord), StandardCharsets.UTF_8)
                foreignCipher.decrypt(
                    EncryptedGatewayCredentialRecord(
                        version = EncryptedGatewayCredentialRecord.FORMAT_VERSION,
                        initializationVector = cacheRecord.initializationVector,
                        ciphertext = cacheRecord.ciphertext,
                    ),
                )
            }.exceptionOrNull()

        assertTrue(failure != null)
    }
}
