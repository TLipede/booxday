package com.boox.day.api.ai

import android.content.Context
import com.boox.day.security.ChatGptCredentialStore

internal object ChatGptProvider {
    @Volatile
    private var runtime: ChatGptSession? = null

    fun init(context: Context) {
        runtime = ChatGptSession(ChatGptCredentialStore(context.applicationContext))
    }

    val session: ChatGptSession? get() = runtime
    val state: ChatGptSession.State? get() = runtime?.state

    fun selectedModel(): AiModel.ChatGpt {
        val snapshot = state
        val slug = snapshot?.active?.modelSlug.orEmpty()
        return AiModel.ChatGpt(slug, snapshot?.generation ?: 0)
    }
}
