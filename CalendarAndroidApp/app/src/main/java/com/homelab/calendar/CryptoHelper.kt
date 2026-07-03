package com.homelab.calendar

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {
    private const val PASSWORD = "my_secure_decryption_key_1237"

    private fun getKey(password: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(password.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    fun decrypt(encryptedBase64: String): String {
        val combined = Base64.decode(encryptedBase64, Base64.DEFAULT)
        if (combined.size < 16) {
            throw IllegalArgumentException("Invalid encrypted payload size")
        }
        val ivBytes = combined.sliceArray(0 until 16)
        val ciphertextBytes = combined.sliceArray(16 until combined.size)

        val keySpec = getKey(PASSWORD)
        val ivSpec = IvParameterSpec(ivBytes)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)

        val decryptedBytes = cipher.doFinal(ciphertextBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    fun encrypt(plainText: String): String {
        val keySpec = getKey(PASSWORD)

        // Generate random 16-byte IV
        val iv = ByteArray(16)
        SecureRandom().nextBytes(iv)
        val ivSpec = IvParameterSpec(iv)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)

        val ciphertextBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        // Combine IV + ciphertext and base64 encode (matching Python format)
        val combined = iv + ciphertextBytes
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }
}
