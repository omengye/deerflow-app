package com.deerflow.app.data.waku

import java.io.File
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WakuImageCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val reference = "waku-blob:926dbfd237ce74b8.png"
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aV1sAAAAASUVORK5CYII=")
    private fun response(bytes: ByteArray = png) = buildJsonObject {
        put("type", "blobData"); put("bytes", Base64.getEncoder().encodeToString(bytes))
    }

    @Test fun downloadedImageSurvivesCacheRecreationAndOpensOffline() = runBlocking {
        val directory = temporary.newFolder()
        val image = WakuImageCache(directory).resolve(reference) { request ->
            assertEquals(buildJsonObject { put("type", "readBlob"); put("reference", reference) }, request)
            response()
        }
        assertEquals("image/png", image.mimeType)
        assertEquals("image", image.kind)
        assertTrue(image.name.endsWith(".png"))
        assertEquals(png.size.toLong(), image.size)
        assertArrayEquals(png, File(URI(image.url)).readBytes())
        assertEquals(image, WakuImageCache(directory).resolve(reference) { error("cached image must work offline") })
    }

    @Test fun missingCorruptAndEvictedCachesDownloadAgain() = runBlocking {
        val directory = temporary.newFolder()
        var reads = 0
        suspend fun load() = WakuImageCache(directory).resolve(reference) { reads++; response() }
        val initial = load()
        File(URI(initial.url)).writeBytes(ByteArray(png.size))
        assertArrayEquals(png, File(URI(load().url)).readBytes())
        File(URI(initial.url)).delete()
        assertArrayEquals(png, File(URI(load().url)).readBytes())
        directory.listFiles()!!.single { it.extension == "json" }.writeText("broken index")
        assertArrayEquals(png, File(URI(load().url)).readBytes())
        directory.listFiles()!!.forEach { it.delete() }
        assertArrayEquals(png, File(URI(load().url)).readBytes())
        assertEquals(5, reads)
    }

    @Test fun profilesNeverReuseEachOthersImageCache() = runBlocking {
        val first = temporary.newFolder("first-profile")
        val second = temporary.newFolder("second-profile")
        val original = WakuImageCache(first).resolve(reference) { response() }
        var reads = 0
        val other = WakuImageCache(second).resolve(reference) { reads++; response() }
        assertEquals(1, reads)
        assertNotEquals(original.url, other.url)
        assertEquals(first.canonicalFile, File(URI(original.url)).parentFile!!.canonicalFile)
        assertEquals(second.canonicalFile, File(URI(other.url)).parentFile!!.canonicalFile)
    }

    @Test fun simultaneousCopiesOfAnImageShareOneDownload() = runBlocking {
        val cache = WakuImageCache(temporary.newFolder())
        val reads = AtomicInteger()
        val results = List(4) {
            async(Dispatchers.Default) { cache.resolve(reference) { reads.incrementAndGet(); delay(30); response() } }
        }.awaitAll()
        assertEquals(1, reads.get())
        assertEquals(1, results.map { it.url }.distinct().size)
    }

    @Test fun failedAndCancelledDownloadsRemainRetryable() = runBlocking {
        val directory = temporary.newFolder()
        val cache = WakuImageCache(directory)
        assertTrue(runCatching { cache.resolve(reference) { throw IOException("offline") } }.isFailure)
        val started = CompletableDeferred<Unit>()
        val cancelled = launch { cache.resolve(reference) { started.complete(Unit); awaitCancellation() } }
        started.await(); cancelled.cancelAndJoin()
        assertTrue(directory.listFiles()!!.isEmpty())
        assertArrayEquals(png, File(URI(cache.resolve(reference) { response() }.url)).readBytes())
    }

    @Test fun malformedReferencesNeverIssueARead() = runBlocking {
        for (value in listOf("waku-blob:../private.png", "waku-blob:x.png?other", "waku-blob:x.svg", "file:///x.png", "data:image/png;base64,AAAA")) {
            assertTrue(runCatching { WakuImageCache(temporary.newFolder()).resolve(value) { error("must not read") } }.exceptionOrNull()?.message.orEmpty().contains("图片引用无效"))
        }
    }

    @Test fun malformedOversizedAndWrongFormatBodiesAreNotCached() = runBlocking {
        val bodies = listOf(
            buildJsonObject { put("type", "ack") },
            buildJsonObject { put("type", "blobData"); put("bytes", "not-base64!") },
            response(byteArrayOf()),
            response("not an image".toByteArray()),
            response(byteArrayOf(-1, -40, -1)), // JPEG bytes cannot masquerade as PNG.
            buildJsonObject { put("type", "blobData"); put("bytes", "A".repeat((((WakuImageCache.MAX_IMAGE_BYTES + 2) / 3) * 4 + 4).toInt())) },
        )
        for (body in bodies) {
            val directory = temporary.newFolder()
            assertTrue(runCatching { WakuImageCache(directory).resolve(reference) { body } }.isFailure)
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun allNativeOutputFormatsKeepTheCorrectMimeAndFileExtension() = runBlocking {
        val samples = listOf(
            Triple("png", "image/png", png), Triple("jpg", "image/jpeg", byteArrayOf(-1, -40, -1, 0)),
            Triple("webp", "image/webp", "RIFF0000WEBP".toByteArray()), Triple("gif", "image/gif", "GIF89a".toByteArray()),
        )
        for ((extension, mime, bytes) in samples) {
            val image = WakuImageCache(temporary.newFolder()).resolve("waku-blob:926dbfd237ce74b8.$extension") { response(bytes) }
            assertEquals(mime, image.mimeType)
            assertTrue(image.name.endsWith(".$extension"))
            assertArrayEquals(bytes, File(URI(image.url)).readBytes())
        }
    }
}
