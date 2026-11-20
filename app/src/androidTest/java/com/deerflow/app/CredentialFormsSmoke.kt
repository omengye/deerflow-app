package com.deerflow.app

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.deerflow.app.ui.MainActivity
import com.deerflow.app.ui.chat.PendingActions
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

/** Drives the shipped approval card, protected dialog and encrypted submit callback on a device. */
internal fun runCredentialFormsSmoke(test: Instrumentation) {
    val intent = Intent(test.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val activity = test.startActivitySync(intent) as MainActivity
    try {
        for (fields in listOf(listOf("key"), listOf("username", "key"))) {
            val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
            val request = "test-" + fields.size
            val option = buildJsonObject {
                put("id", "allow_once")
                putJsonObject("credentialForm") {
                    put("version", 2); put("publicKey", publicKey)
                    put("consumer", "skill:smoke"); put("service","https://fixture.invalid"); put("account","default")
                    put("requiredFields", JsonArray(fields.map(::JsonPrimitive)))
                }
            }
            val card = buildJsonObject {
                put("requestId",request); put("title","Device fixture"); put("options",JsonArray(listOf(option)))
            }
            val submitted = LinkedBlockingQueue<Pair<String, JsonObject>>()
            test.runOnMainSync {
                activity.setContent {
                    MaterialTheme {
                        PendingActions(listOf(card), emptyList(), true, fields.size, true,
                            { id, sealed -> submitted.offer(id to sealed) }, { _, _ -> error("bare approval") },
                            { _, _ -> error("unexpected question") })
                    }
                }
            }
            test.tapLabel("填写服务凭据")
            val inputs = test.waitForNodes { nodes ->
                nodes.filter { it.isEditable }.takeIf { it.size == fields.size }
            }
            val key = "device-fixture-key"
            val user = "device-fixture-user"
            inputs.zip(fields).forEach { (node, field) ->
                val value = if (field == "key") key else user
                check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                })) { "fixture input rejected" }
            }
            test.tapLabel("保存并允许本次")
            val reply = checkNotNull(submitted.poll(10, TimeUnit.SECONDS)) { "encrypted form submission missing" }
            check(reply.first == request && reply.second["publicKey"]?.jsonPrimitive?.content == publicKey)
            check(reply.second.keys == setOf("publicKey", "clientKey", "nonce", "ciphertext"))
            check(!reply.second.toString().contains(key) && !reply.second.toString().contains(user))
        }
    } finally {
        test.runOnMainSync { activity.finish() }
    }
}

private fun Instrumentation.tapLabel(label: String) {
    val button = waitForNodes { nodes ->
        nodes.firstOrNull { it.text?.toString() == label }?.let { text ->
            generateSequence(text) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
        }
    }
    check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "button did not accept click" }
}

private fun <T> Instrumentation.waitForNodes(select: (List<AccessibilityNodeInfo>) -> T?): T {
    val deadline = SystemClock.elapsedRealtime() + 12000
    fun collect(node: AccessibilityNodeInfo, nodes: MutableList<AccessibilityNodeInfo>) {
        nodes.add(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { collect(it, nodes) }
    }
    while (SystemClock.elapsedRealtime() < deadline) {
        uiAutomation.rootInActiveWindow?.let { root ->
            select(buildList { collect(root, this) })?.let { return it }
        }
        SystemClock.sleep(100)
    }
    error("expected approval UI not available")
}
