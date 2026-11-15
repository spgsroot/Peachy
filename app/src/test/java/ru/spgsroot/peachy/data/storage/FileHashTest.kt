package ru.spgsroot.peachy.data.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FileHashTest {
    private fun temp(bytes: ByteArray, name: String): File =
        File.createTempFile(name, ".mp4").apply { writeBytes(bytes); deleteOnExit() }

    @Test fun matchesTheKnownSha256OfAbc() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            FileHash.sha256(temp("abc".toByteArray(), "abc"))
        )
    }

    @Test fun identicalContentHashesEqualUnderDifferentNamesAndLargeFilesAreStreamed() {
        val content = ByteArray(300_000) { (it % 251).toByte() }
        assertEquals(FileHash.sha256(temp(content, "first")), FileHash.sha256(temp(content, "second")))
        assertNotEquals(FileHash.sha256(temp(content, "first")), FileHash.sha256(temp(content.copyOf().also { it[299_999] = 9 }, "third")))
    }
}
