package com.boox.day.api.ai

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ChatGptFailure(
    message: String,
    val problem: AiProblem = AiProblem.MODEL_FAILED,
    val code: String? = null
) : Exception(message)

internal fun chatGptError(message: String, problem: AiProblem = AiProblem.MODEL_FAILED, status: Int? = null) =
    AiResponse.Error.ChatGpt(message, problem, status)

@Serializable
internal class ChatGptGrant(
    val idToken: String?,
    val accessToken: String?,
    val refreshToken: String?,
    val scopes: Set<String>,
    val receivedAt: Long,
    val expiresAt: Long,
    val earliestRefreshAt: Long? = null
) {
    val hasPlanPermission: Boolean
        get() = ChatGptProtocol.PLAN_SCOPE in scopes && "resource.invoke" in scopes && accessToken != null
}

internal class ChatGptApi(
    private val http: OkHttpClient = httpClient(),
    private val now: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    suspend fun exchange(attempt: ChatGptProtocol.Attempt, callback: ChatGptProtocol.Callback.Authorized): ChatGptGrant =
        tokenRequest(attempt.exchangeFields(callback), null)

    suspend fun refresh(clientId: String, grant: ChatGptGrant): ChatGptGrant = tokenRequest(
        mapOf(
            "grant_type" to "refresh_token",
            "client_id" to clientId,
            "refresh_token" to checkNotNull(grant.refreshToken),
            "resource" to ChatGptProtocol.RESOURCE
        ), grant.scopes
    )

    private suspend fun tokenRequest(fields: Map<String, String>, previousScopes: Set<String>?): ChatGptGrant =
        consume(form(ChatGptProtocol.TOKEN, fields)) { response ->
            if (!response.isSuccessful) {
                val json = response.errorJson()
                val code = json?.string("error") ?: json?.get("error").objectOrNull()?.string("code")
                if (code in REFRESH_TERMINAL && previousScopes != null) {
                    throw ChatGptFailure("Your ChatGPT session expired. Sign in again in AI Settings.",
                        AiProblem.CHATGPT_CONNECTION, code)
                }
                val message = when (code) {
                    "invalid_grant" -> "This ChatGPT sign-in expired. Start again in AI Settings."
                    "invalid_client" -> "OpenAI did not accept this ChatGPT registration. Add a new account in AI Settings."
                    else -> "ChatGPT authorization returned HTTP ${response.code}. Try again."
                }
                throw ChatGptFailure(message, AiProblem.CHATGPT_CONNECTION)
            }
            val json = response.json()
            val scope = json.string("scope")
            if (json.get("scope")?.isJsonNull == false && scope == null) {
                throw ChatGptFailure("ChatGPT returned invalid permissions.", AiProblem.CHATGPT_CONNECTION)
            }
            val scopes = scope?.split(' ')?.filter(String::isNotBlank)?.toSet() ?: previousScopes.orEmpty()
            val access = json.string("access_token")?.takeIf { BEARER.matches(it) }
            val refresh = json.string("refresh_token")?.takeIf(String::isNotBlank)
            val idToken = json.string("id_token")?.takeIf(String::isNotBlank)
            val expiresIn = json.long("expires_in")?.takeIf { it in 1..86_400 }
            val needsRefresh = previousScopes != null || "offline_access" in scopes
            if ((json.has("id_token") && idToken == null) ||
                (needsRefresh && refresh == null) ||
                ((needsRefresh || ChatGptProtocol.PLAN_SCOPE in scopes) && access == null) ||
                (access != null && (expiresIn == null || !json.string("token_type").equals("Bearer", true)))
            ) throw ChatGptFailure("ChatGPT returned incomplete credentials.", AiProblem.CHATGPT_CONNECTION)
            val receivedAt = now()
            val earliest = json.long("earliest_refresh_at")
                ?: json.string("earliest_refresh_at")?.let { value ->
                    value.toLongOrNull() ?: runCatching { java.time.Instant.parse(value).epochSecond }.getOrNull()
                }
            ChatGptGrant(idToken, access, refresh, scopes, receivedAt, receivedAt + (expiresIn ?: 0), earliest)
        }

    suspend fun verify(grant: ChatGptGrant, clientId: String, nonce: String?): ChatGptIdentity.Verified {
        val idToken = grant.idToken ?: throw ChatGptFailure("ChatGPT did not return a verifiable identity.")
        val keys = consume(Request.Builder().url(ChatGptProtocol.JWKS).build()) {
            if (!it.isSuccessful) throw IOException("ChatGPT identity keys are unavailable")
            it.json()
        }
        return ChatGptIdentity.verify(idToken, keys, clientId, nonce, grant.accessToken.orEmpty(), grant.receivedAt)
    }

    suspend fun revoke(clientId: String, refreshToken: String): Boolean {
        val endpoint = try {
            consume(Request.Builder().url(ChatGptProtocol.METADATA).build()) {
                if (!it.isSuccessful) throw IOException("ChatGPT discovery is unavailable")
                val metadata = it.json()
                metadata.string("revocation_endpoint")?.toHttpUrlOrNull()?.takeIf { url ->
                    metadata.string("issuer") == ChatGptProtocol.ISSUER && url.isHttps &&
                        url.host == "auth.openai.com" && url.port == 443 && url.username.isEmpty() &&
                        url.password.isEmpty() && url.query == null && url.fragment == null &&
                        url.encodedPath == "/api/accounts/oauth/revoke"
                }?.toString()
            }
        } catch (_: IOException) {
            null
        } ?: return false
        val request = form(endpoint, mapOf("token" to refreshToken, "token_type_hint" to "refresh_token", "client_id" to clientId))
        repeat(2) { attempt ->
            val status = try {
                consume(request) { it.code }
            } catch (_: IOException) {
                503
            }
            if (status == 200) return true
            if (status !in 500..599) return false
            if (attempt == 0) delay(750)
        }
        return false
    }

    fun modelsCall(accessToken: String, allowed: () -> Boolean): Call = http.newCall(
        resourceRequest("models", accessToken, allowed).header("Accept", "application/json").build()
    )

    fun responseCall(accessToken: String, body: String, allowed: () -> Boolean): Call = http.newCall(
        resourceRequest("responses", accessToken, allowed).header("Accept", "text/event-stream")
            .post(OneShotBody(body.toRequestBody(JSON))).build()
    )

    suspend fun models(call: Call): List<ChatGptModel> = consume(call) { response ->
        if (!response.isSuccessful) {
            val error = response.error()
            throw ChatGptFailure(error.message, error.problem)
        }
        ChatGptProtocol.models(response.json())
    }

    suspend fun response(call: Call, model: String): AiResponse<String> = consume(call) { response ->
        if (!response.isSuccessful) return@consume response.error()
        val body = response.body
        if (body?.contentType()?.let { it.type == "text" && it.subtype == "event-stream" } != true) {
            return@consume chatGptError("ChatGPT returned an unexpected response format.")
        }
        body.charStream().buffered().use { ChatGptProtocol.readStream(it, model) }
    }

    private fun Response.error() = inferenceError(code, errorJson()?.get("error").objectOrNull()?.string("code"))

    private fun Response.json(): JsonObject {
        val source = body?.source() ?: throw ChatGptFailure("ChatGPT returned an empty response.")
        if (source.request(MAX_JSON_BYTES + 1)) throw ChatGptFailure("ChatGPT returned an oversized response.")
        return try {
            JsonParser.parseString(source.readUtf8()).asJsonObject
        } catch (_: RuntimeException) {
            throw ChatGptFailure("ChatGPT returned unreadable data.")
        }
    }

    private fun Response.errorJson(): JsonObject? = try {
        json()
    } catch (_: ChatGptFailure) {
        null
    }

    private class DispatchPermission(val allowed: () -> Boolean)

    // OkHttp may repeat replayable POSTs after a follow-up response even with connection retries off.
    private class OneShotBody(private val body: RequestBody) : RequestBody() {
        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
        override fun isOneShot() = true
    }

    private fun resourceRequest(path: String, token: String, allowed: () -> Boolean) =
        Request.Builder().url("${ChatGptProtocol.RESOURCE}/$path")
            .header("Authorization", "Bearer $token")
            .tag(DispatchPermission::class.java, DispatchPermission(allowed))

    private fun form(url: String, fields: Map<String, String>): Request {
        val body = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        return Request.Builder().url(url).post(OneShotBody(body)).build()
    }

    private suspend fun <T> consume(request: Request, parse: (Response) -> T): T =
        consume(http.newCall(request).apply { timeout().timeout(20, TimeUnit.SECONDS) }, parse)

    // Keep the call cancellable through response parsing, including the entire SSE stream.
    private suspend fun <T> consume(call: Call, parse: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use(parse)
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                    return
                }
                continuation.resume(result)
            }
        })
    }

    companion object {
        private const val MAX_JSON_BYTES = 2L * 1024 * 1024
        private val BEARER = Regex("[A-Za-z0-9._~+/-]+=*")
        private val JSON = "application/json".toMediaType()
        internal val REFRESH_TERMINAL = setOf("invalid_grant", "invalid_refresh_token", "token_expired",
            "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")

        fun httpClient(): OkHttpClient = OkHttpClient.Builder()
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                if (request.tag(DispatchPermission::class.java)?.allowed?.invoke() == false) {
                    throw IOException("AI provider or connection changed")
                }
                chain.proceed(request)
            }
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .build()

        fun inferenceError(status: Int?, code: String?): AiResponse.Error.ChatGpt = when (code) {
            "subscription_sharing_usage_limit_exceeded" ->
                chatGptError(AiProblem.CHATGPT_LIMIT.message, AiProblem.CHATGPT_LIMIT, status)
            "subscription_sharing_user_not_eligible" ->
                chatGptError("This ChatGPT account or workspace is ineligible for plan usage.", AiProblem.CHATGPT_CONNECTION, status)
            "subscription_sharing_unsupported_capability" ->
                chatGptError("The selected ChatGPT model does not support this input or output format.", status = status)
            "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" ->
                chatGptError(AiProblem.CHATGPT_UNAVAILABLE.message, AiProblem.CHATGPT_UNAVAILABLE, status)
            "subscription_sharing_invalid_user", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" ->
                chatGptError(AiProblem.CHATGPT_CONNECTION.message, AiProblem.CHATGPT_CONNECTION, status)
            "subscription_sharing_route_not_supported" ->
                chatGptError("The ChatGPT subscription route is unavailable for this request.", status = status)
            else -> when (status) {
                401 -> chatGptError(AiProblem.CHATGPT_CONNECTION.message, AiProblem.CHATGPT_CONNECTION, status)
                403 -> chatGptError("ChatGPT refused this request under its account, workspace or region policy.", status = status)
                429 -> chatGptError("ChatGPT is rate limiting requests. Try again later.", AiProblem.CHATGPT_LIMIT, status)
                400 -> chatGptError("ChatGPT rejected this input or output format. Try a compatible model in AI Settings.", status = status)
                404 -> chatGptError("That ChatGPT model is unavailable. Refresh models in AI Settings.", status = status)
                in 500..599 -> chatGptError(AiProblem.CHATGPT_UNAVAILABLE.message, AiProblem.CHATGPT_UNAVAILABLE, status)
                null -> chatGptError("ChatGPT could not complete the request.")
                else -> chatGptError("ChatGPT returned HTTP $status.", status = status)
            }
        }
    }
}
