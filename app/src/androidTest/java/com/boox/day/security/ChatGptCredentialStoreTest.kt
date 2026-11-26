package com.boox.day.security

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.boox.day.api.ai.ChatGptConnections
import com.boox.day.api.ai.ChatGptGrant
import com.boox.day.api.ai.ChatGptProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class ChatGptCredentialStoreTest {
    @Test fun encryptsOnDiskRejectsTamperingAndRemovesSession() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val temp = Files.createTempDirectory(app.cacheDir.toPath(), "chatgpt-test-").toFile()
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = temp
        }
        try {
            val store = ChatGptCredentialStore(context)
            val profile = ChatGptProfile("oaiapp_test", "test-subject", "account@example.com", "gpt-test",
                ChatGptGrant("synthetic-id", "synthetic-access", "synthetic-refresh",
                    emptySet(), 1_800_000_000, 1_800_003_600))
            val state = ChatGptConnections().withProfile(profile, activate = true)
            store.write(state)

            val encrypted = temp.listFiles()!!.single()
            val bytes = encrypted.readBytes()
            val disk = bytes.toString(Charsets.ISO_8859_1)
            for (secret in listOf("synthetic-id", "synthetic-access", "synthetic-refresh", "account@example.com")) {
                assertFalse(disk.contains(secret))
            }
            assertEquals("synthetic-refresh", ChatGptCredentialStore(context).read()?.active?.grant?.refreshToken)

            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            encrypted.writeBytes(bytes)
            assertTrue(runCatching { store.read() }.isFailure)

            store.write(state.withProfile(profile.signedOut()))
            assertNull(store.read()?.active?.grant)
            assertEquals(profile.clientId, store.read()?.active?.clientId)
        } finally {
            temp.deleteRecursively()
        }
    }
}
