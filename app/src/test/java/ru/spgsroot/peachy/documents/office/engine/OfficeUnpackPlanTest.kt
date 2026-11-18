package ru.spgsroot.peachy.documents.office.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeUnpackPlanTest {

    private val commit = OfficeBuildManifest.PINNED_COMMIT

    @Test
    fun vettedUnpackPathsAreAccepted() {
        assertEquals("program/offapi.rdb", safeRelativePath("program/offapi.rdb"))
        assertEquals(
            "user/fonts/LiberationSans-Regular.ttf",
            safeRelativePath("user/fonts/LiberationSans-Regular.ttf"),
        )
        assertEquals("etc/fonts/fonts.conf", safeRelativePath("etc/fonts/fonts.conf"))
    }

    @Test
    fun traversalAndMalformedPathsAreRefused() {
        for (path in listOf(
            "",
            "/program/offapi.rdb",
            "program/offapi.rdb/",
            "program//offapi.rdb",
            "../program/offapi.rdb",
            "program/../../etc/passwd",
            "program\\offapi.rdb",
            ".hidden",
            "program/.hidden",
            "program/offapi.rdb\u0000",
            "program/off\u0001api.rdb",
            "program/offapi.rdb ",
            "a/b/c/d/e/f/g/h/i",
        )) {
            assertNull(path, safeRelativePath(path))
        }
        assertNull(safeRelativePath("a".repeat(OfficeUnpackLimits.MAX_RELATIVE_PATH_LENGTH + 1)))
    }

    @Test
    fun segmentNamesRejectSeparatorsDotsAndControls() {
        assertTrue(isSafeAssetName("offapi.rdb"))
        assertTrue(isSafeAssetName("LiberationSans-Regular.ttf"))
        for (name in listOf("", ".", "..", ".marker", "a/b", "a\\b", "a\u0000b", "\t")) {
            assertFalse(name, isSafeAssetName(name))
        }
        assertFalse(isSafeAssetName("x".repeat(OfficeUnpackLimits.MAX_NAME_LENGTH + 1)))
    }

    @Test
    fun fingerprintIsOrderIndependentAndTracksTheApk() {
        val paths = listOf("program/udkapi.rdb", "etc/fonts/fonts.conf", "program/offapi.rdb")
        val first = unpackFingerprint(paths, 1024L, 7L)
        assertEquals(64, first.length)
        assertTrue(first.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(first, unpackFingerprint(paths.reversed(), 1024L, 7L))
        assertFalse(first == unpackFingerprint(paths, 1025L, 7L))
        assertFalse(first == unpackFingerprint(paths, 1024L, 8L))
        assertFalse(first == unpackFingerprint(paths + "user/fonts/x.ttf", 1024L, 7L))
    }

    @Test
    fun directoryNamesAreStrictlyAppOwned() {
        val fingerprint = unpackFingerprint(listOf("program/offapi.rdb"), 1L, 1L)
        val name = unpackDirectoryName(commit, fingerprint)
        assertEquals("unpack-${commit.take(12)}-${fingerprint.take(16)}", name)
        assertTrue(isOwnedUnpackName(name))
        assertFalse(isOwnedUnpackStagingName(name))
        assertTrue(isOwnedUnpackStagingName("$name.tmp"))
        for (other in listOf(
            "office-engine",
            "unpack-${commit.take(12)}",
            "$name.tmp.old",
            "unpack-000000000000-0000000000000000-extra",
            "unpack-ABCDEF012345-abcdef0123456789",
            "unpack",
            "../unpack-${commit.take(12)}-${fingerprint.take(16)}",
        )) {
            assertFalse(other, isOwnedUnpackName(other))
        }
    }

    @Test
    fun markerRoundTripsAndMismatchesFailClosed() {
        val expected = OfficeUnpackMarker(
            format = OfficeUnpackLayout.MARKER_FORMAT,
            commit = commit,
            fingerprint = "a".repeat(64),
            files = 12,
        )
        val parsed = OfficeUnpackMarker.parse(expected.render())
        assertEquals(expected, parsed)
        assertTrue(markerMatches(parsed, expected))
        assertFalse(markerMatches(null, expected))
        assertFalse(markerMatches(parsed?.copy(files = 13), expected))
        assertFalse(markerMatches(parsed?.copy(commit = "0".repeat(40)), expected))
        assertFalse(markerMatches(parsed?.copy(fingerprint = "b".repeat(64)), expected))
        assertFalse(markerMatches(parsed?.copy(format = 2), expected))
    }

    @Test
    fun malformedMarkersAreRefused() {
        assertNull(OfficeUnpackMarker.parse(null))
        assertNull(OfficeUnpackMarker.parse(""))
        assertNull(OfficeUnpackMarker.parse("not-a-marker"))
        assertNull(OfficeUnpackMarker.parse("format=1\nfiles=3"))
        assertNull(
            OfficeUnpackMarker.parse(
                "format=1\ncommit=${commit}\nfingerprint=${"a".repeat(64)}\nfiles=3\nfiles=4",
            ),
        )
        assertNull(
            OfficeUnpackMarker.parse(
                "format=2\ncommit=${commit}\nfingerprint=${"a".repeat(64)}\nfiles=3",
            ),
        )
        assertNull(
            OfficeUnpackMarker.parse(
                "format=1\ncommit=${commit.uppercase()}\nfingerprint=${"a".repeat(64)}\nfiles=3",
            ),
        )
        assertNull(
            OfficeUnpackMarker.parse(
                "format=1\ncommit=${commit}\nfingerprint=${"a".repeat(64)}\nfiles=-1",
            ),
        )
    }

    @Test
    fun theCopyBudgetStopsAtEveryBoundary() {
        assertTrue(OfficeUnpackBudget.allowsFile(0, 0, 0))
        assertTrue(OfficeUnpackBudget.allowsFile(0, 0, OfficeUnpackLimits.MAX_FILE_BYTES))
        assertFalse(OfficeUnpackBudget.allowsFile(0, 0, OfficeUnpackLimits.MAX_FILE_BYTES + 1))
        assertFalse(OfficeUnpackBudget.allowsFile(0, 0, -1))
        assertTrue(
            OfficeUnpackBudget.allowsFile(
                0,
                OfficeUnpackLimits.MAX_TOTAL_BYTES - OfficeUnpackLimits.MAX_FILE_BYTES,
                OfficeUnpackLimits.MAX_FILE_BYTES,
            ),
        )
        assertFalse(
            OfficeUnpackBudget.allowsFile(
                0,
                OfficeUnpackLimits.MAX_TOTAL_BYTES - OfficeUnpackLimits.MAX_FILE_BYTES + 1,
                OfficeUnpackLimits.MAX_FILE_BYTES,
            ),
        )
        assertFalse(OfficeUnpackBudget.allowsFile(OfficeUnpackLimits.MAX_FILES, 0, 0))
        assertTrue(OfficeUnpackBudget.allowsFile(OfficeUnpackLimits.MAX_FILES - 1, 0, 0))
    }
}
