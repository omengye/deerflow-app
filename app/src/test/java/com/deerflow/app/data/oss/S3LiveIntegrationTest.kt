package com.deerflow.app.data.oss

import com.deerflow.app.data.waku.MessageFileCache
import com.deerflow.app.domain.model.MessageFile
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Opt-in live check. The profile is never included in the project or APK. */
class S3LiveIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun platformClient(): OkHttpClient {
        // Optional CA exported from the OS trust store; TLS verification stays enabled.
        // Android itself uses its system roots, independently verified by the device test.
        val ca = System.getenv("DEERFLOW_S3_TRUSTED_CA")?.takeIf { it.isNotBlank() } ?: return OkHttpClient()
        val certificate = File(ca).inputStream().use { java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val roots = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply {
            load(null, null); setCertificateEntry("system-trusted-service-root", certificate)
        }
        val factory = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }
        val trust = factory.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().single()
        val tls = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust).followRedirects(false).build()
    }
    @Test fun appUploadAndColdCacheDownloadRoundTrip() = runBlocking {
        val profile = System.getenv("DEERFLOW_S3_PROFILE")
        assumeTrue("Private S3 profile not provided", !profile.isNullOrBlank())
        val settings = S3Settings.importProfile(File(profile!!).readText().removePrefix("\uFEFF"))
        val payload = "DeerFlow Kotlin OSS roundtrip.\n中文文件名与内容\n".toByteArray()
        val source = temporary.newFile().apply { writeBytes(payload) }
        val key = "deerflow-app/validation/${UUID.randomUUID()}/附件 +测试.txt"
        var uploaded = false
        val http = platformClient()
        try {
            val obj = S3Storage(http).upload(settings, source, key, "附件 +测试.txt", "text/plain")
            uploaded = true
            val file = MessageFile("test", obj.name, obj.mimeType, obj.size, oss = obj)
            val downloaded = MessageFileCache(temporary.newFolder(), http).resolve(file, settings) { error("OSS cannot route through Waku") }
            assertArrayEquals(payload, File(URI(downloaded.url)).readBytes())
        } finally {
            if (uploaded) {
                val request = Request.Builder().url(S3Signing.presign(settings, key, "DELETE", expiresSeconds = 900))
                    .header("User-Agent", settings.userAgent).delete().build()
                http.newCall(request).execute().use { assertTrue("Owned test object cleanup failed", it.isSuccessful) }
            }
        }
    }
}
