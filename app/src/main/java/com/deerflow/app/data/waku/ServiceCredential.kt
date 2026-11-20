package com.deerflow.app.data.waku

import java.net.URI
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

private const val protocol = "DeerFlow service credential v2"
private const val maxFieldBytes = 65536

/** Public permission-card data. The private key exists only in the waiting Go service. */
data class ServiceCredentialForm(
    val publicKey: String,
    val consumer: String,
    val service: String,
    val account: String,
    val requiredFields: List<String>,
) {
    val requiresUsername get() = "username" in requiredFields
    val requiresKey get() = "key" in requiredFields

    fun hasRequiredInput(username: String, key: String) =
        username.isNotEmpty() == requiresUsername && key.isNotEmpty() == requiresKey

    companion object {
        fun fromOption(option: JsonObject): ServiceCredentialForm? {
            if (option.text("id") != "allow_once") return null
            val form = option["credentialForm"] as? JsonObject ?: return null
            if ((form["version"] as? JsonPrimitive)?.intOrNull != 2) return null
            val fields = (form["requiredFields"] as? JsonArray)?.map {
                (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: return null
            } ?: return null
            if (fields != listOf("key") && fields != listOf("username") && fields != listOf("username", "key")) return null
            val result = ServiceCredentialForm(form.text("publicKey"), form.text("consumer"),
                form.text("service"), form.text("account"), fields)
            return result.takeIf { it.publicKey.length == 124 && it.consumer.length in 1..256 &&
                it.service.length in 1..17000 && it.account.length in 1..64 &&
                listOf(it.consumer, it.service, it.account).none { s -> s.any(Char::isISOControl) } }
        }
    }

    /** HKDF-SHA256 / AES-256-GCM, with an ephemeral P-256 key on each side. */
    fun seal(username: String, secret: String): JsonObject {
        require(hasRequiredInput(username, secret)) { "填写本次请求的凭据字段。" }
        require(username.length <= maxFieldBytes && secret.length <= maxFieldBytes &&
            '\u0000' !in username && '\u0000' !in secret) { "每项最多 65536 字节，且不能包含空字符。" }
        val userBytes = username.toByteArray(Charsets.UTF_8)
        val secretBytes = secret.toByteArray(Charsets.UTF_8)
        try {
            require(userBytes.size <= maxFieldBytes && secretBytes.size <= maxFieldBytes) { "用户名和密钥各最多 65536 个 UTF-8 字节。" }
            val serverKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
            val client = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val shared = KeyAgreement.getInstance("ECDH").run { init(client.private); doPhase(serverKey, true); generateSecret() }
            val mac = Mac.getInstance("HmacSHA256")
            val prk = try { mac.init(SecretKeySpec(ByteArray(32), "HmacSHA256")); mac.doFinal(shared) } finally { shared.fill(0) }
            val key = try { mac.init(SecretKeySpec(prk, "HmacSHA256")); mac.doFinal(protocol.toByteArray() + byteArrayOf(1)) } finally { prk.fill(0) }
            val plain = ByteBuffer.allocate(4 + userBytes.size + secretBytes.size).putInt(userBytes.size).put(userBytes).put(secretBytes).array()
            try {
                val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
                val aes = Cipher.getInstance("AES/GCM/NoPadding")
                aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                aes.updateAAD(listOf(protocol, consumer, service, account, requiredFields.joinToString(","))
                    .joinToString("\u0000").toByteArray(Charsets.UTF_8))
                val b64 = Base64.getEncoder()
                return buildJsonObject {
                    put("publicKey", publicKey)
                    put("clientKey", b64.encodeToString(client.public.encoded))
                    put("nonce", b64.encodeToString(nonce))
                    put("ciphertext", b64.encodeToString(aes.doFinal(plain)))
                }
            } finally { key.fill(0); plain.fill(0) }
        } finally { userBytes.fill(0); secretBytes.fill(0) }
    }
}
private fun JsonObject.text(field: String): String = (this[field] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun credentialTransportAllowed(endpoint: String): Boolean = runCatching {
    val uri = URI(normalizeWakuEndpoint(endpoint))
    uri.scheme == "wss" || uri.host in setOf("127.0.0.1", "[::1]", "::1")
}.getOrDefault(false)
