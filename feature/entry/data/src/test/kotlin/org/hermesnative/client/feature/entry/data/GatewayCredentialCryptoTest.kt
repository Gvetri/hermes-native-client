package org.hermesnative.client.feature.entry.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Arrays
import javax.crypto.spec.SecretKeySpec

class GatewayCredentialCryptoTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    @Test
    fun encrypts_and_round_trips_a_credential_through_an_authenticated_record() {
        val cipher = AesGcmGatewayCredentialCipher { key }

        val record = cipher.encrypt("secure-token")
        val decoded = EncryptedGatewayCredentialRecord.decode(record.encode())

        assertEquals("secure-token", cipher.decrypt(decoded))
        assertEquals(EncryptedGatewayCredentialRecord.FORMAT_VERSION, decoded.version)
        assertTrue(decoded.initializationVector.isNotEmpty())
        assertTrue(!Arrays.equals("secure-token".toByteArray(), decoded.ciphertext))
    }

    @Test
    fun tampering_with_the_record_fails_authenticated_decryption() {
        val cipher = AesGcmGatewayCredentialCipher { key }
        val record = cipher.encrypt("secure-token")
        val tamperedCiphertext = record.ciphertext.clone().also { it[0] = (it[0].toInt() xor 1).toByte() }

        val failure =
            runCatching {
                cipher.decrypt(record.copy(ciphertext = tamperedCiphertext))
            }.exceptionOrNull()

        assertTrue(failure != null)
    }

    @Test
    fun record_encoding_preserves_the_iv_and_ciphertext_without_plaintext() {
        val cipher = AesGcmGatewayCredentialCipher { key }
        val record = cipher.encrypt("secure-token")
        val decoded = EncryptedGatewayCredentialRecord.decode(record.encode())

        assertArrayEquals(record.initializationVector, decoded.initializationVector)
        assertArrayEquals(record.ciphertext, decoded.ciphertext)
        assertTrue(!String(record.encode()).contains("secure-token"))
    }
}
