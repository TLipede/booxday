package com.boox.day.api.ai

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit

class ChatGptSessionIntegrationTest {
    private val signingKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private var time = 1_800_000_000L
    private val gson = Gson()
    private val clientId = "oaiapp_synthetic"
    private val scopes = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    private val store = MemoryStore()

    @Test fun `sign in discover stream rotate across restart and revoke`() = runBlocking {
        MockWebServer().use { server ->
            val api = api(server)
            var session = ChatGptSession(store, api) { time }
            session.load()
            signIn(session, server)
            assertTrue(session.state.active!!.planEnabled)
            assertEquals("test@example.com", store.saved?.active?.email)
            val token = server.request()
            assertEquals("/api/accounts/oauth/token", token.path)
            val fields = token.body.readUtf8()
            assertTrue(fields.contains("client_id=$clientId"))
            assertTrue(fields.contains("code_verifier="))
            assertFalse(fields.contains("scope="))
            assertEquals("/.well-known/jwks.json", server.request().path)

            server.enqueue(catalog())
            val model = session.discover(session.state) { true }.single()
            val modelsRequest = server.request()
            assertEquals("/v1/models", modelsRequest.path)
            assertEquals("Bearer access-1", modelsRequest.getHeader("Authorization"))
            assertEquals("Synthetic model", model.name)
            session.selectModel(session.state, model)

            server.enqueue(stream())
            val route = AiModel.ChatGpt(model.slug, session.state.generation)
            val request = AiRequest(route, listOf(AiMessage.User("Hello")))
            assertEquals("hello", session.execute(route, request) { true }.getOrNull())
            val inference = server.request()
            assertEquals("/v1/responses", inference.path)
            assertEquals("Bearer access-1", inference.getHeader("Authorization"))
            assertTrue(inference.body.readUtf8().contains("\"stream\":true"))

            // A JWKS outage after rotation must preserve the replacement, even across process restart.
            time += 3_555
            server.enqueue(json(grant("access-2", "refresh-2", identity(nonce = null), scope = null)))
            server.enqueue(MockResponse().setResponseCode(503))
            assertTrue(session.execute(route, request) { true } is AiResponse.Error)
            val refresh = server.request()
            assertEquals("/api/accounts/oauth/token", refresh.path)
            assertTrue(refresh.body.readUtf8().contains("refresh_token=refresh-1"))
            assertEquals("/.well-known/jwks.json", server.request().path)
            assertEquals("refresh-2", store.saved?.active?.pendingRefresh?.refreshToken)
            session = ChatGptSession(store, api) { time }
            session.load()

            server.enqueue(jwks())
            server.enqueue(stream())
            server.enqueue(stream())
            val afterRestart = AiModel.ChatGpt(model.slug, session.state.generation)
            val concurrent = List(2) {
                async { session.execute(afterRestart, request.copy(model = afterRestart)) { true } }
            }.awaitAll()
            assertTrue(concurrent.all { it.isSuccess })
            assertEquals("/.well-known/jwks.json", server.request().path)
            repeat(2) { assertEquals("Bearer access-2", server.request().getHeader("Authorization")) }
            assertNull(store.saved?.active?.pendingRefresh)
            assertEquals("refresh-2", store.saved?.active?.grant?.refreshToken)

            server.enqueue(json(mapOf("issuer" to ChatGptProtocol.ISSUER,
                "revocation_endpoint" to "${ChatGptProtocol.ISSUER}/api/accounts/oauth/revoke")))
            server.enqueue(MockResponse().setResponseCode(200))
            assertTrue(session.signOut(clientId))
            assertEquals("/.well-known/openid-configuration", server.request().path)
            val revoke = server.request()
            assertEquals("/api/accounts/oauth/revoke", revoke.path)
            assertTrue(revoke.body.readUtf8().contains("token=refresh-2"))
            assertFalse(session.state.active!!.connected)
            assertEquals(clientId, store.saved?.active?.clientId)
            assertNull(store.saved?.active?.grant)
            assertTrue(session.execute(afterRestart, request.copy(model = afterRestart)) { true } is AiResponse.Error)
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun `valid identity without plan permission cannot discover or send data`() = runBlocking {
        MockWebServer().use { server ->
            val session = ChatGptSession(store, api(server)) { time }
            session.load()
            signIn(session, server, "openid email profile")
            assertEquals(2, server.requestCount)
            assertTrue(session.state.active!!.connected)
            assertFalse(session.state.active!!.planEnabled)
            val result = runCatching { session.discover(session.state) { true } }
            assertEquals(AiProblem.CHATGPT_CONNECTION, (result.exceptionOrNull() as ChatGptFailure).problem)
            val model = AiModel.ChatGpt("gpt-test", session.state.generation)
            assertTrue(session.execute(model, AiRequest(model, listOf(AiMessage.User("private")))) { true } is AiResponse.Error)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `audio and zero retention requests are rejected locally and usage errors pause inference`() = runBlocking {
        MockWebServer().use { server ->
            val session = ChatGptSession(store, api(server)) { time }
            session.load()
            signIn(session, server)
            val model = ChatGptModel("gpt-test", "Synthetic model")
            session.selectModel(session.state, model)
            val route = AiModel.ChatGpt(model.slug, session.state.generation)
            val text = AiRequest(route, listOf(AiMessage.User("private")))
            val audio = AiRequest(route, listOf(AiMessage.UserWithAudio("Transcribe", "synthetic", "audio/wav")))
            assertTrue(session.execute(route, audio) { true } is AiResponse.Error)
            val privacy = session.execute(route, text.copy(zeroDataRetention = true)) { true } as AiResponse.Error.ChatGpt
            assertEquals(AiProblem.CHATGPT_INDEXING_PAUSED, privacy.problem)
            assertEquals(2, server.requestCount)

            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n"
            ))
            assertEquals(AiProblem.CHATGPT_LIMIT, (session.execute(route, text) { true } as AiResponse.Error.ChatGpt).problem)
            assertTrue(session.state.usageLimited)
            assertTrue(session.execute(route, text) { true } is AiResponse.Error)
            assertEquals(3, server.requestCount)
            session.resumeUsage(session.state)
            server.enqueue(stream())
            assertTrue(session.execute(route, text) { true }.isSuccess)
        }
    }

    @Test fun `Responses POST is never redirected or replayed by transport`() = runBlocking {
        MockWebServer().use { server ->
            val api = api(server)
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/unexpected")))
            server.enqueue(stream())
            val first = api.response(api.responseCall("synthetic", "{}") { true }, "gpt-test")
            assertEquals(503, (first as AiResponse.Error.ChatGpt).status)
            val second = api.response(api.responseCall("synthetic", "{}") { true }, "gpt-test")
            assertEquals(307, (second as AiResponse.Error.ChatGpt).status)
            assertEquals(2, server.requestCount)
            repeat(2) { assertEquals("/v1/responses", server.request().path) }
        }
    }

    @Test fun `wrong signature nonce audience or expiry cannot establish identity`() {
        val jwks = com.google.gson.JsonParser.parseString(jwks().getBody()!!.readUtf8()).asJsonObject
        val token = identity(nonce = "nonce")
        assertNotNull(ChatGptIdentity.verify(token, jwks, clientId, "nonce", "", time))
        assertTrue(runCatching { ChatGptIdentity.verify(token, jwks, clientId, "wrong", "", time) }.isFailure)
        assertTrue(runCatching { ChatGptIdentity.verify(token, jwks, "wrong", "nonce", "", time) }.isFailure)
        assertTrue(runCatching { ChatGptIdentity.verify(token, jwks, clientId, "nonce", "", time + 4_000) }.isFailure)
        val altered = token.substringBeforeLast('.') + "." + b64(ByteArray(256))
        assertTrue(runCatching { ChatGptIdentity.verify(altered, jwks, clientId, "nonce", "", time) }.isFailure)
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.signIn(
        session: ChatGptSession,
        server: MockWebServer,
        requestedScopes: String = scopes
    ) = session.signIn(null, consent = false) { authorization ->
        val url = authorization.toHttpUrl()
        val nonce = url.queryParameter("nonce")
        val redirect = checkNotNull(url.queryParameter("redirect_uri"))
        val state = checkNotNull(url.queryParameter("state"))
        server.enqueue(json(grant("access-1", "refresh-1", identity(nonce), requestedScopes)))
        server.enqueue(jwks())
        launch(Dispatchers.IO) {
            assertEquals(400, callback("$redirect?state=wrong&code=synthetic&client_id=$clientId"))
            assertEquals(200, callback("$redirect?state=$state&code=synthetic&client_id=$clientId"))
        }
    }

    private fun callback(url: String): Int {
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun api(server: MockWebServer): ChatGptApi {
        val http = ChatGptApi.httpClient().newBuilder().proxy(Proxy.NO_PROXY).addInterceptor { chain ->
            val request = chain.request()
            check(request.url.host in setOf("auth.openai.com", "api.openai.com"))
            val local = server.url(request.url.encodedPath)
            chain.proceed(request.newBuilder().url(local).build())
        }.build()
        return ChatGptApi(http) { time }
    }

    private fun identity(nonce: String?): String {
        val claims = mutableMapOf<String, Any>(
            "iss" to ChatGptProtocol.ISSUER, "sub" to "account", "aud" to clientId,
            "iat" to time, "exp" to time + 3_600, "email" to "test@example.com"
        )
        nonce?.let { claims["nonce"] = it }
        val signed = b64(gson.toJson(mapOf("alg" to "RS256", "kid" to "test")).toByteArray()) + "." +
            b64(gson.toJson(claims).toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(signingKey.private)
            update(signed.toByteArray(Charsets.US_ASCII))
        }
        return "$signed.${b64(signature.sign())}"
    }

    private fun jwks(): MockResponse {
        val key = signingKey.public as RSAPublicKey
        return json(mapOf("keys" to listOf(mapOf(
            "kty" to "RSA", "kid" to "test", "use" to "sig", "alg" to "RS256",
            "n" to b64(key.modulus.toByteArray()), "e" to b64(key.publicExponent.toByteArray())
        ))))
    }

    private fun grant(access: String, refresh: String, id: String, scope: String?) =
        mapOf("access_token" to access, "refresh_token" to refresh, "id_token" to id, "scope" to scope,
            "expires_in" to 3_600, "token_type" to "Bearer")

    private fun catalog() = json(mapOf("models" to listOf(
        mapOf("slug" to "hidden", "display_name" to "Hidden", "visibility" to "hide"),
        mapOf("slug" to "gpt-test", "display_name" to "Synthetic model", "visibility" to "list")
    )))

    private fun stream() = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n" +
            "data: {\"type\":\"response.completed\"}\n\n"
    )

    private fun json(value: Any) = MockResponse().setHeader("Content-Type", "application/json").setBody(gson.toJson(value))
    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun MockWebServer.request() = checkNotNull(takeRequest(5, TimeUnit.SECONDS))

    private class MemoryStore : ChatGptStore {
        var saved: ChatGptConnections? = null
        override fun read() = saved
        override fun write(connections: ChatGptConnections) { saved = connections }
    }
}
