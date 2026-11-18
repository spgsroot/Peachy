package ru.spgsroot.peachy.documents

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DocumentSnifferTest {

    // ---- images --------------------------------------------------------------------------

    @Test
    fun pdfIsRecognisedByItsHeader() {
        assertEquals(DocumentFormat.PDF, sniff("%PDF-1.7\n1 0 obj\n".toByteArray(), "report.pdf").format)
    }

    @Test
    fun imageFormatsAreRecognisedByMagic() {
        assertEquals(DocumentFormat.PNG, sniff(png(), null).format)
        assertEquals(DocumentFormat.JPG, sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(16), null).format)
        assertEquals(DocumentFormat.BMP, sniff(bmp(), null).format)
        assertEquals(DocumentFormat.WEBP, sniff(webp(animated = false), null).format)
        assertEquals(DocumentFormat.HEIC, sniff(heic("heic"), null).format)
    }

    @Test
    fun anAnimatedWebpIsFlaggedSoOnlyTheFirstFrameIsClaimed() {
        val detection = sniff(webp(animated = true), "clip.webp")
        assertEquals(DocumentFormat.WEBP, detection.format)
        assertTrue(detection.notes.contains(DetectNote.ANIMATED_WEBP))
    }

    @Test
    fun avifIsRefusedByNameRatherThanMislabelled() {
        assertFailure(DocumentFormatError.UNSUPPORTED_FORMAT) { sniff(heic("avif"), null) }
    }

    // ---- OOXML / ODF ---------------------------------------------------------------------

    @Test
    fun ooxmlMainPartContentTypesDecideTheExactFormat() {
        val cases = mapOf(
            DocumentFormat.DOCX to ("/word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"),
            DocumentFormat.DOCM to ("/word/document.xml" to "application/vnd.ms-word.document.macroEnabled.main+xml"),
            DocumentFormat.DOTX to ("/word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.template.main+xml"),
            DocumentFormat.XLSX to ("/xl/workbook.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"),
            DocumentFormat.XLSM to ("/xl/workbook.xml" to "application/vnd.ms-excel.sheet.macroEnabled.main+xml"),
            DocumentFormat.XLSB to ("/xl/workbook.xml" to "application/vnd.ms-excel.sheet.binary.macroEnabled.main+xml"),
            DocumentFormat.PPTX to ("/ppt/presentation.xml" to "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"),
            DocumentFormat.PPSX to ("/ppt/presentation.xml" to "application/vnd.openxmlformats-officedocument.presentationml.slideshow.main+xml"),
            DocumentFormat.POTX to ("/ppt/presentation.xml" to "application/vnd.openxmlformats-officedocument.presentationml.template.main+xml"),
        )
        for ((expected, part) in cases) {
            val file = ooxml(part)
            val detection = DocumentSniffer.sniff(file, "document.${expected.extension}")
            assertEquals("content type for ${expected.id}", expected, detection.format)
            assertTrue("content type detection is exact", detection.exact)
        }
    }

    @Test
    fun aMacroEnabledPackageIsNotAcceptedAsItsPlainSibling() {
        assertFailure(DocumentFormatError.MISMATCH) {
            DocumentSniffer.sniff(ooxml(macroPart()), "notes.docx")
        }
        assertFailure(DocumentFormatError.MISMATCH) {
            DocumentSniffer.sniff(ooxml(plainPart()), "notes.docm")
        }
    }

    @Test
    fun aMacroEnabledTemplateIsNamedAsUnsupported() {
        assertFailure(DocumentFormatError.UNSUPPORTED_FORMAT) {
            DocumentSniffer.sniff(
                ooxml(
                    "/word/document.xml" to "application/vnd.ms-word.template.macroEnabledTemplate.main+xml"
                ),
                null
            )
        }
    }

    @Test
    fun theFamilyFallsBackToPartNamesWhenContentTypesHaveNoOverride() {
        val file = zip(
            "[Content_Types].xml" to "<Types><Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>".toByteArray(),
            "word/document.xml" to ByteArray(8),
        )
        val detection = DocumentSniffer.sniff(file, null)
        assertEquals(DocumentFormat.DOCX, detection.format)
        assertEquals(false, detection.exact)
        assertTrue(detection.notes.contains(DetectNote.ZIP_PART_NAMES))
    }

    @Test
    fun aPackageWithTwoMainPartsIsRejectedAsAPolyglot() {
        val file = zip(
            "[Content_Types].xml" to contentTypes(
                "/word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml",
                "/xl/workbook.xml" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
            ),
        )
        assertFailure(DocumentFormatError.CORRUPT) { DocumentSniffer.sniff(file, null) }
    }

    @Test
    fun aZipWithoutAnyDocumentMarkersIsNotGuessed() {
        val file = zip("payload.txt" to "hello".toByteArray())
        assertFailure(DocumentFormatError.UNKNOWN_FORMAT) { DocumentSniffer.sniff(file, null) }
    }

    @Test
    fun anArchiveWithTooManyEntriesIsRefusedBeforeItIsInspected() {
        val file = File.createTempFile("entries", ".zip").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            repeat(600) { index ->
                zip.putNextEntry(ZipEntry("entry$index.xml"))
                zip.write(byteArrayOf(0))
                zip.closeEntry()
            }
        }
        assertFailure(DocumentFormatError.CORRUPT) { DocumentSniffer.sniff(file, null) }
    }

    @Test
    fun anOversizedContentTypesPartIsRefused() {
        val huge = ByteArray(1100 * 1024) { 'a'.code.toByte() }
        val file = zip("[Content_Types].xml" to huge)
        assertFailure(DocumentFormatError.CORRUPT) { DocumentSniffer.sniff(file, null) }
    }

    @Test
    fun openDocumentMimetypeDecidesTheOdfFormat() {
        assertEquals(
            DocumentFormat.ODT,
            DocumentSniffer.sniff(
                zip("mimetype" to "application/vnd.oasis.opendocument.text".toByteArray()),
                "letter.odt"
            ).format
        )
        assertEquals(
            DocumentFormat.ODS,
            DocumentSniffer.sniff(
                zip("mimetype" to "application/vnd.oasis.opendocument.spreadsheet".toByteArray()),
                "sheet.ods"
            ).format
        )
        assertFailure(DocumentFormatError.UNSUPPORTED_FORMAT) {
            DocumentSniffer.sniff(
                zip("mimetype" to "application/vnd.oasis.opendocument.presentation".toByteArray()),
                null
            )
        }
    }

    // ---- legacy OLE2 ---------------------------------------------------------------------

    @Test
    fun legacyOfficeFilesAreDecidedByStreamNames() {
        assertEquals(DocumentFormat.DOC, sniff(ole2("WordDocument"), "old.doc").format)
        assertEquals(DocumentFormat.XLS, sniff(ole2("Workbook"), "old.xls").format)
        assertEquals(DocumentFormat.XLS, sniff(ole2("Book"), "old.xls").format)
        assertEquals(DocumentFormat.PPT, sniff(ole2("PowerPoint Document"), "old.ppt").format)
    }

    @Test
    fun anOle2ContainerWithoutKnownStreamsIsNotGuessed() {
        assertFailure(DocumentFormatError.UNKNOWN_FORMAT) {
            sniff(ole2("SomeOtherStream"), null)
        }
    }

    @Test
    fun anEncryptedLegacyPackageIsRefused() {
        assertFailure(DocumentFormatError.UNSUPPORTED_FORMAT) {
            sniff(ole2("EncryptedPackage"), null)
        }
    }

    // ---- text ----------------------------------------------------------------------------

    @Test
    fun textFormatsTakeTheirLabelFromTheDeclaredExtension() {
        val text = "Привет, это заметка.\nвторая строка\n".toByteArray()
        val markdown = sniff(text, "notes.md")
        assertEquals(DocumentFormat.MD, markdown.format)
        assertEquals(false, markdown.exact)
        assertTrue(markdown.notes.contains(DetectNote.TEXT_BY_EXTENSION))
        assertEquals(DocumentFormat.CSV, sniff(text, "table.csv").format)
        assertEquals(DocumentFormat.TSV, sniff(text, "table.tsv").format)
        assertEquals(DocumentFormat.TXT, sniff(text, "notes.txt").format)
        assertEquals(DocumentFormat.TXT, sniff(text, null).format)
    }

    @Test
    fun rtfIsProvenByItsHeader() {
        val rtf = "{\\rtf1\\ansi Привет}".toByteArray()
        val detection = sniff(rtf, "letter.rtf")
        assertEquals(DocumentFormat.RTF, detection.format)
        assertTrue(detection.exact)
    }

    @Test
    fun utf16WithABomIsStillText() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hello".toByteArray(Charsets.UTF_16LE)
        assertEquals(DocumentFormat.TXT, sniff(bytes, null).format)
    }

    @Test
    fun binaryContentIsNotMistakenForText() {
        val binary = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x80.toByte(), 0x81.toByte(), 0x00, 0x00)
        assertFailure(DocumentFormatError.UNKNOWN_FORMAT) { sniff(binary, null) }
    }

    // ---- mismatches and limits -----------------------------------------------------------

    @Test
    fun aDeclaredExtensionThatDisagreesWithTheBytesIsRefused() {
        assertFailure(DocumentFormatError.MISMATCH) { sniff(png(), "invoice.pdf") }
        assertFailure(DocumentFormatError.MISMATCH) { sniff(png(), "photo.jpg") }
        assertFailure(DocumentFormatError.MISMATCH) { sniff("just text".toByteArray(), "invoice.pdf") }
        assertFailure(DocumentFormatError.MISMATCH) {
            DocumentSniffer.sniff(ooxml(plainPart()), "notes.txt")
        }
        assertFailure(DocumentFormatError.MISMATCH) {
            sniff("{\\rtf1 x}".toByteArray(), "notes.md")
        }
    }

    @Test
    fun imageAliasesAcceptTheirAlternativeExtensions() {
        assertEquals(DocumentFormat.JPG, sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "photo.jpeg").format)
        assertEquals(DocumentFormat.MD, sniff("text".toByteArray(), "readme.markdown").format)
        assertEquals(DocumentFormat.HEIC, sniff(heic("heic"), "photo.heif").format)
    }

    @Test
    fun theFamilyCapsMatchTheEngineReport() {
        assertEquals(100L * MIB, DocumentFormat.PDF.capBytes)
        assertEquals(50L * MIB, DocumentFormat.DOCX.capBytes)
        assertEquals(25L * MIB, DocumentFormat.XLSX.capBytes)
        assertEquals(100L * MIB, DocumentFormat.PPTX.capBytes)
        assertEquals(100L * MIB, DocumentFormat.PNG.capBytes)
    }

    @Test
    fun aFileOverItsFamilyCapIsRefusedAfterTheHeaderWasRecognised() {
        val file = File.createTempFile("bigpdf", ".pdf").apply { deleteOnExit() }
        RandomAccessFile(file, "rw").use { raf ->
            raf.write("%PDF-1.7\n".toByteArray())
            raf.setLength(DocumentFormat.PDF.capBytes + 1)
        }
        assertFailure(DocumentFormatError.TOO_LARGE) { DocumentSniffer.sniff(file, "big.pdf") }
    }

    @Test
    fun anEmptyFileIsTypedAsEmpty() {
        assertFailure(DocumentFormatError.EMPTY) { sniff(ByteArray(0), null) }
    }

    @Test
    fun thePickerOffersADeclaredFormatFallbackForOctetStreamProviders() {
        assertTrue(DocumentFormat.pickerMimeTypes.contains("application/octet-stream"))
        assertTrue(DocumentFormat.pickerMimeTypes.contains("application/pdf"))
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun sniff(bytes: ByteArray, declared: String?): Detection =
        DocumentSniffer.sniff(writeFile(bytes), declared)

    private fun assertFailure(reason: DocumentFormatError, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (failure: DocumentFormatException) {
            assertEquals("wrong reason: ${failure.message}", reason, failure.reason)
        }
    }

    private fun writeFile(bytes: ByteArray): File =
        File.createTempFile("sniff", ".bin").apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val file = File.createTempFile("archive", ".zip").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun contentTypes(vararg overrides: Pair<String, String>): ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        for ((part, type) in overrides) {
            append("<Override PartName=\"").append(part).append("\" ContentType=\"").append(type).append("\"/>")
        }
        append("</Types>")
    }.toByteArray()

    private fun ooxml(part: Pair<String, String>): File =
        zip(
            "[Content_Types].xml" to contentTypes(part),
            "docProps/core.xml" to ByteArray(16),
        )

    private fun plainPart() =
        "/word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"

    private fun macroPart() = "/word/document.xml" to "application/vnd.ms-word.document.macroEnabled.main+xml"

    private fun png(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D
    )

    private fun bmp(): ByteArray {
        val bytes = ByteArray(64)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        bytes[2] = 64.toByte()
        bytes[10] = 54.toByte()
        return bytes
    }

    private fun webp(animated: Boolean): ByteArray {
        val bytes = ByteArray(64)
        "RIFF".toByteArray().copyInto(bytes, 0)
        "WEBP".toByteArray().copyInto(bytes, 8)
        if (animated) {
            "VP8X".toByteArray().copyInto(bytes, 12)
            bytes[20] = 0x02.toByte()
        } else {
            "VP8 ".toByteArray().copyInto(bytes, 12)
        }
        return bytes
    }

    private fun heic(brand: String): ByteArray {
        val bytes = ByteArray(32)
        bytes[3] = 24
        "ftyp".toByteArray().copyInto(bytes, 4)
        brand.toByteArray().copyInto(bytes, 8)
        "mif1".toByteArray().copyInto(bytes, 16)
        return bytes
    }

    /** A minimal 512-byte-sector OLE2 file with one root child stream named [streamName]. */
    private fun ole2(streamName: String): ByteArray {
        val sectorSize = 512
        val bytes = ByteArray(sectorSize * 3)
        val header = bytes
        // Signature
        byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
        ).copyInto(header, 0)
        putShort(header, 24, 0x3E) // minor version
        putShort(header, 26, 3) // major version
        putShort(header, 28, 0xFFFE) // little endian
        putShort(header, 30, 9) // sector shift => 512
        putShort(header, 32, 6) // mini sector shift
        putInt(header, 40, 0) // directory sectors
        putInt(header, 44, 1) // FAT sectors
        putInt(header, 48, 1) // first directory sector
        putInt(header, 56, 4096) // mini stream cutoff
        putInt(header, 60, -2) // no mini FAT
        putInt(header, 64, 0)
        putInt(header, 68, -2) // no DIFAT chain
        putInt(header, 72, 0)
        // DIFAT: first FAT sector is 0, the rest unused.
        putInt(header, 76, 0)
        for (index in 1 until 109) putInt(header, 76 + index * 4, -1)

        // FAT at sector 0 (offset 512): sector 0 = FATSECT, sector 1 = end of chain.
        val fatOffset = sectorSize
        putInt(bytes, fatOffset, -3)
        putInt(bytes, fatOffset + 4, -2)
        for (index in 2 until 128) putInt(bytes, fatOffset + index * 4, -1)

        // Directory at sector 1 (offset 1024): root entry then one stream entry.
        val directoryOffset = sectorSize * 2
        writeDirectoryEntry(bytes, directoryOffset, "Root Entry", type = 5, child = 1)
        writeDirectoryEntry(bytes, directoryOffset + 128, streamName, type = 2, child = -1)
        return bytes
    }

    private fun writeDirectoryEntry(
        target: ByteArray,
        offset: Int,
        name: String,
        type: Int,
        child: Int,
    ) {
        val nameBytes = name.toByteArray(Charsets.UTF_16LE)
        nameBytes.copyInto(target, offset)
        putShort(target, offset + 64, nameBytes.size + 2)
        target[offset + 66] = type.toByte()
        putInt(target, offset + 68, -1) // left sibling
        putInt(target, offset + 72, -1) // right sibling
        putInt(target, offset + 76, child)
    }

    private fun putShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
