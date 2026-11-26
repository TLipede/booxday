package com.boox.day.api.ai

import com.boox.day.api.ModelConfigProvider
import com.boox.day.data.datastore.AppSettings
import com.boox.day.data.datastore.GlobalAppSettings
import com.boox.day.ocr.aiReadWaitReason
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderRoutingTest {
    private val original = GlobalAppSettings.current
    private val originalLoaded = GlobalAppSettings.loaded

    @After fun restore() {
        GlobalAppSettings.update(original)
        GlobalAppSettings.loaded = originalLoaded
    }

    @Test fun `existing settings keep the OpenRouter provider`() {
        assertEquals(AiProvider.OPENROUTER, Json.decodeFromString<AppSettings>("""{"version":1}""").aiProvider)
    }

    @Test fun `selected ChatGPT returns no OpenRouter models and blocks a stale OpenRouter request`() = runBlocking {
        select(AiProvider.CHATGPT)
        val model = ModelConfigProvider.getVisionFallbackChain().single()
        assertTrue(model is AiModel.ChatGpt)
        assertFalse(AiProvider.allows(AiProvider.OPENROUTER))
        val staleOpenRouter = AiRequest(AiModel.Gpt5Nano, listOf(AiMessage.User("private")))
        val result = AiClient.execute(staleOpenRouter, promptForKey = false)
        assertTrue(result is AiResponse.Error)
        assertTrue(result.errorOrNull()?.message.orEmpty().contains("changed"))
        val waiting = aiReadWaitReason(aiEnabled = true, hasKey = true, provider = AiProvider.CHATGPT) {
            error("ChatGPT indexing should stop before quota checks")
        }
        assertEquals(AiProblem.CHATGPT_INDEXING_PAUSED, waiting)
    }

    @Test fun `an operation cannot switch providers between stages or resume after switching back`() = runBlocking {
        select(AiProvider.OPENROUTER)
        withContext(AiProvider.capture()) {
            select(AiProvider.CHATGPT)
            val chatModel = ModelConfigProvider.getTextFallbackChain().single()
            val chat = AiClient.execute(AiRequest(chatModel, listOf(AiMessage.User("private"))), promptForKey = false)
            assertTrue(chat.errorOrNull()?.message.orEmpty().contains("changed"))
            select(AiProvider.OPENROUTER)
            val openRouter = AiClient.execute(AiRequest(AiModel.Gpt5Nano, listOf(AiMessage.User("private"))), promptForKey = false)
            assertTrue(openRouter.errorOrNull()?.message.orEmpty().contains("changed"))
        }
    }

    @Test fun `master switch prevents dispatch even with a selected model`() = runBlocking {
        select(AiProvider.CHATGPT, enabled = false)
        val model = ModelConfigProvider.getTextFallbackChain().single()
        val result = AiClient.execute(AiRequest(model, listOf(AiMessage.User("private"))), promptForKey = false)
        assertEquals(AiProblem.AI_OFF.message, result.errorOrNull()?.message)
    }

    private fun select(provider: AiProvider, enabled: Boolean = true) {
        GlobalAppSettings.update(GlobalAppSettings.current.copy(aiProvider = provider, aiEnabled = enabled))
        GlobalAppSettings.loaded = true
    }
}
