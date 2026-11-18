package ru.spgsroot.peachy.documents

import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.spgsroot.peachy.data.db.DocumentSessionEntity

class DocumentStoreTest {

    private val dao = FakeDocumentSessionDao()
    private lateinit var root: File

    private fun store(available: () -> Long = { Long.MAX_VALUE }): DocumentStore {
        root = File.createTempFile("documents", "root").let { probe ->
            probe.delete()
            probe.mkdirs()
            probe.deleteOnExit()
            probe
        }
        return DocumentStore(root, dao, available)
    }

    @Test
    fun anImportCommitsSourceWorkingCopyAndRow() = runBlocking {
        val store = store()
        val session = store.importSession(
            displayName = "Quarterly report.pdf",
            sourceMime = "application/pdf",
            declaredBytes = PDF.size.toLong(),
            open = { ByteArrayInputStream(PDF) },
        )

        assertEquals(DocumentFormat.PDF.id, session.format)
        assertEquals(DocumentKind.PDF, session.kind)
        assertEquals(false, session.dirty)
        val directory = session.workingFile.parentFile!!
        assertTrue(File(directory, "source.pdf").isFile)
        assertEquals(PDF.toList(), session.workingFile.readBytes().toList())
        assertEquals(directory.absolutePath, store.sessionDir(session.id).absolutePath)
        val row = dao.getById(session.id)
        assertNotNull(row)
        assertEquals(false, row!!.dirty)
        assertEquals(session.workingFile.absolutePath, row.workingPath)
    }

    @Test
    fun aMismatchedFileLeavesNoDirectoryAndNoRow() = runBlocking {
        val store = store()
        val idsBefore = dao.rows.size
        try {
            store.importSession(
                displayName = "report.pdf",
                sourceMime = "application/pdf",
                declaredBytes = 16,
                open = { ByteArrayInputStream("plain text here".toByteArray()) },
            )
            fail("expected a mismatch")
        } catch (failure: DocumentFormatException) {
            assertEquals(DocumentFormatError.MISMATCH, failure.reason)
        }
        assertEquals(idsBefore, dao.rows.size)
        assertEquals(0, root.listFiles()?.count { it.isDirectory } ?: 0)
    }

    @Test
    fun aFailedInsertRollsBackTheFiles() = runBlocking {
        val store = store()
        dao.failInsert = true
        try {
            store.importSession("notes.txt", "text/plain", 5, { ByteArrayInputStream("hello".toByteArray()) })
            fail("expected an insert failure")
        } catch (failure: IllegalStateException) {
            // expected
        }
        assertFalse(root.listFiles()?.any { it.isDirectory } ?: false)
        assertEquals(0, dao.rows.size)
    }

    @Test
    fun aKnownOversizeImportIsRefusedBeforeTheCopy() = runBlocking {
        val store = store()
        var opened = false
        try {
            store.importSession("huge.pdf", "application/pdf", ABSOLUTE_MAX_DOCUMENT_BYTES + 1, {
                opened = true
                ByteArrayInputStream(PDF)
            })
            fail("expected a cap failure")
        } catch (failure: DocumentFormatException) {
            assertEquals(DocumentFormatError.TOO_LARGE, failure.reason)
        }
        assertFalse(opened)
    }

    @Test
    fun aTextImportTakesTheDeclaredLabel() = runBlocking {
        val store = store()
        val session = store.importSession(
            "notes.md", "text/markdown", 11,
            { ByteArrayInputStream("# hello\nok".toByteArray()) }
        )
        assertEquals(DocumentFormat.MD.id, session.format)
        assertEquals(DocumentKind.WORD, session.kind)
        assertTrue(File(session.workingFile.parentFile, "source.md").isFile)
    }

    @Test
    fun markDirtyAndPositionArePersistedWithTheRow() = runBlocking {
        val store = store()
        val session = store.importSession("a.pdf", null, PDF.size.toLong(), { ByteArrayInputStream(PDF) })

        store.markDirty(session.id)
        store.saveLastPosition(session.id, page = 7, scroll = 320)

        val row = dao.getById(session.id)!!
        assertTrue(row.dirty)
        assertEquals(7, row.lastPage)
        assertEquals(320, row.lastScroll)
        assertEquals(true, store.get(session.id)?.dirty)
    }

    @Test
    fun refusedImagePublicationKeepsThePreviousRasterAndItsHistory() = runBlocking {
        val store = store()
        val session = store.importSession("photo.jpg", "image/jpeg", JPG.size.toLong(), { ByteArrayInputStream(JPG) })
        val directory = session.workingFile.parentFile!!
        val first = store.publishImageEdit(session.id, session.workingFile, "[first]") { it.write(1) }
        assertEquals(first, store.get(session.id)!!.workingFile)
        assertTrue(store.get(session.id)!!.dirty)
        val entries = directory.list()!!.toSet()

        dao.failPublication = true
        try {
            store.publishImageEdit(session.id, first, "[next]") { it.write(2) }
            fail("expected the pointer update to fail after staging the files")
        } catch (failure: IllegalStateException) {
            assertEquals("publication refused", failure.message)
        }
        assertEquals(first, store.get(session.id)!!.workingFile)
        assertEquals(1.toByte(), first.readBytes().single())
        assertEquals("[first]", imageHistoryFile(first).readText())
        assertEquals(entries, directory.list()!!.toSet())
    }

    @Test
    fun discardDeletesTheDirectoryThenTheRow() = runBlocking {
        val store = store()
        val session = store.importSession("a.pdf", null, PDF.size.toLong(), { ByteArrayInputStream(PDF) })
        val directory = store.sessionDir(session.id)

        val result = store.discard(session.id)

        assertTrue(result.isSuccess)
        assertFalse(directory.exists())
        assertNull(dao.getById(session.id))
    }

    @Test
    fun aRowThatCannotBeDeletedIsReportedEvenAfterTheFilesAreGone() = runBlocking {
        val store = store()
        val session = store.importSession("a.pdf", null, PDF.size.toLong(), { ByteArrayInputStream(PDF) })
        dao.failDelete = true

        val result = store.discard(session.id)

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun discardingAnUnusableIdFails() = runBlocking {
        val store = store()
        val result = store.discard("../../etc")
        assertTrue(result.isFailure)
    }

    @Test
    fun aRowPointingOutsideItsSessionIsNotOpened() = runBlocking {
        val store = store()
        val outside = File(root.parentFile, "elsewhere.pdf").apply { writeBytes(PDF) }
        val id = "2f1c7f4e-1d2b-4a3c-9b8e-0123456789ab"
        dao.rows[id] = DocumentSessionEntity(
            id = id,
            displayName = "evil.pdf",
            sourceMime = null,
            format = "pdf",
            kind = "PDF",
            workingPath = outside.absolutePath,
            dirty = false,
            createdAt = 0,
            updatedAt = 0,
            lastPage = null,
            lastScroll = null,
        )

        assertNull(store.get(id))
    }

    @Test
    fun anUnknownSessionIsNull() = runBlocking {
        val store = store()
        assertNull(store.get("2f1c7f4e-1d2b-4a3c-9b8e-0123456789ab"))
        assertNull(store.get("not-a-uuid"))
    }

    private companion object {
        val PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\ntrailer\n".toByteArray()
        val JPG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(32)
    }
}
