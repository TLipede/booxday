package com.boox.day.api.ai

import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptProtocolTest {
    @Test fun `authorization binds registration to loopback state nonce and S256 verifier`() {
        val attempt = ChatGptProtocol.newAttempt(43210, "urn:uuid:host")
        val url = attempt.authorizationUrl().toHttpUrl()
        assertEquals("auth.openai.com", url.host)
        assertEquals("dynamic_agent_client", url.queryParameter("client_id"))
        assertEquals("BooxDay", url.queryParameter("agent_name_hint"))
        assertEquals("http://127.0.0.1:43210/auth/callback", url.queryParameter("redirect_uri"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(ChatGptProtocol.challenge(attempt.verifier), url.queryParameter("code_challenge"))
        assertEquals(attempt.nonce, url.queryParameter("nonce"))
        assertNotEquals(attempt.state, attempt.nonce)
        assertNotEquals(attempt.state, ChatGptProtocol.newAttempt(43210, attempt.hostId).state)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            ChatGptProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))

        val callback = attempt.callback("/auth/callback?state=${attempt.state}&code=hello&client_id=oaiapp_test")
            as ChatGptProtocol.Callback.Authorized
        val fields = attempt.exchangeFields(callback)
        assertEquals("hello", fields["code"])
        assertEquals("oaiapp_test", fields["client_id"])
        assertEquals(url.queryParameter("redirect_uri"), fields["redirect_uri"])
        assertEquals(attempt.verifier, fields["code_verifier"])
        assertFalse(fields.containsKey("client_secret"))
    }

    @Test fun `callback ignores bad state path duplicates and foreign authority`() {
        val attempt = ChatGptProtocol.newAttempt(43210, "urn:uuid:host", "oaiapp_saved")
        val state = attempt.state
        for (callback in listOf(
            "/auth/callback?state=wrong&code=one",
            "/auth/callback?state=$state&state=$state&code=one",
            "/auth/callback?state=$state&code=one&%63ode=two",
            "/img?state=$state&code=one",
            "//evil.example/auth/callback?state=$state&code=one",
            "/auth/callback?state=$state&code=%not-hex"
        )) assertNull(attempt.callback(callback))
        assertTrue(attempt.callback("/auth/callback?state=$state&code=one&client_id=oaiapp_other")
            is ChatGptProtocol.Callback.Rejected)
        assertTrue(attempt.callback("/auth/callback?state=$state&error=access_denied")
            is ChatGptProtocol.Callback.Rejected)
        val valid = attempt.callback("/auth/callback?state=$state&code=one") as ChatGptProtocol.Callback.Authorized
        assertEquals("oaiapp_saved", valid.clientId)
        assertNull(attempt.authorizationUrl().toHttpUrl().queryParameter("agent_name_hint"))
    }

    @Test fun `Responses body omits unsupported controls and sends an inline image and strict schema`() {
        val request = AiRequest(
            AiModel.Gpt5Nano, listOf(AiMessage.System("Instructions"), AiMessage.UserWithImage("Read", "/9j/synthetic")),
            config = AiConfig(maxTokens = 200, temperature = 0.2f),
            jsonSchema = JsonSchema("ocr_text", mapOf("type" to "object"))
        )
        val json = JsonParser.parseString(ChatGptProtocol.requestBody(request, "gpt-test")).asJsonObject
        assertEquals("gpt-test", json.get("model").asString)
        assertFalse(json.get("store").asBoolean)
        assertTrue(json.get("stream").asBoolean)
        assertFalse(json.has("max_output_tokens"))
        assertFalse(json.has("temperature"))
        val input = json.getAsJsonArray("input")
        assertEquals("developer", input[0].asJsonObject.string("role"))
        assertEquals("data:image/jpeg;base64,/9j/synthetic",
            input[1].asJsonObject.getAsJsonArray("content")[1].asJsonObject.string("image_url"))
        val format = json.getAsJsonObject("text").getAsJsonObject("format")
        assertEquals("json_schema", format.string("type"))
        assertTrue(format.get("strict").asBoolean)
    }

    @Test fun `partial output is rejected on disconnect refusal or subscription limit`() {
        val delta = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"
        assertTrue(parse(delta) is AiResponse.Error)
        assertTrue(parse(delta + "data: {\"type\":\"response.incomplete\"}\n\n") is AiResponse.Error)
        assertTrue(parse(delta + "data: {\"type\":\"response.refusal.done\"}\n\n" +
            "data: {\"type\":\"response.completed\"}\n\n") is AiResponse.Error)
        val limited = parse(delta + """data: {"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}""" + "\n\n")
        assertEquals(AiProblem.CHATGPT_LIMIT, (limited as AiResponse.Error.ChatGpt).problem)
        assertEquals("partial", parse(delta + "data: {\"type\":\"response.completed\"}\n\n").getOrNull())
    }

    private fun parse(events: String) = ChatGptProtocol.readStream(events.reader().buffered(), "gpt-test")
}
