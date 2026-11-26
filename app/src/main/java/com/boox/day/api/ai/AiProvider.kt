package com.boox.day.api.ai

import com.boox.day.data.datastore.GlobalAppSettings
import com.boox.day.security.UserOpenRouterKey
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

@Serializable
enum class AiProvider(val displayName: String) {
    OPENROUTER("OpenRouter"),
    CHATGPT("ChatGPT");

    companion object {
        val current: AiProvider get() = GlobalAppSettings.current.aiProvider

        /** Used by UI entry points that can open settings when the selected provider needs attention. */
        val ready: Boolean get() = when (current) {
            OPENROUTER -> UserOpenRouterKey.hasKey
            CHATGPT -> ChatGptProvider.state?.active?.let { it.planEnabled && it.modelSlug != null } == true
        }

        /** Background OCR and audio require capabilities unique to the existing OpenRouter route. */
        val openRouterAvailable: Boolean get() = current == OPENROUTER && UserOpenRouterKey.hasKey

        fun allows(provider: AiProvider): Boolean =
            GlobalAppSettings.loaded && GlobalAppSettings.current.aiEnabled && current == provider

        internal suspend fun capture(): Selection = coroutineContext[Selection] ?: Selection(
            current, GlobalAppSettings.aiRoutingRevision, ChatGptProvider.state?.generation
        )
    }

    internal class Selection(
        private val provider: AiProvider,
        private val revision: Long,
        private val chatGptGeneration: Long?
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Selection>

        fun allows(requested: AiProvider): Boolean =
            provider == requested && revision == GlobalAppSettings.aiRoutingRevision &&
                (provider != CHATGPT || chatGptGeneration == ChatGptProvider.state?.generation)
    }
}
