package com.secondbrain.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class BackupCryptoTest {

    @Test
    fun encryptsAndAuthenticatesRoundTrip() {
        val plaintext = "private notes and graph evidence".toByteArray()
        val encrypted = ByteArrayOutputStream()

        BackupCrypto.encrypt(encrypted, "correct horse battery".toCharArray()) { output ->
            output.write(plaintext)
        }

        assertFalse(encrypted.toByteArray().containsSubsequence(plaintext))
        val restored = ByteArrayOutputStream()
        BackupCrypto.decrypt(
            ByteArrayInputStream(encrypted.toByteArray()),
            "correct horse battery".toCharArray()
        ) { input -> input.copyTo(restored) }
        assertArrayEquals(plaintext, restored.toByteArray())
    }

    @Test
    fun wrongPassphraseCannotDecryptBackup() {
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(encrypted, "right-passphrase".toCharArray()) { it.write("secret".toByteArray()) }

        assertThrows(Exception::class.java) {
            BackupCrypto.decrypt(
                ByteArrayInputStream(encrypted.toByteArray()),
                "wrong-passphrase".toCharArray()
            ) { it.readBytes() }
        }
    }

    @Test
    fun modifiedCiphertextCannotDecryptBackup() {
        val encrypted = ByteArrayOutputStream()
        BackupCrypto.encrypt(encrypted, "right-passphrase".toCharArray()) { it.write("secret".toByteArray()) }
        val damaged = encrypted.toByteArray().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }

        assertThrows(Exception::class.java) {
            BackupCrypto.decrypt(
                ByteArrayInputStream(damaged),
                "right-passphrase".toCharArray()
            ) { it.readBytes() }
        }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return (0..size - needle.size).any { start ->
            needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
    }
}
