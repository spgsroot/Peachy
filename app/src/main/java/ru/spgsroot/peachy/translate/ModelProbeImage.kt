package ru.spgsroot.peachy.translate

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** A tiny generated RGB pattern; never receives page bytes or accesses gallery storage. */
internal object ModelProbeImage {
    val dataUri: String by lazy {
        val pixels = ByteArrayOutputStream()
        DeflaterOutputStream(pixels).use { compressed ->
            for (y in 0 until 32) {
                compressed.write(0) // PNG scanline: no filter
                for (x in 0 until 32) {
                    compressed.write((x * 37 + y * 13) and 255)
                    compressed.write((x * 11 + y * 53) and 255)
                    compressed.write((x * 71 + y * 7) and 255)
                }
            }
        }
        val png = ByteArrayOutputStream()
        DataOutputStream(png).use { out ->
            out.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            fun chunk(type: String, bytes: ByteArray) {
                val name = type.toByteArray(Charsets.US_ASCII)
                out.writeInt(bytes.size)
                out.write(name)
                out.write(bytes)
                val crc = CRC32().apply { update(name); update(bytes) }
                out.writeInt(crc.value.toInt())
            }
            val header = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { it.writeInt(32); it.writeInt(32); it.write(byteArrayOf(8, 2, 0, 0, 0)) }
            }
            chunk("IHDR", header.toByteArray())
            chunk("IDAT", pixels.toByteArray())
            chunk("IEND", byteArrayOf())
        }
        "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray())
    }
}
