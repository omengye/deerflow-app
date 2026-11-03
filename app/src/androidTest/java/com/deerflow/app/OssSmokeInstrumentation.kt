package com.deerflow.app

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import com.deerflow.app.data.oss.*
import com.deerflow.app.data.settings.SettingsStore
import com.deerflow.app.data.waku.*
import com.deerflow.app.domain.model.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request

/** Opt-in device smoke test; private profiles are pushed at runtime, never packaged. */
class OssSmokeInstrumentation : Instrumentation() {
    private var arguments: Bundle? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments
        start()
    }
    override fun onStart() {
        val result = Bundle()
        try {
            runBlocking {
                val store = SettingsStore(targetContext)
                val profileFile = File(targetContext.filesDir, "oss-validation-profile.json")
                if (profileFile.isFile) {
                    val settings = S3Settings.importProfile(profileFile.readText().removePrefix("\uFEFF"))
                    profileFile.delete()
                    store.saveOss(settings)
                    check(store.currentOss() == settings) { "encrypted configuration roundtrip failed" }
                    val source = File(targetContext.cacheDir, "oss-validation.txt").apply { writeText("Android OSS signed upload and download.\n") }
                    val key = "deerflow-app/validation/${UUID.randomUUID()}/android.txt"
                    var uploaded = false
                    try {
                        val obj = S3Storage().upload(settings, source, key, "android.txt", "text/plain")
                        uploaded = true
                        val file = MessageFile("android-test", obj.name, obj.mimeType, obj.size, oss = obj)
                        val downloaded = MessageFileCache(File(targetContext.cacheDir, "oss-device-validation")).resolve(file, settings) { error("unexpected Waku read") }
                        check(File(URI(downloaded.url)).readBytes().contentEquals(source.readBytes())) { "content mismatch" }
                        result.putString("live_s3", "passed")
                        result.putString("keystore_storage", "passed")
                    } finally {
                        source.delete()
                        if (uploaded) {
                            val request = Request.Builder().url(S3Signing.presign(settings, key, "DELETE", expiresSeconds = 900))
                                .header("User-Agent", settings.userAgent).delete().build()
                            OkHttpClient().newCall(request).execute().use { check(it.isSuccessful) { "test object cleanup failed" } }
                        }
                    }
                }
                val cfg = store.current()
                val sessionId = "50000000-0000-0000-0000-000000000001"
                val sessionStore = WakuSessionStore(File(targetContext.filesDir, "waku-sessions"), cfg.endpoint, cfg.token)
                if (arguments?.getString("cleanup_ui_fixture") == "true") {
                    sessionStore.delete(sessionId)
                    result.putString("ui_fixture", "cleaned")
                } else if (arguments?.getString("seed_ui_fixture") != "false") {
                    val namespace = WakuSessionStore.profileNamespace(cfg.endpoint, cfg.token)
                    val cache = MessageFileCache(File(targetContext.cacheDir, "waku-$namespace/message-files"))
                    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(110, 95, 210)) }
                    val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    bitmap.recycle()
                    val staged = cache.stage(png.inputStream(), "上传图片.png", 1024 * 1024)
                    val text = cache.stage("用户上传文件".byteInputStream(), "上传文档.txt", 1024)
                    val image = MessageFile("image-fixture", "上传图片.png", "image/png", staged.size, staged.file.toURI().toString(), sha256 = staged.sha256)
                    val document = MessageFile("file-fixture", "上传文档.txt", "text/plain", text.size, text.file.toURI().toString(), sha256 = text.sha256)
                    val report = File(targetContext.cacheDir, "waku-$namespace/waku-artifacts").apply { mkdirs() }
                    val data = "按需读取的会话产物".toByteArray()
                    val reportFile = File(report, S3Signing.sha256(data) + "-会话报告.txt").apply { writeBytes(data) }
                    val session = WakuProjection.createSession(sessionId, "test-project").updatedForTest(
                        "title" to JsonPrimitive("附件与产物验证"), "messages" to buildJsonArray {
                            add(buildJsonObject { put("id", "message-fixture"); put("role", "user"); put("content", "查看这张图片和文件"); put("streaming", false) })
                            add(buildJsonObject { put("id", "reply-fixture"); put("role", "assistant"); put("content", "已处理附件，报告可在产物页面查看。"); put("streaming", false) })
                        })
                    val snapshot = WakuMessageFiles.remember(WakuProjection.fromSession(session), "message-fixture", "查看这张图片和文件", listOf(image, document))
                        .copy(artifactFiles = listOf(AgentArtifact("waku:test/node/report", "会话报告.txt", reportFile.toURI().toString(), "text/plain", size = data.size.toLong())))
                    sessionStore.save(snapshot)
                    result.putString("ui_fixture", "prepared")
                }
            }
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            // Never return a raw HTTP exception, credentials or signed URL to test logs.
            result.putString("failure_type", error.javaClass.simpleName)
            result.putString("failure_code", if (error.message?.contains("OSS") == true) error.message else "device validation failed")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}

private fun JsonObject.updatedForTest(vararg values: Pair<String, JsonElement>) = JsonObject(toMutableMap().apply { putAll(values) })
