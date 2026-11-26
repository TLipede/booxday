package com.boox.day.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.boox.day.api.ai.ChatGptConnections
import com.boox.day.api.ai.ChatGptStore
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Atomic AES-GCM storage under Android's no-backup directory. Never writes plaintext to disk. */
internal class ChatGptCredentialStore(context: Context) : ChatGptStore {
    private val file = File(context.noBackupFilesDir, "chatgpt-connections.v1").toPath()
    private val json = Json { ignoreUnknownKeys = true }

    override fun read(): ChatGptConnections? {
        val bytes = try {
            val size = Files.size(file)
            if (size !in (1 + IV_BYTES + TAG_BYTES).toLong()..MAX_BYTES) throw IOException("Invalid storage size")
            Files.readAllBytes(file)
        } catch (_: NoSuchFileException) {
            return null
        }
        require(bytes[0] == VERSION)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(TAG_BYTES * 8, bytes, 1, IV_BYTES))
        cipher.updateAAD(AAD)
        val decoded = cipher.doFinal(bytes, 1 + IV_BYTES, bytes.size - 1 - IV_BYTES)
        return try {
            json.decodeFromString(ChatGptConnections.serializer(), decoded.toString(Charsets.UTF_8))
        } finally {
            decoded.fill(0)
        }
    }

    override fun write(connections: ChatGptConnections) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(AAD)
        val plain = json.encodeToString(ChatGptConnections.serializer(), connections).toByteArray(Charsets.UTF_8)
        val encrypted = try {
            cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
        check(cipher.iv.size == IV_BYTES && encrypted.size + IV_BYTES + 1 <= MAX_BYTES)
        val pending = file.resolveSibling("chatgpt-connections.pending")
        try {
            pending.toFile().outputStream().use { stream ->
                stream.write(VERSION.toInt())
                stream.write(cipher.iv)
                stream.write(encrypted)
                stream.fd.sync()
            }
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(pending)
        }
    }

    private fun key(create: Boolean): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Credential encryption key unavailable" }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(spec)
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "booxday-chatgpt-connections-v1"
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val MAX_BYTES = 4L * 1024 * 1024
        val AAD = KEY_ALIAS.toByteArray(Charsets.US_ASCII)
    }
}
