package com.boox.day.api.ai

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Call
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
internal class ChatGptProfile(
    val clientId: String,
    val subject: String? = null,
    val email: String? = null,
    val modelSlug: String? = null,
    val grant: ChatGptGrant? = null,
    val pendingRefresh: ChatGptGrant? = null
) {
    fun signedOut() = ChatGptProfile(clientId, subject, email, modelSlug)
    fun withModel(slug: String) = ChatGptProfile(clientId, subject, email, slug, grant, pendingRefresh)
    fun withGrant(value: ChatGptGrant, email: String? = this.email) =
        ChatGptProfile(clientId, subject, email, modelSlug, value)
}

@Serializable
internal class ChatGptConnections(
    val hostId: String = "urn:uuid:${UUID.randomUUID()}",
    val activeClientId: String? = null,
    val profiles: List<ChatGptProfile> = emptyList()
) {
    val active get() = profiles.firstOrNull { it.clientId == activeClientId }

    fun withProfile(profile: ChatGptProfile, activate: Boolean = false): ChatGptConnections {
        val updated = if (profiles.any { it.clientId == profile.clientId }) {
            profiles.map { if (it.clientId == profile.clientId) profile else it }
        } else profiles + profile
        return ChatGptConnections(hostId, if (activate) profile.clientId else activeClientId, updated)
    }
}

internal interface ChatGptStore {
    fun read(): ChatGptConnections?
    fun write(connections: ChatGptConnections)
}

