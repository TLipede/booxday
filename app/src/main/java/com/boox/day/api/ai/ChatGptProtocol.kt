package com.boox.day.api.ai

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.BufferedReader
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Sign in with ChatGPT for open-source apps and its public Responses API. */
internal object ChatGptProtocol {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
    const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    const val METADATA = "$ISSUER/.well-known/openid-configuration"
    const val JWKS = "$ISSUER/.well-known/jwks.json"
    const val RESOURCE = "https://api.openai.com/v1"
    const val CALLBACK_PATH = "/auth/callback"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    private const val DYNAMIC_CLIENT = "dynamic_agent_client"
    private const val SCOPE = "openid profile email offline_access resource.invoke $PLAN_SCOPE"
    private val clientPattern = Regex("[A-Za-z0-9_-]{1,256}")
    private val slugPattern = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,199}")
    private val gson = Gson()
    private val random = SecureRandom()

    class Attempt(
        val redirect: String,
        val verifier: String,
        val state: String,
        val nonce: String,
        val hostId: String,
        val clientId: String?,
        private val idTokenHint: String?,
        private val requestConsent: Boolean
    ) {
        fun authorizationUrl(): String = AUTHORIZE.toHttpUrl().newBuilder().apply {
            addQueryParameter("client_id", clientId ?: DYNAMIC_CLIENT)
            if (clientId == null) addQueryParameter("agent_name_hint", "BooxDay")
            addQueryParameter("ext_agent_host_id", hostId)
            addQueryParameter("response_type", "code")
            addQueryParameter("redirect_uri", redirect)
            addQueryParameter("scope", SCOPE)
            addQueryParameter("resource", RESOURCE)
            addQueryParameter("state", state)
            addQueryParameter("nonce", nonce)
            addQueryParameter("code_challenge_method", "S256")
            addQueryParameter("code_challenge", challenge(verifier))
            idTokenHint?.let { addQueryParameter("id_token_hint", it) }
            if (requestConsent) addQueryParameter("prompt", "consent")
        }.build().toString()

        /** Unrelated or malformed requests do not consume the listener. */
        fun callback(target: String): Callback? {
            val uri = runCatching { URI(target) }.getOrNull() ?: return null
            if (uri.isAbsolute || uri.rawAuthority != null || uri.rawFragment != null ||
                uri.rawPath != CALLBACK_PATH
            ) return null
            val query = uri.rawQuery ?: return null
            val fields = mutableMapOf<String, String>()
            for (part in query.split('&')) {
                val name = decode(part.substringBefore('=')) ?: return null
                val value = decode(part.substringAfter('=', "")) ?: return null
                if (fields.put(name, value) != null) return null
            }
            val returnedState = fields["state"] ?: return null
            if (!secureEquals(state, returnedState)) return null
            if (fields["error"] == "access_denied") return Callback.Rejected("ChatGPT plan consent was declined.")
            if (fields.containsKey("error")) return Callback.Rejected("ChatGPT authorization was unsuccessful.")

            val issued = fields["client_id"] ?: clientId
            val code = fields["code"]
            if (issued == null || !clientPattern.matches(issued) || issued == DYNAMIC_CLIENT ||
                (clientId != null && issued != clientId) || code.isNullOrBlank()
            ) return Callback.Rejected("ChatGPT returned an invalid authorization callback.")
            return Callback.Authorized(issued, code)
        }

        fun exchangeFields(callback: Callback.Authorized): Map<String, String> = mapOf(
            "grant_type" to "authorization_code",
            "client_id" to callback.clientId,
            "code" to callback.code,
            "code_verifier" to verifier,
            "redirect_uri" to redirect,
            "resource" to RESOURCE
        )
    }

    sealed interface Callback {
        class Authorized(val clientId: String, val code: String) : Callback
        class Rejected(val message: String) : Callback
    }

    fun newAttempt(
        port: Int,
        hostId: String,
        clientId: String? = null,
        idTokenHint: String? = null,
        requestConsent: Boolean = false
    ): Attempt {
        require(port in 1..65535)
        return Attempt(
            "http://127.0.0.1:$port$CALLBACK_PATH", secret(), secret(), secret(),
            hostId, clientId, idTokenHint, requestConsent
        )
    }

    private fun secret(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(random::nextBytes))

    fun secureEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))

    internal fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun decode(value: String): String? =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()

    fun requestBody(request: AiRequest, modelSlug: String): String {
        require(slugPattern.matches(modelSlug))
        require(!request.zeroDataRetention)
        val input = request.messages.map { message ->
            when (message) {
                is AiMessage.System -> mapOf("role" to "developer", "content" to message.content)
                is AiMessage.User -> mapOf("role" to "user", "content" to message.content)
                is AiMessage.Assistant -> mapOf("role" to "assistant", "content" to message.content)
                is AiMessage.UserWithImage -> {
                    val mime = if (message.imageBase64.startsWith("/9j/")) "image/jpeg" else "image/png"
                    mapOf(
                        "role" to "user",
                        "content" to listOf(
                            mapOf("type" to "input_text", "text" to message.text),
                            mapOf("type" to "input_image", "image_url" to "data:$mime;base64,${message.imageBase64}")
                        )
                    )
                }
                is AiMessage.UserWithAudio -> throw IllegalArgumentException("Audio is unsupported")
            }
        }
        val body = linkedMapOf<String, Any>(
            "model" to modelSlug, "input" to input, "store" to false, "stream" to true
        )
        // The subscription preview does not accept max_output_tokens or temperature.
        request.jsonSchema?.let { schema ->
            body["text"] = mapOf(
                "format" to mapOf("type" to "json_schema", "name" to schema.name,
                    "schema" to schema.schema, "strict" to schema.strict)
            )
        } ?: if (request.jsonOutput) {
            body["text"] = mapOf("format" to mapOf("type" to "json_object"))
        } else Unit
        return gson.toJson(body)
    }

    fun models(json: JsonObject): List<ChatGptModel> {
        val models = json.get("models")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
            ?: throw ChatGptFailure("ChatGPT returned an unexpected model catalog.")
        return models.mapNotNull { item ->
            val model = item.objectOrNull() ?: return@mapNotNull null
            if (model.string("visibility") != "list") return@mapNotNull null
            val slug = model.string("slug")?.takeIf(slugPattern::matches)
                ?: throw ChatGptFailure("ChatGPT returned an incomplete model listing.")
            val name = model.string("display_name")?.takeIf { it.isNotBlank() && it.length <= 200 }
                ?: throw ChatGptFailure("ChatGPT returned an incomplete model listing.")
            ChatGptModel(slug, name)
        }.distinctBy { it.slug }
    }

    /** A terminal completion event is mandatory. A refusal or incomplete stream never yields text. */
    fun readStream(reader: BufferedReader, model: String): AiResponse<String> {
        val text = StringBuilder()
        val event = StringBuilder()
        var refused = false
        while (true) {
            val line = reader.readLimitedLine(MAX_EVENT_CHARS) ?: break
            if (line.startsWith("data:")) {
                if (event.isNotEmpty()) event.append('\n')
                event.append(line.removePrefix("data:").removePrefix(" "))
                if (event.length > MAX_EVENT_CHARS) throw ChatGptFailure("ChatGPT returned an oversized event.")
            } else if (line.isEmpty() && event.isNotEmpty()) {
                val payload = event.toString()
                event.setLength(0)
                if (payload == "[DONE]") break
                val json = JsonParser.parseString(payload).asJsonObject
                when (json.string("type")) {
                    "response.output_text.delta" -> {
                        text.append(json.string("delta") ?: throw ChatGptFailure("ChatGPT returned invalid response text."))
                        if (text.length > MAX_EVENT_CHARS) throw ChatGptFailure("ChatGPT returned an oversized answer.")
                    }
                    "response.refusal.delta", "response.refusal.done" -> refused = true
                    "response.completed" -> {
                        val response = json.get("response").objectOrNull()
                        val usage = response?.get("usage").objectOrNull()?.let {
                            TokenUsage(it.int("input_tokens"), it.int("output_tokens"), it.int("total_tokens"))
                        }
                        if (refused || text.isBlank()) return chatGptError("ChatGPT returned no usable answer.")
                        return AiResponse.Success(text.toString(), response?.string("model") ?: model, usage)
                    }
                    "response.incomplete" -> return chatGptError("ChatGPT did not complete the answer.")
                    "response.failed", "error" -> {
                        val error = json.get("response").objectOrNull()?.get("error").objectOrNull()
                            ?: json.get("error").objectOrNull() ?: json
                        return ChatGptApi.inferenceError(null, error.string("code"))
                    }
                }
            }
        }
        return chatGptError("The ChatGPT stream ended before completion.")
    }

    private const val MAX_EVENT_CHARS = 2 * 1024 * 1024
}

internal data class ChatGptModel(val slug: String, val name: String)

internal fun JsonElement?.objectOrNull(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject
internal fun JsonObject.string(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

internal fun JsonObject.int(key: String): Int = long(key)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: 0
internal fun JsonObject.long(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()

internal fun BufferedReader.readLimitedLine(limit: Int, withinDeadline: () -> Boolean = { true }): String? {
    val line = StringBuilder()
    while (true) {
        if (!withinDeadline()) throw java.net.SocketTimeoutException()
        when (val ch = read()) {
            -1 -> return line.takeIf { it.isNotEmpty() }?.toString()
            '\n'.code -> return line.toString().removeSuffix("\r")
            else -> {
                if (line.length == limit) throw ChatGptFailure("ChatGPT returned an oversized line.")
                line.append(ch.toChar())
            }
        }
    }
}
