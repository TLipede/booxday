package com.boox.day.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.boox.day.api.ai.AiProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The AI features that send the user's calendar to OpenRouter (Optimize My Day, called Plan My Day in
 * the code, Daily Summary, Weekly
 * Summary, Smart Suggestions; Task Breakdown keeps only tasks) first say so once (Oct 2026, owner's decision for Google's verification): what is
 * sent, that free models may keep it, and how to keep it private (ui/dialogs/CalendarAiNoticePopup.kt).
 * Nothing is enforced: the user's models and OpenRouter account settings decide where it goes.
 * Shown until the user taps Continue once; device-only, so a new install shows it again.
 * MainScreen hosts the popup and keeps raw drawing off while it is open.
 */
object CalendarAiNotice {
    private const val PREFS = "calendar_ai_notice"
    private fun seenKey(provider: AiProvider) = if (provider == AiProvider.OPENROUTER) "seen" else "seen_chatgpt"

    var provider by mutableStateOf(AiProvider.OPENROUTER)
        private set

    var isOpen by mutableStateOf(false)
        private set

    private var prefs: SharedPreferences? = null
    private var pendingAction: (() -> Unit)? = null
    private var pendingAnswer: CompletableDeferred<Boolean>? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun seen(provider: AiProvider): Boolean = prefs?.getBoolean(seenKey(provider), false) ?: false

    /** Runs [action] now when the notice was seen, otherwise after Continue. Main thread. */
    fun afterNotice(action: () -> Unit) {
        val selected = AiProvider.current
        if (seen(selected)) {
            action()
            return
        }
        pendingAnswer?.complete(false)
        pendingAnswer = null
        pendingAction = action
        provider = selected
        isOpen = true
    }

    /** True at once when the notice was seen; otherwise shows it and waits: true for Continue. */
    suspend fun awaitAllowed(): Boolean {
        val selected = AiProvider.current
        if (seen(selected)) return true
        val answer = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            pendingAnswer?.complete(false)
            pendingAction = null
            pendingAnswer = answer
            provider = selected
            isOpen = true
        }
        return answer.await()
    }

    /** Continue: remember it and go on with the feature. */
    fun onContinue() {
        val selected = provider
        if (selected == AiProvider.current) {
            prefs?.edit()?.putBoolean(seenKey(selected), true)?.apply()
            close(allowed = true)
        } else close(allowed = false)
    }

    /** Not Now, or AI Settings: the feature doesn't run; the notice shows again next time. */
    fun onCancel() = close(allowed = false)

    private fun close(allowed: Boolean) = onMain {
        isOpen = false
        val action = pendingAction
        val answer = pendingAnswer
        pendingAction = null
        pendingAnswer = null
        answer?.complete(allowed)
        if (allowed) action?.invoke()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post(block)
    }
}