/** Serializes changes to saved connections and refresh-token rotation. */
internal class ChatGptSession(
    private val store: ChatGptStore,
    private val api: ChatGptApi = ChatGptApi(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    data class Account(
        val clientId: String,
        val label: String,
        val connected: Boolean,
        val planEnabled: Boolean,
        val modelSlug: String?
    )

    data class State(
        val accounts: List<Account> = emptyList(),
        val activeClientId: String? = null,
        val generation: Long = 0,
        val ready: Boolean = false,
        val storageError: Boolean = false,
        val usageLimited: Boolean = false
    ) {
        val active get() = accounts.firstOrNull { it.clientId == activeClientId }
    }

    var state by mutableStateOf(State())
        private set

    private val mutex = Mutex()
    private val authorization = Mutex()
    private var connections: ChatGptConnections? = null
    private val activeCalls = mutableSetOf<Call>()
    private val limitedAccounts = mutableSetOf<String>()
    private val signingOut = ConcurrentHashMap.newKeySet<String>()

    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock { loadLocked() } }

    suspend fun signIn(clientId: String?, consent: Boolean, openBrowser: suspend (String) -> Unit) {
        if (!authorization.tryLock()) throw ChatGptFailure("A ChatGPT sign-in is already in progress.")
        try {
            withContext(Dispatchers.IO) {
                val (hostId, profile) = mutex.withLock {
                    val saved = loadLocked()
                    // Save the installation ID before the first authorization.
                    persist(saved)
                    val selected = saved.profiles.firstOrNull { it.clientId == clientId }
                    if (clientId != null && selected == null) throw changed()
                    saved.hostId to selected
                }
                val attempt: ChatGptProtocol.Attempt
                val callback = ChatGptLoopback().use { listener ->
                    attempt = ChatGptProtocol.newAttempt(
                        listener.port, hostId, profile?.clientId, profile?.grant?.idToken, consent
                    )
                    openBrowser(attempt.authorizationUrl())
                    listener.await(attempt)
                }
                if (callback is ChatGptProtocol.Callback.Rejected) throw ChatGptFailure(callback.message)
                callback as ChatGptProtocol.Callback.Authorized

                mutex.withLock {
                    var saved = loadLocked()
                    if (profile == null) {
                        if (saved.profiles.any { it.clientId == callback.clientId }) throw changed()
                        // Keep this registration even if the one-time code expires during exchange.
                        saved = saved.withProfile(ChatGptProfile(callback.clientId))
                        persist(saved)
                    } else if (saved.profiles.firstOrNull { it.clientId == profile.clientId }?.subject != profile.subject) {
                        throw changed()
                    }
                    val grant = api.exchange(attempt, callback)
                    val identity = api.verify(grant, callback.clientId, attempt.nonce)
                    if (profile?.subject != null && profile.subject != identity.subject) {
                        throw ChatGptFailure("This sign-in returned a different ChatGPT account. Choose Add account to connect it.")
                    }
                    val verified = ChatGptProfile(callback.clientId, identity.subject, identity.email,
                        profile?.modelSlug, grant)
                    limitedAccounts.remove(callback.clientId)
                    persist(saved.withProfile(verified, activate = true), changeSession = true)
                }
            }
        } finally {
            authorization.unlock()
        }
    }

    suspend fun selectAccount(clientId: String) = update {
        val profile = it.profiles.firstOrNull { profile -> profile.clientId == clientId } ?: throw changed()
        it.withProfile(profile, activate = true)
    }

    suspend fun resumeUsage(expected: State) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (state.generation != expected.generation) throw changed()
            state.activeClientId?.let(limitedAccounts::remove)
            state = state.copy(usageLimited = false)
        }
    }

    suspend fun selectModel(expected: State, model: ChatGptModel) = update {
        if (state.generation != expected.generation || it.active?.clientId != expected.activeClientId) throw changed()
        it.withProfile((it.active ?: throw changed()).withModel(model.slug))
    }

    private suspend fun update(transform: (ChatGptConnections) -> ChatGptConnections) = withContext(Dispatchers.IO) {
        mutex.withLock { persist(transform(loadLocked()), changeSession = true) }
    }

    /** Cancel outgoing work immediately. Retry remote revocation, then clear all local tokens. */
    suspend fun signOut(clientId: String): Boolean {
        if (!signingOut.add(clientId)) throw changed()
        try {
            return withContext(Dispatchers.IO) {
                mutex.withLock {
                    val saved = loadLocked()
                    val profile = saved.profiles.firstOrNull { it.clientId == clientId } ?: throw changed()
                    val isActive = profile == saved.active
                    if (isActive) cancelCalls()
                    withContext(NonCancellable) {
                        val renewable = profile.pendingRefresh?.refreshToken ?: profile.grant?.refreshToken
                        val revoked = try {
                            if (renewable != null) api.revoke(clientId, renewable) else profile.grant?.accessToken == null
                        } catch (_: Exception) {
                            false
                        }
                        limitedAccounts.remove(clientId)
                        persist(saved.withProfile(profile.signedOut()), changeSession = isActive)
                        revoked
                    }
                }
            }
        } finally {
            signingOut.remove(clientId)
        }
    }

    suspend fun discover(expected: State, allowed: () -> Boolean): List<ChatGptModel> = withContext(Dispatchers.IO) {
        val permitted = { isCurrent(expected.generation) && allowed() }
        val call = beginCall(expected.generation, permitted, forInference = false) { api.modelsCall(it, permitted) }
        try {
            api.models(call)
        } finally {
            endCall(call)
        }
    }

    suspend fun execute(
        model: AiModel.ChatGpt,
        request: AiRequest,
        allowed: () -> Boolean
    ): AiResponse<String> = try {
        requireRequest(model, request)
        val body = ChatGptProtocol.requestBody(request, model.id)
        withContext(Dispatchers.IO) {
            val permitted = { isCurrent(model.generation) && allowed() }
            val call = beginCall(model.generation, permitted, forInference = true) { api.responseCall(it, body, permitted) }
            try {
                val result = api.response(call, model.id)
                if (result is AiResponse.Error.ChatGpt && result.problem == AiProblem.CHATGPT_LIMIT) {
                    mutex.withLock {
                        if (model.generation == state.generation) {
                            state.activeClientId?.let(limitedAccounts::add)
                            state = state.copy(usageLimited = true)
                        }
                    }
                }
                if (!permitted()) throw changed()
                result
            } finally {
                endCall(call)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ChatGptFailure) {
        chatGptError(failure.message.orEmpty(), failure.problem)
    } catch (_: IOException) {
        chatGptError(AiProblem.CHATGPT_UNAVAILABLE.message, AiProblem.CHATGPT_UNAVAILABLE)
    } catch (_: RuntimeException) {
        chatGptError("ChatGPT returned unreadable data.")
    }

    private fun requireRequest(model: AiModel.ChatGpt, request: AiRequest) {
        if (request.zeroDataRetention) {
            throw ChatGptFailure(AiProblem.CHATGPT_INDEXING_PAUSED.message, AiProblem.CHATGPT_INDEXING_PAUSED)
        }
        if (request.messages.any { it is AiMessage.UserWithAudio }) {
            throw ChatGptFailure("ChatGPT plan usage doesn't support voice recordings. Use tablet speech recognition when available.")
        }
        if (!state.ready) throw ChatGptFailure("ChatGPT connections are loading. Try again.", AiProblem.CHATGPT_CONNECTION)
        if (state.generation != model.generation) throw changed()
        if (state.active?.planEnabled != true) {
            throw ChatGptFailure(AiProblem.CHATGPT_CONNECTION.message, AiProblem.CHATGPT_CONNECTION)
        }
        if (model.id.isBlank()) throw ChatGptFailure("Choose a ChatGPT model in AI Settings.")
        if (state.active?.modelSlug != model.id) throw changed()
    }

    private suspend fun beginCall(
        generation: Long,
        allowed: () -> Boolean,
        forInference: Boolean,
        create: (String) -> Call
    ): Call = mutex.withLock {
        val saved = loadLocked()
        if (generation != state.generation || !allowed()) throw changed()
        if (forInference && state.usageLimited) {
            throw ChatGptFailure(AiProblem.CHATGPT_LIMIT.message, AiProblem.CHATGPT_LIMIT)
        }
        val profile = saved.active ?: throw ChatGptFailure(AiProblem.CHATGPT_CONNECTION.message, AiProblem.CHATGPT_CONNECTION)
        val token = getAccessToken(saved, profile)
        currentCoroutineContext().ensureActive()
        if (generation != state.generation || !allowed()) throw changed()
        create(token).also { activeCalls.add(it) }
    }

    private suspend fun endCall(call: Call) {
        withContext(NonCancellable) { mutex.withLock { activeCalls.remove(call) } }
    }

    private suspend fun getAccessToken(saved: ChatGptConnections, profile: ChatGptProfile): String {
        val grant = profile.grant?.takeIf { it.hasPlanPermission }
            ?: throw ChatGptFailure(AiProblem.CHATGPT_CONNECTION.message, AiProblem.CHATGPT_CONNECTION)
        val time = now()
        if (profile.pendingRefresh == null) {
            val refreshAt = maxOf(grant.expiresAt - 60, grant.earliestRefreshAt ?: 0)
            if (time < minOf(grant.expiresAt, refreshAt)) return checkNotNull(grant.accessToken)
            if (time < grant.expiresAt && grant.refreshToken == null) return checkNotNull(grant.accessToken)
            if (grant.refreshToken == null) {
                throw ChatGptFailure("This ChatGPT session needs to be renewed in AI Settings.", AiProblem.CHATGPT_CONNECTION)
            }
            if ((grant.earliestRefreshAt ?: 0) > time) {
                throw ChatGptFailure("ChatGPT cannot renew this session yet. Try again later.", AiProblem.CHATGPT_UNAVAILABLE)
            }
        }
        // A refresh is single-use. Checkpoint a rotation before attempting remote key retrieval or
        // allowing cancellation, so a temporary JWKS failure never replays the old refresh token.
        return withContext(NonCancellable) {
            val latest = profile.pendingRefresh ?: try {
                api.refresh(profile.clientId, grant).also { rotated ->
                    persist(saved.withProfile(ChatGptProfile(profile.clientId, profile.subject, profile.email,
                        profile.modelSlug, grant, rotated)))
                }
            } catch (failure: ChatGptFailure) {
                if (failure.code in ChatGptApi.REFRESH_TERMINAL) {
                    persist(saved.withProfile(profile.signedOut()), changeSession = true)
                }
                throw failure
            }
            val identity = if (latest.idToken != null) api.verify(latest, profile.clientId, null) else null
            if (identity != null && identity.subject != profile.subject) {
                throw ChatGptFailure("The refreshed ChatGPT identity did not match this account.", AiProblem.CHATGPT_CONNECTION)
            }
            val updatedGrant = ChatGptGrant(latest.idToken ?: grant.idToken, latest.accessToken, latest.refreshToken,
                latest.scopes, latest.receivedAt, latest.expiresAt, latest.earliestRefreshAt)
            val updated = (connections ?: saved).withProfile(profile.withGrant(updatedGrant, identity?.email ?: profile.email))
            persist(updated)
            if (!updatedGrant.hasPlanPermission || updatedGrant.expiresAt <= now()) {
                throw ChatGptFailure(AiProblem.CHATGPT_CONNECTION.message, AiProblem.CHATGPT_CONNECTION)
            }
            checkNotNull(updatedGrant.accessToken)
        }
    }

    private fun isCurrent(generation: Long): Boolean {
        val current = state
        return generation == current.generation && current.activeClientId?.let { it !in signingOut } == true
    }

    private fun cancelCalls() {
        activeCalls.forEach(Call::cancel)
        activeCalls.clear()
    }

    private fun loadLocked(): ChatGptConnections {
        connections?.let { return it }
        return try {
            (store.read() ?: ChatGptConnections()).also { publish(it, false) }
        } catch (_: Exception) {
            storageFailure()
        }
    }

    private fun persist(value: ChatGptConnections, changeSession: Boolean = false) {
        try {
            store.write(value)
        } catch (_: Exception) {
            storageFailure()
        }
        publish(value, changeSession)
    }

    private fun publish(value: ChatGptConnections, changeSession: Boolean) {
        if (changeSession) {
            cancelCalls()
        }
        connections = value
        val accounts = value.profiles.mapIndexed { index, profile ->
            val suffix = profile.email ?: if (profile.subject == null) "Sign-in incomplete" else "ChatGPT account"
            Account(profile.clientId, "${index + 1}. $suffix", profile.grant != null,
                profile.grant?.hasPlanPermission == true, profile.modelSlug)
        }
        state = State(accounts, value.activeClientId, state.generation + if (changeSession) 1 else 0, ready = true,
            usageLimited = value.activeClientId in limitedAccounts)
    }

    private fun storageFailure(): Nothing {
        cancelCalls()
        connections = null
        state = State(generation = state.generation + 1, ready = true, storageError = true)
        throw ChatGptFailure("BooxDay could not access secure ChatGPT storage.", AiProblem.CHATGPT_CONNECTION)
    }

    private fun changed() = ChatGptFailure("Your AI connection changed. Start the action again.", AiProblem.CHATGPT_CONNECTION)
}
