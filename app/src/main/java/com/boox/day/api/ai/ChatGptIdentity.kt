package com.boox.day.api.ai

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

internal object ChatGptIdentity {
    class Verified(val subject: String, val email: String?)

    /** Validate the OIDC ID token against OpenAI's fixed JWKS origin. Access tokens remain opaque. */
    fun verify(
        token: String,
        jwks: JsonObject,
        clientId: String,
        nonce: String?,
        accessToken: String,
        now: Long
    ): Verified = try {
        val parts = token.split('.')
        require(parts.size == 3)
        val header = jsonPart(parts[0])
        val keyId = header.string("kid") ?: error("missing kid")
        require(header.string("alg") == "RS256" && !header.has("crit"))
        val keys = jwks.get("keys").asJsonArray
        val key = keys.mapNotNull(JsonElement::objectOrNull).single { it.string("kid") == keyId }
        require(key.string("kty") == "RSA")
        require(!key.has("use") || key.string("use") == "sig")
        require(!key.has("alg") || key.string("alg") == "RS256")
        val modulus = BigInteger(1, decode(checkNotNull(key.string("n"))))
        val exponent = BigInteger(1, decode(checkNotNull(key.string("e"))))
        require(modulus.bitLength() in 2048..8192 && exponent.bitLength() in 2..32)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initVerify(publicKey)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        }
        require(signature.verify(decode(parts[2])))

        val claims = jsonPart(parts[1])
        require(claims.string("iss") == ChatGptProtocol.ISSUER)
        val aud = claims.get("aud")
        val audiences = if (aud?.isJsonArray == true) {
            aud.asJsonArray.map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString }
        } else listOfNotNull(claims.string("aud"))
        require(clientId in audiences)
        if (claims.has("azp") || audiences.size > 1) require(claims.string("azp") == clientId)
        require((claims.long("exp") ?: 0) > now)
        require((claims.long("iat") ?: Long.MAX_VALUE) in 1..(now + 60))
        if (claims.has("nbf")) require((claims.long("nbf") ?: Long.MAX_VALUE) <= now)
        if (nonce != null) require(ChatGptProtocol.secureEquals(nonce, claims.string("nonce").orEmpty()))
        if (claims.has("at_hash")) {
            val digest = MessageDigest.getInstance("SHA-256").digest(accessToken.toByteArray(Charsets.US_ASCII))
            val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest.copyOf(16))
            require(ChatGptProtocol.secureEquals(expected, claims.string("at_hash").orEmpty()))
        }
        val subject = claims.string("sub")?.takeIf { it.isNotBlank() && it.length <= 255 } ?: error("missing sub")
        val email = claims.string("email")?.takeIf { it.isNotBlank() && it.length <= 254 }
        Verified(subject, email)
    } catch (_: Exception) {
        throw ChatGptFailure("ChatGPT returned an identity that BooxDay could not verify.")
    }

    private fun jsonPart(part: String) = JsonParser.parseString(String(decode(part), Charsets.UTF_8)).asJsonObject
    private fun decode(part: String) = Base64.getUrlDecoder().decode(part)
}
