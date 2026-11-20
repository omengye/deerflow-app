package com.deerflow.app.data.waku

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.AEADBadTagException
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ServiceCredentialTest {
    private val server = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun fields(vararg names: String) = JsonArray(names.map(::JsonPrimitive))
    private fun form(required: JsonElement?, version: Int = 2, option: String = "allow_once") =
        ServiceCredentialForm.fromOption(buildJsonObject {
            put("id", option)
            put("credentialForm", buildJsonObject {
                put("version", version); put("publicKey", Base64.getEncoder().encodeToString(server.public.encoded))
                put("consumer", "skill:test"); put("service", "https://sample.example"); put("account", "default")
                required?.let { put("requiredFields", it) }
            })
        })

    @Test fun rejectsIncompleteUnsupportedAndAmbiguousEnrollmentCards() {
        assertNull(form(fields("key"), option = "allow_always"))
        assertNull(form(fields("key"), version = 1))
        assertNull(form(null))
        assertNull(form(fields()))
        assertNull(form(fields("other")))
        assertNull(form(fields("key", "key")))
        assertNull(form(fields("key", "username")))
        assertNull(form(JsonPrimitive("key")))
        assertNull(ServiceCredentialForm.fromOption(buildJsonObject { put("id","allow_once") }))
    }

    @Test fun encryptsOnlyTheRequestedFieldsAndBindsThemToTheCard() {
        for (required in listOf(listOf("username"), listOf("key"), listOf("username","key"))) {
            val active = requireNotNull(form(fields(*required.toTypedArray())))
            val username = if ("username" in required) "  测试-用户  " else ""
            val secret = if ("key" in required) "symbol /+ 密钥 " + "x".repeat(500) else ""
            assertEquals("username" in required, active.requiresUsername)
            assertEquals("key" in required, active.requiresKey)
            val sealed = active.seal(username,secret)
            assertEquals(setOf("publicKey","clientKey","nonce","ciphertext"),sealed.keys)
            if (username.isNotEmpty()) assertFalse(sealed.toString().contains(username))
            if (secret.isNotEmpty()) assertFalse(sealed.toString().contains(secret))
            assertEquals(username to secret, decrypt(sealed,required.joinToString(",")))
            assertThrows(AEADBadTagException::class.java) { decrypt(sealed,"other") }
            assertThrows(IllegalArgumentException::class.java) { active.seal("", "") }
            if (username.isEmpty()) assertThrows(IllegalArgumentException::class.java) { active.seal("extra", secret) }
            if (secret.isEmpty()) assertThrows(IllegalArgumentException::class.java) { active.seal(username, "extra") }
        }
        val both = requireNotNull(form(fields("username","key")))
        assertThrows(IllegalArgumentException::class.java) { both.seal("x","界".repeat(22000)) }
        assertThrows(IllegalArgumentException::class.java) { both.seal("","abc") }
        val oneByte = requireNotNull(form(fields("key"))).seal("", "k")
        assertEquals(28, oneByte.getValue("ciphertext").jsonPrimitive.content.length)
        assertEquals("" to "k", decrypt(oneByte,"key"))
    }

    private fun decrypt(sealed: JsonObject, fieldList: String): Pair<String, String> {
        val replyKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(sealed.bytes("clientKey")))
        val shared = KeyAgreement.getInstance("ECDH").run { init(server.private); doPhase(replyKey,true); generateSecret() }
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(ByteArray(32),"HmacSHA256"))
        val extracted = hmac.doFinal(shared)
        hmac.init(SecretKeySpec(extracted,"HmacSHA256"))
        val label = "DeerFlow service credential v2"
        val aesKey = hmac.doFinal(label.toByteArray() + byteArrayOf(1))
        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.DECRYPT_MODE,SecretKeySpec(aesKey,"AES"),GCMParameterSpec(128,sealed.bytes("nonce")))
        aes.updateAAD((label + "\u0000skill:test\u0000https://sample.example\u0000default\u0000" + fieldList).toByteArray())
        val plain = ByteBuffer.wrap(aes.doFinal(sealed.bytes("ciphertext")))
        val user = ByteArray(plain.int).also { plain.get(it) }
        val password = ByteArray(plain.remaining()).also { plain.get(it) }
        return user.decodeToString() to password.decodeToString()
    }

    @Test fun authenticationRequiresTlsOrNumericLoopback() {
        assertTrue(credentialTransportAllowed("wss://gentoo.example/v1"))
        assertTrue(credentialTransportAllowed("ws://127.0.0.1:18787/v1"))
        assertTrue(credentialTransportAllowed("ws://[::1]/v1"))
        assertFalse(credentialTransportAllowed("ws://10.0.0.2/v1"))
        assertFalse(credentialTransportAllowed("ws://127.0.0.1.example/v1"))
    }
    private fun JsonObject.bytes(name: String) = Base64.getDecoder().decode(getValue(name).jsonPrimitive.content)
}
