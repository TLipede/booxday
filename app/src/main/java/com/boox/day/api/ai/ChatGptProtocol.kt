package com.boox.day.api.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Protocol helpers for OpenAI's documented open-source ChatGPT plan usage flow.
 * No credentials are persisted here, and no provider fallback is performed.
 * Authentication orchestration must validate the ID token before handing off an access token.
 */
internal object ChatGptProtocol {
    const val AUTHORIZE = "https://auth.openai.com/api/accounts/authorize"
    const val TOKEN = "https://auth.openai.com/api/accounts/oauth/token"
    const val RESOURCE = "https://api.openai.com/v1"
    const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    private val gson = Gson()

    class Attempt(
        val redirect: String,
        val verifier: String,
        val state: String,
        val nonce: String,
        val hostId: String,
        val clientId: String? = null
    ) {
        init {
            val uri = URI(redirect)
            require(uri.scheme == "http" && uri.host == "127.0.0.1" &&
                uri.port in 1..65535 && uri.path == "/auth/callback" &&
                uri.userInfo == null && uri.query == null && uri.fragment == null)
        }

        fun authorizationUrl(): String {
            val params = linkedMapOf(
                "client_id" to (clientId ?: "dynamic_agent_client"),
                "ext_agent_host_id" to hostId,
                "response_type" to "code",
                "redirect_uri" to redirect,
                "scope" to SCOPE,
                "resource" to RESOURCE,
                "state" to state,
                "nonce" to nonce,
                "code_challenge_method" to "S256",
                "code_challenge" to challenge(verifier)
            )
            if (clientId == null) params["agent_name_hint"] = "BooxDay"
            return AUTHORIZE + "?" + params.entries.joinToString("&") {
                encode(it.key) + "=" + encode(it.value)
            }
        }

        fun exchangeFields(rawQuery: String): Map<String, String> {
            val fields = mutableMapOf<String, String>()
            for (part in rawQuery.split('&')) {
                val name = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                require(fields.put(name, value) == null) { "Duplicate callback parameter" }
            }
            require(fields["state"] == state) { "Authorization state mismatch" }
            require("error" !in fields) { "Authorization denied" }
            val issued = fields["client_id"] ?: clientId
            require(!issued.isNullOrBlank() && issued != "dynamic_agent_client")
            require(clientId == null || issued == clientId)
            val code = fields["code"]
            require(!code.isNullOrBlank())
            return mapOf("grant_type" to "authorization_code", "client_id" to issued,
                "code" to code, "code_verifier" to verifier, "redirect_uri" to redirect,
                "resource" to RESOURCE)
        }
    }

    fun newAttempt(redirect: String, hostId: String, clientId: String? = null): Attempt =
        Attempt(redirect, secret(), secret(), secret(), hostId, clientId)

    private fun secret(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    internal fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Reject unsupported audio and ZDR instead of weakening the user's request. */
    fun requestBody(request: AiRequest, modelSlug: String): String {
        require(modelSlug.isNotBlank())
        require(!request.zeroDataRetention) { "ChatGPT plan usage cannot guarantee OpenRouter ZDR routing" }
        val input = request.messages.map { msg ->
            when (msg) {
                is AiMessage.System -> mapOf("role" to "system", "content" to msg.content)
                is AiMessage.User -> mapOf("role" to "user", "content" to msg.content)
                is AiMessage.Assistant -> mapOf("role" to "assistant", "content" to msg.content)
                is AiMessage.UserWithImage -> {
                    val mime = if (msg.imageBase64.startsWith("/9j/")) "image/jpeg" else "image/png"
                    mapOf("role" to "user", "content" to listOf(
                        mapOf("type" to "input_text", "text" to msg.text),
                        mapOf("type" to "input_image", "image_url" to "data:$mime;base64,${msg.imageBase64}")
                    ))
                }
                is AiMessage.UserWithAudio -> throw IllegalArgumentException("Audio input is not enabled for ChatGPT")
            }
        }
        val body = linkedMapOf<String, Any>("model" to modelSlug, "input" to input,
            "store" to false, "stream" to true)
        request.config.maxTokens?.let { body["max_output_tokens"] = it }
        val schema = request.jsonSchema
        if (schema != null) {
            body["text"] = mapOf("format" to mapOf("type" to "json_schema", "name" to schema.name,
                "schema" to schema.schema, "strict" to schema.strict))
        } else if (request.jsonOutput) {
            body["text"] = mapOf("format" to mapOf("type" to "json_object"))
        }
        return gson.toJson(body)
    }

    /** Do not return partial text after a failed, incomplete or disconnected stream. */
    fun readStream(reader: BufferedReader, model: String): AiResponse<String> {
        val text = StringBuilder()
        val event = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.startsWith("data:")) {
                if (event.isNotEmpty()) event.append('\n')
                event.append(line.removePrefix("data:").trimStart())
            } else if (line.isEmpty() && event.isNotEmpty()) {
                val payload = event.toString()
                event.setLength(0)
                if (payload == "[DONE]") break
                val json = JsonParser.parseString(payload).asJsonObject
                when (json.get("type")?.asString) {
                    "response.output_text.delta" -> text.append(json.get("delta").asString)
                    "response.completed" -> return if (text.isBlank()) AiResponse.Error.NoContent()
                        else AiResponse.Success(text.toString(), model, null)
                    "response.incomplete" -> return AiResponse.Error.NoContent("ChatGPT response incomplete")
                    "response.failed", "error" -> return AiResponse.Error.ApiError(null, "ChatGPT response failed")
                }
            }
        }
        return AiResponse.Error.NoContent("ChatGPT stream ended before completion")
    }
}
