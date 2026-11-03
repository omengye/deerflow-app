package com.deerflow.app.data.waku

import com.deerflow.app.data.oss.*
import com.deerflow.app.domain.model.MessageFile
import com.deerflow.app.domain.model.S3Object
import java.io.File
import java.net.URI
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MessageFileCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "实际附件内容".toByteArray()
    private val hash = S3Signing.sha256(bytes)
    private val settings = S3Settings(accessKeyId = "test-account", accessKeySecret = "test-only-secret")

    @Test fun stagingRejectsUnknownSizeStreamsAboveLimitAndCleansPartialFiles() {
        val root = temporary.newFolder()
        assertTrue(runCatching { MessageFileCache(root).stage(ByteArray(9).inputStream(), "../file.txt", 8) }.isFailure)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun longUnicodeNamesStayInsideCacheAndCachedFilesWorkOffline() = runBlocking {
        val root = temporary.newFolder()
        val cache = MessageFileCache(root)
        val staged = cache.stage(bytes.inputStream(), "../../" + "文件".repeat(100) + ".txt", 100)
        assertEquals(root.canonicalFile, staged.file.parentFile!!.canonicalFile)
        assertTrue(staged.file.name.toByteArray().size < 255)
        val input = MessageFile("f", "image.png", "image/png", staged.size, staged.file.toURI().toString(), sha256 = staged.sha256)
        assertEquals(staged.file, File(URI(cache.resolve(input, S3Settings()) { error("must not query server") }.url)))
    }

    @Test fun serverFilesUseCorrectReadRpcAndVerifyReturnedBytes() = runBlocking {
        val cache = MessageFileCache(temporary.newFolder())
        for (reference in listOf("waku-blob:hash", "waku-attachment:ref")) {
            val file = MessageFile(reference, "a.txt", "text/plain", bytes.size.toLong(), serverReference = reference, serverPath = "/server/a.txt", sha256 = hash)
            val artifact = cache.resolve(file, S3Settings()) { request ->
                assertEquals(if (reference.startsWith("waku-blob:")) "readBlob" else "readAttachment", request.string("type"))
                buildJsonObject { put("type", "blobData"); put("bytes", Base64.getEncoder().encodeToString(bytes)) }
            }
            assertArrayEquals(bytes, File(URI(artifact.url)).readBytes())
        }
    }

    @Test fun corruptedBytesAreRejectedAndNotRetained() = runBlocking {
        val root = temporary.newFolder()
        val file = MessageFile("f", "x.txt", "text/plain", bytes.size.toLong(), serverReference = "waku-blob:h", sha256 = "0".repeat(64))
        val result = runCatching { MessageFileCache(root).resolve(file, S3Settings()) {
            buildJsonObject { put("type", "blobData"); put("bytes", Base64.getEncoder().encodeToString(bytes)) }
        } }
        assertTrue(result.isFailure)
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun ossDownloadsRequireProviderHeaderAndNeverForwardSecretKey() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("S3Drive", chain.request().header("User-Agent"))
            assertNull(chain.request().header("Authorization"))
            assertFalse(chain.request().url.toString().contains(settings.accessKeySecret))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(bytes.toResponseBody()).build()
        }.build()
        val objectRef = S3Object(settings.endpoint, settings.bucket, "deerflow-app/attachments/id/a.txt", "a.txt", "text/plain", bytes.size.toLong(), hash)
        val file = MessageFile("f", "a.txt", "text/plain", bytes.size.toLong(), oss = objectRef)
        val result = MessageFileCache(temporary.newFolder(), http).resolve(file, settings) { error("OSS must not use Waku") }
        assertArrayEquals(bytes, File(URI(result.url)).readBytes())
    }
}
