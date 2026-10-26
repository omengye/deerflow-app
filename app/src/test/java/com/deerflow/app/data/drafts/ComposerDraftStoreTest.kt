package com.deerflow.app.data.drafts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComposerDraftStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `text and attachment metadata survive recreating the store`() = runBlocking {
        val directory = temporary.newFolder()
        val draft = ComposerDraft("未发送的文字", listOf(DraftAttachment("content://documents/item/1", "图片.png", "image/png", 123)))
        ComposerDraftStore(directory).save("profile-a|thread-1", draft)
        assertEquals(draft, ComposerDraftStore(directory).load("profile-a|thread-1"))
        assertTrue(directory.listFiles()!!.all { !it.name.contains("profile-a") && !it.name.endsWith(".tmp") })
    }
    @Test fun `profiles and threads do not share drafts`() = runBlocking {
        val store = ComposerDraftStore(temporary.newFolder())
        store.save("server-a|thread-1", ComposerDraft("A"))
        store.save("server-b|thread-1", ComposerDraft("B"))
        assertEquals("A", store.load("server-a|thread-1").text)
        assertEquals("B", store.load("server-b|thread-1").text)
        assertEquals(ComposerDraft(), store.load("server-a|thread-2"))
    }
    @Test fun `newer saved content replaces the previous version and accepted draft can be cleared`() = runBlocking {
        val directory = temporary.newFolder()
        val store = ComposerDraftStore(directory)
        store.save("p|t", ComposerDraft("old"))
        store.save("p|t", ComposerDraft("new"))
        assertEquals("new", ComposerDraftStore(directory).load("p|t").text)
        store.save("p|t", ComposerDraft())
        assertEquals(ComposerDraft(), ComposerDraftStore(directory).load("p|t"))
        assertTrue(directory.listFiles()!!.isEmpty())
    }
    @Test fun `damaged draft is reported rather than silently replaced with empty text`() = runBlocking {
        val directory = temporary.newFolder()
        val store = ComposerDraftStore(directory)
        store.save("p|t", ComposerDraft("keep"))
        directory.listFiles()!!.single().writeText("{broken")
        assertTrue(runCatching { store.load("p|t") }.isFailure)
        assertEquals("{broken", directory.listFiles()!!.single().readText())
    }
}