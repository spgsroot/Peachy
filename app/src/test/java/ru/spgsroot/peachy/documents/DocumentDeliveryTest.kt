package ru.spgsroot.peachy.documents

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentDeliveryTest {

    private var opens = 0
    private val sink = ByteArrayOutputStream()

    private fun open(): java.io.OutputStream {
        opens++
        return sink
    }

    @Test
    fun aMissingFileNeverOpensTheTarget() {
        val result = DocumentDelivery.deliver(File("does-not-exist.pdf"), DocumentKind.PDF, ::open)
        assertTrue(result.isFailure)
        assertEquals(0, opens)
    }

    @Test
    fun anEmptyFileNeverTruncatesTheTarget() {
        val empty = tempFile(ByteArray(0))
        val result = DocumentDelivery.deliver(empty, DocumentKind.PDF, ::open)
        assertTrue(result.isFailure)
        assertEquals(0, opens)
    }

    @Test
    fun aPdfDeliveryRequiresPdfBytes() {
        val notPdf = tempFile("{\\rtf1 not a pdf}".toByteArray())
        val result = DocumentDelivery.deliver(notPdf, DocumentKind.PDF, ::open)
        assertTrue(result.isFailure)
        assertEquals(0, opens)
    }

    @Test
    fun anImageDeliveryRequiresKnownImageBytes() {
        val notImage = tempFile("plain text".toByteArray())
        val result = DocumentDelivery.deliver(notImage, DocumentKind.IMAGE, ::open)
        assertTrue(result.isFailure)
        assertEquals(0, opens)
    }

    @Test
    fun aCompleteExportIsStreamedVerbatim() {
        val bytes = "%PDF-1.7\nsecond line\n".toByteArray()
        val produced = tempFile(bytes)

        val result = DocumentDelivery.deliver(produced, DocumentKind.PDF, ::open)

        assertTrue(result.isSuccess)
        assertEquals(1, opens)
        assertArrayEquals(bytes, sink.toByteArray())
    }

    @Test
    fun aWordExportIsNotHeaderCheckedButStillMustBeNonEmpty() {
        val produced = tempFile("PK\u0003\u0004fake package".toByteArray())
        assertTrue(DocumentDelivery.deliver(produced, DocumentKind.WORD, ::open).isSuccess)
        assertEquals(1, opens)
    }

    @Test
    fun aPlainTextExportIsNotHeldToThePdfHeader() {
        // The PDF editor's text export is produced from a PDF session; validating it as a PDF
        // rejected every .txt export before delivery.
        val produced = tempFile("page 1\nпривет\n".toByteArray(Charsets.UTF_8))
        val result = DocumentDelivery.deliver(produced, null, ::open)
        assertTrue(result.isSuccess)
        assertEquals(1, opens)
        assertArrayEquals("page 1\nпривет\n".toByteArray(Charsets.UTF_8), sink.toByteArray())
    }

    @Test
    fun aTargetThatCannotBeOpenedIsReported() {
        val produced = tempFile("%PDF-1.7\n".toByteArray())
        val result = DocumentDelivery.deliver(
            produced = produced,
            kind = DocumentKind.PDF,
            openTarget = { throw IOException("no target") }
        )
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()!!.message.isNullOrBlank())
    }

    private fun tempFile(bytes: ByteArray): File =
        File.createTempFile("export", ".bin").apply {
            deleteOnExit()
            writeBytes(bytes)
        }
}
