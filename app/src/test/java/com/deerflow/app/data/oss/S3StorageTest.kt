package com.deerflow.app.data.oss

import com.deerflow.app.domain.model.MessageFile
import com.deerflow.app.domain.model.S3Object
import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class S3StorageTest {
    private val settings = S3Settings(endpoint = "https://s3.example.com", bucket = "my-bucket",
        accessKeyId = "AKIDEXAMPLE", accessKeySecret = "test-example-secret")
    private val now = Instant.parse("2026-10-31T00:00:00Z")

    @Test fun querySigningMatchesIndependentPythonStdlibReferenceVector() {
        val url = S3Signing.presign(settings, "deerflow-app/attachments/test/报价 +#.txt", now = now, expiresSeconds = 900)
        assertTrue(url.contains("%E6%8A%A5%E4%BB%B7%20%2B%23.txt"))
        assertTrue(url.endsWith("X-Amz-Signature=12ffff2dbd266ae3ea2fd32ec9cfc9fa4f1c08b77dc170dfc0a087956b654592"))
        assertFalse(url.contains(settings.accessKeySecret))
    }

    @Test fun temporaryTokensAreEncodedAndBoundToSignature() {
        val withToken = S3Signing.presign(settings.copy(sessionToken = "a/b+c="), "folder/a.txt", now = now)
        assertTrue(withToken.contains("X-Amz-Security-Token=a%2Fb%2Bc%3D"))
        assertNotEquals(S3Signing.presign(settings, "folder/a.txt", now = now).substringAfterLast('='), withToken.substringAfterLast('='))
    }

    @Test fun invalidEndpointsKeysAndExpiryAreRejected() {
        for (endpoint in listOf("http://s3.example.com", "https://user:pass@s3.example.com", "https://s3.example.com/path", "https://s3.example.com?query=1")) {
            assertNotNull(settings.copy(endpoint = endpoint).validationError)
        }
        for (key in listOf("../file", "folder/../file", "/file", "folder//file")) {
            assertTrue(runCatching { S3Signing.presign(settings, key) }.isFailure)
        }
        assertTrue(runCatching { S3Signing.presign(settings, "file", expiresSeconds = 604801) }.isFailure)
        assertFalse(settings.toString().contains(settings.accessKeySecret))
        assertFalse(settings.toString().contains(settings.accessKeyId))
    }

    @Test fun manifestContainsOnlySignedReadAccessWithIntegrityMetadataAndRequiredProviderHeader() {
        val obj = S3Object(settings.endpoint, settings.bucket, "deerflow-app/attachments/id/a.txt", "a.txt", "text/plain", 4, "a".repeat(64))
        val file = MessageFile("id", obj.name, obj.mimeType, obj.size, oss = obj)
        val text = OssAttachmentEnvelope.append("分析文件", listOf(file), settings, now)
        val manifest = Json.parseToJsonElement(text.substringAfter("[DEERFLOW_OSS_ATTACHMENTS_V1]\n").substringBefore("\n[/DEERFLOW_OSS_ATTACHMENTS_V1]")).jsonObject
        val first = manifest["attachments"]!!.jsonArray.single().jsonObject
        assertEquals("S3Drive", first["headers"]!!.jsonObject["User-Agent"]!!.jsonPrimitive.content)
        assertEquals(obj.sha256, first["sha256"]!!.jsonPrimitive.content)
        assertFalse(text.contains(settings.accessKeySecret))
        assertEquals("分析文件", OssAttachmentEnvelope.append(" 分析文件 ", emptyList(), settings))
        assertTrue(S3Settings.importProfile(Json.encodeToString(S3Settings.serializer(), settings)).configured)
        assertTrue(S3Settings.importProfile("\uFEFF" + Json.encodeToString(S3Settings.serializer(), settings)).configured)
        val padded = S3Settings.importProfile("""{"endpoint":" https://s3.example.com/ ","bucket":" my-bucket ","region":" us-east-1 ","accessKeyId":" AKIDEXAMPLE\n","accessKeySecret":" test-example-secret\r\n","sessionToken":" tok ","userAgent":" S3Drive "}""")
        assertEquals(settings.copy(sessionToken = "tok"), padded)
        assertEquals(
            S3Signing.presign(settings, "folder/a.txt", now = now),
            S3Signing.presign(settings.copy(endpoint = "https://s3.example.com:443"), "folder/a.txt", now = now),
        )
    }
}
