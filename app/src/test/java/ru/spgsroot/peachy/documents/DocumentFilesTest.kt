package ru.spgsroot.peachy.documents

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocumentFilesTest {

    private fun files(root: File = tempDir(), available: () -> Long = { Long.MAX_VALUE }) =
        DocumentFiles(root, available)

    @Test
    fun aCopyThatExceedsTheCapIsRemovedAndReported() {
        val files = files()
        val id = files.newSessionId()
        try {
            files.copyPending(id, ByteArray(200) { 1 }.inputStream(), 200, maxBytes = 100)
            fail("expected a cap failure")
        } catch (failure: DocumentFormatException) {
            assertEquals(DocumentFormatError.TOO_LARGE, failure.reason)
        }
        assertFalse(File(files.dir(id), DocumentFiles.PENDING_NAME).exists())
    }

    @Test
    fun aKnownOversizeSourceIsRefusedBeforeItIsRead() {
        val files = files()
        val id = files.newSessionId()
        var reads = 0
        val source = object : java.io.InputStream() {
            override fun read(): Int {
                reads++
                return 1
            }
        }
        try {
            files.copyPending(id, source, declaredBytes = 200, maxBytes = 100)
            fail("expected a cap failure")
        } catch (failure: DocumentFormatException) {
            assertEquals(DocumentFormatError.TOO_LARGE, failure.reason)
        }
        assertEquals(0, reads)
    }

    @Test
    fun cancellationBetweenChunksLeavesNoPartialFile() {
        val files = files()
        val id = files.newSessionId()
        try {
            files.copyPending(id, ByteArray(64 * 1024 * 4).inputStream(), -1, Long.MAX_VALUE) { false }
            fail("expected cancellation")
        } catch (failure: java.io.InterruptedIOException) {
            // expected
        }
        assertFalse(File(files.dir(id), DocumentFiles.PENDING_NAME).exists())
    }

    @Test
    fun anAtomicWriteReplacesTheWholeFileAndLeavesNoTemp() {
        val files = files()
        val id = files.newSessionId()
        val target = File(files.dir(id), "working.png")
        target.parentFile!!.mkdirs()
        target.writeBytes(ByteArray(4096) { 7 })

        files.writeAtomically(target) { stream -> stream.write("new content".toByteArray()) }

        assertEquals("new content", target.readText())
        assertFalse(File(target.parentFile, "${target.name}.tmp").exists())
    }

    @Test
    fun sessionPathsAreUuidsOnly() {
        val files = files()
        assertFalse(DocumentFiles.isSessionId("../escape"))
        assertFalse(DocumentFiles.isSessionId(""))
        assertFalse(DocumentFiles.isSessionId(null))
        assertTrue(DocumentFiles.isSessionId(files.newSessionId()))
        try {
            files.dir("../escape")
            fail("a traversal id must be refused")
        } catch (failure: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun containmentUsesCanonicalPaths() {
        val root = tempDir()
        val files = files(root)
        val id = files.newSessionId()
        assertTrue(files.contains(File(files.sessionDir(id), "working.png")))
        assertFalse(files.contains(File(root.parentFile, "other/working.png")))
    }

    @Test
    fun deletingASessionRemovesItsWholeDirectory() {
        val files = files()
        val id = files.newSessionId()
        val directory = files.sessionDir(id)
        File(directory, "exports/second.png").apply { parentFile!!.mkdirs() }.writeText("x")
        File(directory, "working.png").writeText("y")

        assertTrue(files.deleteSession(id))
        assertFalse(directory.exists())
    }

    @Test
    fun theFreeSpaceReserveIsCheckedBeforeACopy() {
        val files = files(available = { MIN_FREE_BYTES - 1 })
        val id = files.newSessionId()
        try {
            files.copyPending(id, "x".byteInputStream(), 1, maxBytes = 10)
            fail("expected a space failure")
        } catch (failure: DocumentFormatException) {
            assertEquals(DocumentFormatError.NOT_ENOUGH_SPACE, failure.reason)
        }
    }

    @Test
    fun exportFilesStayInsideTheExportsDirectory() {
        val files = files()
        val id = files.newSessionId()
        val export = files.createExport(id, "png")
        assertEquals(files.exportDir(id).canonicalFile, export.parentFile!!.canonicalFile)
        assertTrue(export.name.endsWith(".png"))
        export.delete()
    }

    @Test
    fun suggestedNamesDropPathsControlCharactersAndTakeTheSniffedExtension() {
        assertEquals("Report.pdf", suggestedDocumentName("docs/Report.pdf", DocumentFormat.PDF))
        assertEquals("a b.txt", suggestedDocumentName("a\u0000b.txt", DocumentFormat.TXT))
        assertEquals("Document.docx", suggestedDocumentName(null, DocumentFormat.DOCX))
        assertEquals("Document.docx", suggestedDocumentName("....", DocumentFormat.DOCX))
        assertEquals("evil.txt", suggestedDocumentName("../../evil.jpg", DocumentFormat.TXT))
        val long = suggestedDocumentName("x".repeat(400), DocumentFormat.PDF)
        assertTrue(long.length <= 124)
        assertTrue(long.endsWith(".pdf"))
    }

    @Test
    fun aSourceNameIsTheSniffedFormatNeverTheDeclaredOne() {
        assertEquals("source.pdf", DocumentFiles.sourceName(DocumentFormat.PDF))
        assertEquals("source.docm", DocumentFiles.sourceName(DocumentFormat.DOCM))
        assertEquals("source.heic", DocumentFiles.sourceName(DocumentFormat.HEIC))
    }

    @Test
    fun aWorkingFileOutsideTheSessionDirectoryIsRefused() = runBlocking {
        val root = tempDir()
        val store = DocumentStore(root, FakeDocumentSessionDao())
        val id = UUID_SAMPLE
        root.resolve(id).mkdirs()
        try {
            store.publishImageEdit(id, File(root.parentFile, "elsewhere.png"), "[]") { it.write(1) }
            fail("expected a containment failure")
        } catch (failure: java.io.IOException) {
            assertNotNull(failure.message)
        }
    }

    private fun tempDir(): File =
        File.createTempFile("documents", "root").let { probe ->
            probe.delete()
            probe.mkdirs()
            probe.deleteOnExit()
            probe
        }

    private companion object {
        const val UUID_SAMPLE = "2f1c7f4e-1d2b-4a3c-9b8e-0123456789ab"
    }
}
