package ru.spgsroot.peachy.documents

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Byte-level format detection. Nothing here trusts a display name or a provider MIME: the declared
 * extension only labels the text family (md/txt/csv/tsv cannot be told apart from their bytes) and is
 * otherwise used to *refuse* a file whose bytes disagree with it.
 *
 * Proven by bytes: pdf, png, jpg, bmp, webp, heic, the ZIP container itself, OOXML/ODF content types
 * and the OLE2 stream names. Heuristic: which OOXML family is meant when `[Content_Types].xml` has
 * no usable override (part names decide then), every md/txt/csv/tsv choice, and the fact that OLE2
 * names only a family, not a version. Both are reported through [Detection.notes].
 */
internal object DocumentSniffer {

    private const val HEADER_BYTES = 64
    private const val TEXT_SAMPLE_BYTES = 1 shl 20
    private const val MAX_ZIP_ENTRIES = 512
    private const val MAX_ZIP_ENTRY_BYTES = 256L * MIB
    private const val MAX_ZIP_RATIO = 1000L
    private const val MAX_CONTENT_TYPES_BYTES = 1L * MIB
    private const val MAX_OLE2_DIRECTORY_SECTORS = 32

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B)
    private val OLE2_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()
    )
    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    private val HEIF_BRANDS = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1")
    private val AVIF_BRANDS = setOf("avif", "avis")

    fun sniff(file: File, declaredName: String? = null): Detection {
        val declared = DocumentFormat.fromName(declaredName)
        val head = file.inputStream().use { readAtMost(it, HEADER_BYTES) }
        if (head.isEmpty()) throw DocumentFormatException(DocumentFormatError.EMPTY, "empty file")
        val detection = detect(file, head)
        val cap = detection.format.capBytes
        if (file.length() > cap) {
            throw DocumentFormatException(
                DocumentFormatError.TOO_LARGE,
                "${detection.format.id} is limited to $cap bytes, file is ${file.length()}"
            )
        }
        return reconcile(declared, detection)
    }

    private fun detect(file: File, head: ByteArray): Detection = when {
        head.startsWithAscii("%PDF-") -> Detection(DocumentFormat.PDF, exact = true)
        head.startsWith(PNG_MAGIC) -> Detection(DocumentFormat.PNG, exact = true)
        head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() ->
            Detection(DocumentFormat.JPG, exact = true)

        isBmp(head, file.length()) -> Detection(DocumentFormat.BMP, exact = true)
        head.size >= 12 && head.startsWithAscii("RIFF") && head.ascii(8, 4) == "WEBP" -> {
            val animated = head.size >= 21 && head.ascii(12, 4) == "VP8X" && (head[20].toInt() and 0x02) != 0
            Detection(
                DocumentFormat.WEBP,
                exact = true,
                notes = if (animated) setOf(DetectNote.ANIMATED_WEBP) else emptySet()
            )
        }

        isIsoBmff(head) -> sniffIsoBmff(head)
        head.startsWith(ZIP_MAGIC) -> sniffZip(file)
        head.startsWith(OLE2_MAGIC) -> sniffOle2(file)
        else -> sniffText(file, head)
    }

    // ---- images ----------------------------------------------------------------------------

    private fun isBmp(head: ByteArray, fileLength: Long): Boolean {
        if (head.size < 18 || head[0] != 'B'.code.toByte() || head[1] != 'M'.code.toByte()) return false
        val pixelOffset = head.intLe(10)
        // A bare "BM" is too weak: the pixel-data offset must land inside the file.
        return pixelOffset >= 26 && pixelOffset.toLong() <= fileLength
    }

    private fun isIsoBmff(head: ByteArray): Boolean = head.size >= 12 && head.ascii(4, 4) == "ftyp"

    private fun sniffIsoBmff(head: ByteArray): Detection {
        val boxSize = head.intBe(0)
        val brands = mutableListOf(head.ascii(8, 4))
        var offset = 16
        val end = if (boxSize in 16..head.size) boxSize else head.size
        while (offset + 4 <= end) {
            brands += head.ascii(offset, 4)
            offset += 4
        }
        val ids = brands.map { it.trim().lowercase(Locale.ROOT) }
        if (ids.any { it in AVIF_BRANDS }) unsupported("AVIF", "this build decodes HEIC, not AVIF")
        if (ids.any { it in HEIF_BRANDS }) return Detection(DocumentFormat.HEIC, exact = true)
        throw DocumentFormatException(DocumentFormatError.UNKNOWN_FORMAT, "ISO-BMFF container ${ids.firstOrNull()}")
    }

    // ---- ZIP containers --------------------------------------------------------------------

    private fun sniffZip(file: File): Detection {
        val archive = try {
            ZipFile(file)
        } catch (failure: ZipException) {
            throw DocumentFormatException(DocumentFormatError.CORRUPT, "not a readable zip archive", failure)
        } catch (failure: IOException) {
            throw DocumentFormatException(DocumentFormatError.CORRUPT, "not a readable zip archive", failure)
        }
        archive.use { zip ->
            var entries = 0
            val names = HashSet<String>()
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                entries++
                if (entries > MAX_ZIP_ENTRIES) corrupt("archive declares more than $MAX_ZIP_ENTRIES entries")
                names += entry.name
                if (entry.isDirectory) continue
                val size = entry.size
                val compressed = entry.compressedSize
                if (size > MAX_ZIP_ENTRY_BYTES) corrupt("archive entry ${entry.name} expands to $size bytes")
                if (size > MIB && compressed > 0 && size / compressed > MAX_ZIP_RATIO) {
                    corrupt("archive entry ${entry.name} has an implausible compression ratio")
                }
            }

            // ODF: a stored `mimetype` entry names the document class.
            textEntry(zip, "mimetype", 128L)?.trim()?.let { mimetype ->
                if (mimetype.startsWith("application/vnd.oasis.opendocument.")) {
                    return Detection(odfFormat(mimetype), exact = true)
                }
            }

            // OOXML: [Content_Types].xml overrides name the main part of exactly one family.
            val contentTypes = textEntry(zip, "[Content_Types].xml", MAX_CONTENT_TYPES_BYTES)
                ?: throw DocumentFormatException(
                    DocumentFormatError.UNKNOWN_FORMAT,
                    "zip archive without OOXML content types or an ODF mimetype"
                )
            val formats = CONTENT_TYPE_TAGS.findAll(contentTypes)
                .mapNotNull { tag ->
                    CONTENT_TYPE.findAll(tag.value).firstOrNull()?.groupValues?.get(1)?.let(::formatForContentType)
                }
                .toSet()
            when (formats.size) {
                1 -> return Detection(formats.first(), exact = true)
                0 -> Unit
                else -> corrupt("package declares several main parts: ${formats.joinToString { it.id }}")
            }
            // Heuristic fallback: the OOXML package layout still identifies the family.
            val fallback = when {
                names.contains("word/document.xml") -> DocumentFormat.DOCX
                names.contains("xl/workbook.xml") -> DocumentFormat.XLSX
                names.contains("ppt/presentation.xml") -> DocumentFormat.PPTX
                else -> null
            } ?: throw DocumentFormatException(DocumentFormatError.UNKNOWN_FORMAT, "unrecognised zip package")
            return Detection(fallback, exact = false, notes = setOf(DetectNote.ZIP_PART_NAMES))
        }
    }

    private fun odfFormat(mimetype: String): DocumentFormat = when {
        mimetype.startsWith("application/vnd.oasis.opendocument.text") -> DocumentFormat.ODT
        mimetype.startsWith("application/vnd.oasis.opendocument.spreadsheet") -> DocumentFormat.ODS
        mimetype.startsWith("application/vnd.oasis.opendocument.presentation") ->
            unsupported("ODP", "presentation ODF documents are not supported")

        else -> throw DocumentFormatException(
            DocumentFormatError.UNSUPPORTED_FORMAT,
            "unsupported OpenDocument type $mimetype"
        )
    }

    private fun formatForContentType(raw: String): DocumentFormat? {
        val type = raw.lowercase(Locale.ROOT)
        return when {
            type.contains("wordprocessingml.document.main+xml") -> DocumentFormat.DOCX
            type.contains("ms-word.document.macroenabled.main+xml") -> DocumentFormat.DOCM
            type.contains("wordprocessingml.template.main+xml") -> DocumentFormat.DOTX
            type.contains("ms-word.template.macroenabledtemplate.main+xml") ->
                unsupported("DOTM", "macro-enabled templates are not supported")

            type.contains("spreadsheetml.sheet.main+xml") -> DocumentFormat.XLSX
            type.contains("ms-excel.sheet.macroenabled.main+xml") -> DocumentFormat.XLSM
            type.contains("ms-excel.sheet.binary.macroenabled.main+xml") -> DocumentFormat.XLSB
            type.contains("presentationml.presentation.main+xml") -> DocumentFormat.PPTX
            type.contains("presentationml.slideshow.main+xml") -> DocumentFormat.PPSX
            type.contains("presentationml.template.main+xml") -> DocumentFormat.POTX
            type.contains("ms-powerpoint.presentation.macroenabled.main+xml") ->
                unsupported("PPTM", "macro-enabled presentations are not supported")

            else -> null
        }
    }

    private fun textEntry(archive: ZipFile, name: String, maxBytes: Long): String? {
        val entry = archive.getEntry(name) ?: return null
        if (entry.isDirectory) return null
        if (entry.size > maxBytes) corrupt("$name is larger than $maxBytes bytes")
        return archive.getInputStream(entry).use { stream ->
            // Capped read: a lying central directory cannot make this allocate more than maxBytes.
            val limit = minOf(maxBytes, 1L shl 20).toInt()
            val bytes = readAtMost(stream, limit)
            if (bytes.size >= limit && entry.size > limit) corrupt("$name is larger than $maxBytes bytes")
            bytes.toString(Charsets.UTF_8)
        }
    }

    // ---- OLE2 (legacy Office) --------------------------------------------------------------

    private fun sniffOle2(file: File): Detection {
        val names = Ole2Probe.streamNames(file)
        val format = when {
            names.contains("WordDocument") -> DocumentFormat.DOC
            names.contains("Workbook") || names.contains("Book") -> DocumentFormat.XLS
            names.contains("PowerPoint Document") -> DocumentFormat.PPT
            names.contains("EncryptedPackage") ->
                unsupported("encrypted Office", "password-protected legacy files cannot be opened")

            else -> throw DocumentFormatException(
                DocumentFormatError.UNKNOWN_FORMAT,
                "OLE2 container with unrecognised streams: ${names.take(8).joinToString()}"
            )
        }
        return Detection(format, exact = true, notes = setOf(DetectNote.OLE2_STREAM_NAMES))
    }

    private object Ole2Probe {
        private const val HEADER_BYTES = 512
        private const val DIRECTORY_ENTRY_BYTES = 128
        private const val MAX_ENTRY_VISITS = 512

        fun streamNames(file: File): Set<String> {
            if (file.length() > ABSOLUTE_MAX_DOCUMENT_BYTES) corrupt("OLE2 file exceeds the import cap")
            java.io.RandomAccessFile(file, "r").use { handle ->
                val fileLength = handle.length()
                if (fileLength < HEADER_BYTES) corrupt("truncated OLE2 header")
                val header = ByteArray(HEADER_BYTES)
                handle.readFully(header)
                val sectorShift = header.u16Le(30)
                if (sectorShift != 9 && sectorShift != 12) corrupt("unexpected OLE2 sector size")
                val sectorSize = 1 shl sectorShift
                val fatCount = header.intLe(44)
                val firstDirectorySector = header.intLe(48)
                if (fatCount <= 0 || fatCount > 109) corrupt("OLE2 FAT is too fragmented to inspect")
                if (firstDirectorySector < 0) corrupt("OLE2 has no directory")

                // Only the FAT and the directory are read: a 100 MiB container never lands in memory.
                val fat = IntArray(fatCount * (sectorSize / 4))
                var fatWrite = 0
                for (index in 0 until fatCount) {
                    val sector = header.intLe(76 + index * 4)
                    if (sector < 0) break
                    val bytes = readSector(handle, sector, sectorSize, fileLength)
                        ?: corrupt("OLE2 FAT sector lies outside the file")
                    for (word in 0 until sectorSize / 4) {
                        fat[fatWrite++] = bytes.intLe(word * 4)
                    }
                }

                val directory = readDirectory(handle, fat, firstDirectorySector, sectorSize, fileLength)
                val entries = (0 until directory.size step DIRECTORY_ENTRY_BYTES)
                    .mapNotNull { directory.readEntry(it) }
                val root = entries.firstOrNull { it.type == 5 } ?: corrupt("OLE2 directory has no root entry")
                val names = LinkedHashSet<String>()
                val pending = ArrayDeque<Int>()
                val visited = HashSet<Int>()
                pending.add(root.child)
                var visits = 0
                while (pending.isNotEmpty()) {
                    val id = pending.removeFirst()
                    if (id < 0 || id >= entries.size || !visited.add(id)) continue
                    if (++visits > MAX_ENTRY_VISITS) break
                    val entry = entries[id]
                    if (entry.type == 0) continue
                    if (entry.type == 2) names += entry.name
                    pending.add(entry.left)
                    pending.add(entry.right)
                    if (entry.type == 1 || entry.type == 5) pending.add(entry.child)
                }
                return names
            }
        }

        private fun readDirectory(
            handle: java.io.RandomAccessFile,
            fat: IntArray,
            start: Int,
            sectorSize: Int,
            fileLength: Long,
        ): ByteArray {
            val out = ByteArrayOutputStream(sectorSize * 4)
            var sector = start
            var count = 0
            while (sector >= 0 && count < MAX_OLE2_DIRECTORY_SECTORS) {
                val bytes = readSector(handle, sector, sectorSize, fileLength) ?: break
                out.write(bytes)
                if (sector >= fat.size) break
                sector = fat[sector]
                count++
            }
            if (out.size() == 0) corrupt("OLE2 directory chain is empty")
            return out.toByteArray()
        }

        private fun readSector(
            handle: java.io.RandomAccessFile,
            sector: Int,
            sectorSize: Int,
            fileLength: Long,
        ): ByteArray? {
            val offset = (sector + 1).toLong() * sectorSize
            if (offset < 0 || offset + sectorSize > fileLength) return null
            val bytes = ByteArray(sectorSize)
            handle.seek(offset)
            handle.readFully(bytes)
            return bytes
        }

        private data class Entry(
            val name: String,
            val type: Int,
            val left: Int,
            val right: Int,
            val child: Int,
        )

        private fun ByteArray.readEntry(offset: Int): Entry? {
            if (offset + DIRECTORY_ENTRY_BYTES > size) return null
            val nameBytes = u16Le(offset + 64)
            if (nameBytes < 2 || nameBytes > 64 || nameBytes % 2 != 0) return null
            val name = String(this, offset, nameBytes - 2, Charsets.UTF_16LE)
            if (name.isBlank()) return null
            return Entry(
                name = name,
                type = this[offset + 66].toInt() and 0xFF,
                left = intLe(offset + 68),
                right = intLe(offset + 72),
                child = intLe(offset + 76),
            )
        }
    }

    // ---- text ------------------------------------------------------------------------------

    private fun sniffText(file: File, head: ByteArray): Detection {
        val sample = file.inputStream().use { readAtMost(it, TEXT_SAMPLE_BYTES) }
        val truncated = file.length() > sample.size
        val text = decodeText(sample, truncated)
            ?: throw DocumentFormatException(
                DocumentFormatError.UNKNOWN_FORMAT,
                "unrecognised binary content ${head.take(8).joinToString("") { "%02X".format(it) }}"
            )
        if (RTF_PREFIX.containsMatchIn(text.take(64))) return Detection(DocumentFormat.RTF, exact = true)
        return Detection(DocumentFormat.TXT, exact = false)
    }

    /** Returns decoded text, or null when the sample is binary rather than text. */
    private fun decodeText(sample: ByteArray, truncated: Boolean): String? {
        if (sample.isEmpty()) return ""
        val charset: Charset
        val offset: Int
        when {
            sample.size >= 3 && sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() && sample[2] == 0xBF.toByte() -> {
                charset = Charsets.UTF_8; offset = 3
            }

            sample.size >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte() -> {
                charset = Charsets.UTF_16LE; offset = 2
            }

            sample.size >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte() -> {
                charset = Charsets.UTF_16BE; offset = 2
            }

            else -> {
                charset = Charsets.UTF_8; offset = 0
            }
        }
        val body = sample.copyOfRange(offset, sample.size)
        // UTF-8 text never contains NUL; UTF-16 without a BOM is not guessed.
        if (offset == 0 && body.any { it == 0.toByte() }) return null
        val decoded = decodeStrict(charset, body, truncated) ?: return null
        if (decoded.isEmpty()) return decoded
        var controls = 0
        for (char in decoded) {
            when {
                char == '\u0000' -> return null
                char < ' ' && char != '\t' && char != '\n' && char != '\r' && char != '\u000C' -> controls++
                char == '\u007F' -> controls++
            }
        }
        if (controls * 100 / decoded.length > 2) return null
        return decoded
    }

    private fun decodeStrict(charset: Charset, bytes: ByteArray, truncated: Boolean): String? {
        // A sample cut mid-character would otherwise look malformed; drop up to 3 trailing bytes.
        val attempts = if (truncated) listOf(0, 1, 2, 3) else listOf(0)
        for (drop in attempts) {
            if (bytes.size - drop < 0) continue
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                return decoder.decode(ByteBuffer.wrap(bytes, 0, bytes.size - drop)).toString()
            } catch (_: CharacterCodingException) {
                // Try the next shorter length.
            }
        }
        return null
    }

    // ---- reconciliation --------------------------------------------------------------------

    private fun reconcile(declared: DocumentFormat?, detection: Detection): Detection {
        if (declared == null || declared == detection.format) return detection
        val sniffed = detection.format
        // md/txt/csv/tsv share bytes; the declared extension provides the label.
        if (sniffed.isTextContainer && declared.isTextContainer) {
            return detection.copy(
                format = declared,
                exact = false,
                notes = detection.notes + DetectNote.TEXT_BY_EXTENSION
            )
        }
        throw DocumentFormatException(
            DocumentFormatError.MISMATCH,
            "declared .${declared.id} but the bytes are ${sniffed.id}"
        )
    }

    private val CONTENT_TYPE_TAGS = Regex("<Override\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val CONTENT_TYPE = Regex("ContentType\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
    private val RTF_PREFIX = Regex("^\\s*\\{\\\\rtf", RegexOption.IGNORE_CASE)
}

