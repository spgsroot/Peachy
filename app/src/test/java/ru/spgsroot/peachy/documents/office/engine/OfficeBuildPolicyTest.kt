package ru.spgsroot.peachy.documents.office.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OfficeBuildPolicyTest {

    private fun manifest(
        commit: String = OfficeBuildManifest.PINNED_COMMIT,
        macros: String = "false",
        tileMode: String = "rgba",
    ): String = buildString {
        appendLine("# packaged by tools/office-engine")
        appendLine("commit=$commit")
        appendLine("tag=libreoffice-26.8.3.1")
        appendLine("have_feature_android_lok=$macros")
        appendLine("tile_mode=$tileMode")
        appendLine("abi=x86_64")
    }

    @Test
    fun aValidPinnedManifestHasNoProblems() {
        val parsed = OfficeBuildManifest.parse(manifest())
        assertEquals(OfficeBuildManifest.PINNED_COMMIT, parsed.commit)
        assertEquals("libreoffice-26.8.3.1", parsed.tag)
        assertFalse(parsed.haveFeatureAndroidLok)
        assertEquals("rgba", parsed.tileMode)
        assertEquals("x86_64", parsed.abi)
        assertTrue(parsed.problems().isEmpty())
        assertNull(parsed.unavailableReason())
    }

    @Test
    fun anEnabledMacroFeatureRefusesTheEngine() {
        val parsed = OfficeBuildManifest.parse(manifest(macros = "true"))
        assertEquals(listOf(OfficeManifestProblem.MACROS_NOT_LOCKED), parsed.problems())
        assertEquals(OfficeUnavailableReason.MACRO_LOCK, parsed.unavailableReason())
    }

    @Test
    fun aDifferentCommitIsNotThePinnedEngine() {
        val parsed = OfficeBuildManifest.parse(manifest(commit = "0".repeat(40)))
        assertEquals(OfficeUnavailableReason.BUILD_MANIFEST, parsed.unavailableReason())
    }

    @Test
    fun anUnverifiedPixelPathRefusesRendering() {
        for (mode in listOf("bgra", "argb", "rgbx", "rgba32", "")) {
            val parsed = OfficeBuildManifest.parse(manifest(tileMode = mode))
            assertEquals(
                mode,
                listOf(OfficeManifestProblem.TILE_MODE_UNSUPPORTED),
                parsed.problems(),
            )
            assertEquals(mode, OfficeUnavailableReason.TILE_MODE, parsed.unavailableReason())
        }
    }

    @Test
    fun theDeclaredTileModeMustBeThePinnedRgbaPath() {
        assertEquals(OfficeTilePixels.SUPPORTED_TILE_MODE, OfficeBuildManifest.SUPPORTED_TILE_MODE)
        assertEquals("RGBA", OfficeBuildManifest.parse(manifest(tileMode = "RGBA")).tileMode.uppercase())
        assertTrue(OfficeBuildManifest.parse(manifest(tileMode = "rgba")).problems().isEmpty())
    }

    @Test
    fun aMissingRequiredKeyFailsClosed() {
        val withoutCommit = manifest().lineSequence().filterNot { it.startsWith("commit=") }.joinToString("\n")
        try {
            OfficeBuildManifest.parse(withoutCommit)
            fail("parsed a manifest without a commit")
        } catch (expected: IllegalStateException) {
            // expected
        }
        try {
            OfficeBuildManifest.parse(manifest() + "\ncommit=duplicate")
            fail("accepted a duplicate key")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            OfficeBuildManifest.parse("commit-without-equals")
            fail("accepted a malformed line")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun theEnvironmentGatesAreExact() {
        assertEquals(
            listOf("LOK_HOST_ALLOWLIST=^$", "LOK_ALLOWED_EXTREF_PATHS="),
            OfficeEnvironmentPolicy.ASSIGNMENTS,
        )
        for (assignment in OfficeEnvironmentPolicy.ASSIGNMENTS) {
            val key = assignment.substringBefore('=')
            assertTrue(key, key !in OfficeEnvironmentPolicy.FORBIDDEN_KEYS)
        }
        assertEquals("^$", OfficeEnvironmentPolicy.HOST_ALLOWLIST_DENY_ALL)
    }

    @Test
    fun managedPathsBecomeFileUrls() {
        val uri = OfficeLokPaths.lokUri(
            "/data/user/0/ru.spgsroot.peachy/files/documents/0b7c/session/working.docx",
        ).getOrThrow()
        assertEquals(
            "file:///data/user/0/ru.spgsroot.peachy/files/documents/0b7c/session/working.docx",
            uri,
        )
    }

    @Test
    fun ambientAndUnmanagedPathsAreRefused() {
        for (path in listOf(
            "working.docx",
            "content://com.example/document/1",
            "https://example.com/doc.docx",
            "/data/user/0/app/files/working with space.docx",
            "/data/user/0/app/files/working\u0000.docx",
            "",
        )) {
            val failure = OfficeLokPaths.lokUri(path).exceptionOrNull() as OfficeFailure.Refused
            assertEquals(path, OfficeRefusal.UNMANAGED_SOURCE, failure.reason)
        }
    }

    @Test
    fun encodingIsPercentBasedAndKeepsSlashes() {
        assertEquals("/data/a%20b.docx", OfficeLokPaths.encode("/data/a b.docx"))
        assertEquals("/%D0%96.docx", OfficeLokPaths.encode("/Ж.docx"))
        assertEquals("/a~b-c_d.e", OfficeLokPaths.encode("/a~b-c_d.e"))
        assertEquals("/%23%3F.docx", OfficeLokPaths.encode("/#?.docx"))
    }

    @Test
    fun managedPathCheckMatchesTheLokUrlRule() {
        assertTrue(OfficeLokPaths.isManaged("/data/user/0/app/files/documents/x/working.docx"))
        assertTrue(OfficeLokPaths.encode("/data/user/0/app/files/documents/x/working.docx")
            .startsWith("/data/user/0/app/files/documents/x/working.docx"))
    }
}
