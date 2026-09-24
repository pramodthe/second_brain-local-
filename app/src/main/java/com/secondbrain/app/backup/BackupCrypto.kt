package com.secondbrain.app.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Versioned AES-256-GCM envelope with a PBKDF2-derived passphrase key. */
object BackupCrypto {
    private val magic = "SBRAIN01".toByteArray(Charsets.US_ASCII)
    private const val FORMAT_VERSION = 1
    private const val ITERATIONS = 250_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    fun encrypt(output: OutputStream, passphrase: CharArray, writePlaintext: (OutputStream) -> Unit) {
        require(passphrase.size >= 8) { "Use a passphrase with at least 8 characters" }
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val header = DataOutputStream(output)
        header.write(magic)
        header.writeInt(FORMAT_VERSION)
        header.writeInt(ITERATIONS)
        header.write(salt)
        header.write(iv)
        header.flush()

        val key = deriveKey(passphrase, salt, ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(magic)
        }
        CipherOutputStream(output, cipher).use(writePlaintext)
    }

    fun decrypt(input: InputStream, passphrase: CharArray, readPlaintext: (InputStream) -> Unit) {
        require(passphrase.size >= 8) { "Enter the backup passphrase" }
        val header = DataInputStream(input)
        val actualMagic = ByteArray(magic.size).also(header::readFully)
        require(actualMagic.contentEquals(magic)) { "This is not a Second Brain backup" }
        require(header.readInt() == FORMAT_VERSION) { "This backup version is not supported" }
        val iterations = header.readInt()
        require(iterations in 100_000..1_000_000) { "Invalid backup key parameters" }
        val salt = ByteArray(SALT_BYTES).also(header::readFully)
        val iv = ByteArray(IV_BYTES).also(header::readFully)
        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            updateAAD(magic)
        }
        CipherInputStream(input, cipher).use(readPlaintext)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