internal data class Detection(
    val format: DocumentFormat,
    val exact: Boolean,
    val notes: Set<DetectNote> = emptySet(),
)

internal enum class DetectNote {
    /** WebP VP8X animation flag: only the first frame can be edited. */
    ANIMATED_WEBP,

    /** The OOXML family came from part names because [Content_Types].xml had no usable override. */
    ZIP_PART_NAMES,

    /** md|txt|csv|tsv chosen from the declared extension; the bytes only prove "text". */
    TEXT_BY_EXTENSION,

    /** Legacy .doc/.xls/.ppt decided by OLE2 stream names. */
    OLE2_STREAM_NAMES,
}

// ---- file-scoped helpers ---------------------------------------------------------------

private fun corrupt(message: String): Nothing =
    throw DocumentFormatException(DocumentFormatError.CORRUPT, message)

private fun readAtMost(stream: InputStream, limit: Int): ByteArray {
    val buffer = ByteArray(limit)
    var total = 0
    while (total < limit) {
        val read = stream.read(buffer, total, limit - total)
        if (read < 0) break
        total += read
    }
    return buffer.copyOf(total)
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    for (index in prefix.indices) if (this[index] != prefix[index]) return false
    return true
}

private fun ByteArray.startsWithAscii(prefix: String): Boolean {
    if (size < prefix.length) return false
    for (index in prefix.indices) if (this[index] != prefix[index].code.toByte()) return false
    return true
}

private fun ByteArray.ascii(offset: Int, length: Int): String {
    if (offset < 0 || offset + length > size) return ""
    return String(this, offset, length, Charsets.US_ASCII)
}

private fun ByteArray.intLe(offset: Int): Int {
    if (offset < 0 || offset + 4 > size) return -1
    return (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)
}

private fun ByteArray.u16Le(offset: Int): Int {
    if (offset < 0 || offset + 2 > size) return -1
    return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}

private fun ByteArray.intBe(offset: Int): Int {
    if (offset < 0 || offset + 4 > size) return -1
    return ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)
}
