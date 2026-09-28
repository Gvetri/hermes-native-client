package org.hermesnative.client.feature.entry.data

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedGatewayCredentialRecord(
    val version: Int,
    val initializationVector: ByteArray,
    val ciphertext: ByteArray,
) {
    fun encode(): ByteArray {
        require(version == FORMAT_VERSION)
        require(initializationVector.size in MIN_IV_SIZE..MAX_IV_SIZE)
        require(ciphertext.isNotEmpty())
        return ByteBuffer
            .allocate(HEADER_SIZE + initializationVector.size + ciphertext.size)
            .put(version.toByte())
            .put(initializationVector.size.toByte())
            .putInt(ciphertext.size)
            .put(initializationVector)
            .put(ciphertext)
            .array()
    }

    companion object {
        const val FORMAT_VERSION = 1
        private const val HEADER_SIZE = 1 + 1 + Int.SIZE_BYTES
        private const val MIN_IV_SIZE = 12
        private const val MAX_IV_SIZE = 16

        fun decode(bytes: ByteArray): EncryptedGatewayCredentialRecord {
            require(bytes.size >= HEADER_SIZE) { "Invalid Gateway credential record." }
            val buffer = ByteBuffer.wrap(bytes)
            val version = buffer.get().toInt() and 0xff
            val ivSize = buffer.get().toInt() and 0xff
            val ciphertextSize = buffer.int
            require(version == FORMAT_VERSION) { "Unsupported Gateway credential record." }
            require(ivSize in MIN_IV_SIZE..MAX_IV_SIZE) { "Invalid Gateway credential record." }
            require(ciphertextSize > 0 && ciphertextSize == buffer.remaining() - ivSize) {
                "Invalid Gateway credential record."
            }
            val initializationVector = ByteArray(ivSize)
            buffer.get(initializationVector)
            val ciphertext = ByteArray(ciphertextSize)
            buffer.get(ciphertext)
            return EncryptedGatewayCredentialRecord(version, initializationVector, ciphertext)
        }
    }
}

class AesGcmGatewayCredentialCipher(
    private val keyProvider: () -> SecretKey,
) {
    fun encrypt(credential: String): EncryptedGatewayCredentialRecord {
        require(credential.isNotBlank())
        val plaintext = credential.toByteArray(StandardCharsets.UTF_8)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
            cipher.updateAAD(ASSOCIATED_DATA)
            EncryptedGatewayCredentialRecord(
                version = EncryptedGatewayCredentialRecord.FORMAT_VERSION,
                initializationVector = cipher.iv,
                ciphertext = cipher.doFinal(plaintext),
            )
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }

    fun decrypt(record: EncryptedGatewayCredentialRecord): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyProvider(),
            GCMParameterSpec(TAG_LENGTH_BITS, record.initializationVector),
        )
        cipher.updateAAD(ASSOCIATED_DATA)
        val plaintext = cipher.doFinal(record.ciphertext)
        return try {
            String(plaintext, StandardCharsets.UTF_8).also { credential ->
                require(credential.isNotBlank()) { "Invalid Gateway credential record." }
            }
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        val ASSOCIATED_DATA = "org.hermesnative.client.gateway-credential.v1".toByteArray(StandardCharsets.UTF_8)
    }
}
