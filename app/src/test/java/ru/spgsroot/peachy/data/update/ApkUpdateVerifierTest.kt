package ru.spgsroot.peachy.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkUpdateVerifierTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val release = AppRelease("v3.4.0", "3.4.0", "", "https://github.com/spgsroot/Peachy/releases/tag/v3.4.0", "https://github.com/spgsroot/Peachy/releases/download/v3.4.0/app-release-unsigned-signed.apk", 3, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    @Test fun changedDownloadedBytesFailIntegrityVerification() {
        val file = temporaryFolder.newFile().apply { writeText("abc") }
        verifyApkSha256(file, release.sha256)
        file.writeText("abd")
        assertEquals(UpdateError.INTEGRITY, assertThrows(UpdateException::class.java) { verifyApkSha256(file, release.sha256) }.reason)
    }

    @Test fun differentPackageOrAdvertisedVersionCannotBeInstalled() {
        for (identity in listOf(
            ApkIdentity("attacker.app", "3.4.0", 9, 26),
            ApkIdentity("ru.spgsroot.peachy", "3.3.0", 9, 26)
        )) {
            assertEquals(UpdateError.INVALID_APK, assertThrows(UpdateException::class.java) {
                validateApkIdentity(identity, release, "ru.spgsroot.peachy", 8, 36)
            }.reason)
        }
    }

    @Test fun matchingVersionNameDoesNotPermitDowngradingVersionCode() {
        assertEquals(UpdateError.INCOMPATIBLE, assertThrows(UpdateException::class.java) {
            validateApkIdentity(ApkIdentity("ru.spgsroot.peachy", "3.4.0", 8, 26), release, "ru.spgsroot.peachy", 8, 36)
        }.reason)
    }

    @Test fun lowerVersionCodeIsAlsoADowngrade() {
        assertEquals(UpdateError.INCOMPATIBLE, assertThrows(UpdateException::class.java) {
            validateApkIdentity(ApkIdentity("ru.spgsroot.peachy", "3.4.0", 7, 26), release, "ru.spgsroot.peachy", 8, 36)
        }.reason)
    }

    @Test fun newerCodeAndExactSdkBoundaryAreAccepted() {
        validateApkIdentity(
            ApkIdentity("ru.spgsroot.peachy", "3.4.0", 9, 36),
            release,
            "ru.spgsroot.peachy",
            8,
            36
        )
    }

    @Test fun malformedDigestIsNotAccepted() {
        val file = temporaryFolder.newFile().apply { writeText("abc") }
        for (digest in listOf("", "ba7816bf", "z".repeat(64))) {
            assertEquals(UpdateError.INTEGRITY, assertThrows(UpdateException::class.java) {
                verifyApkSha256(file, digest)
            }.reason)
        }
    }

    @Test fun digestHexIsCaseInsensitive() {
        val file = temporaryFolder.newFile().apply { writeText("abc") }
        verifyApkSha256(file, release.sha256.uppercase())
    }

    @Test fun missingFileIsAnIntegrityFailure() {
        val missing = java.io.File(temporaryFolder.root, "missing.apk")
        assertEquals(UpdateError.INTEGRITY, assertThrows(UpdateException::class.java) {
            verifyApkSha256(missing, release.sha256)
        }.reason)
    }

    @Test fun nonPositiveAdvertisedSizeNeverProducesAnApk() = runTest {
        for (size in listOf(0L, -1L)) {
            val target = temporaryFolder.newFile()
            try {
                copyApkWithIntegrityCheck(ByteArrayInputStream("abc".toByteArray()), target, release.copy(apkSize = size))
                fail("A non-positive size must be rejected")
            } catch (exception: UpdateException) {
                assertEquals(UpdateError.INTEGRITY, exception.reason)
            }
            assertFalse(target.exists())
        }
    }

    @Test fun copyRejectsBothShortAndOversizedApksAndRemovesPartialFile() = runTest {
        for (bytes in listOf("ab", "abcd")) {
            val target = temporaryFolder.newFile()
            try {
                copyApkWithIntegrityCheck(ByteArrayInputStream(bytes.toByteArray()), target, release)
                fail("A size mismatch must not produce an APK")
            } catch (exception: UpdateException) {
                assertEquals(UpdateError.INTEGRITY, exception.reason)
            }
            assertFalse(target.exists())
        }
    }

    @Test fun copyRejectsCorruptionEvenWhenSizeMatches() = runTest {
        val target = temporaryFolder.newFile()
        try {
            copyApkWithIntegrityCheck(ByteArrayInputStream("abd".toByteArray()), target, release)
            fail("A digest mismatch must not produce an APK")
        } catch (exception: UpdateException) {
            assertEquals(UpdateError.INTEGRITY, exception.reason)
        }
        assertFalse(target.exists())
    }

    @Test fun copyKeepsOnlyTheVerifiedBytes() = runTest {
        val target = temporaryFolder.newFile()
        copyApkWithIntegrityCheck(ByteArrayInputStream("abc".toByteArray()), target, release)
        assertEquals("abc", target.readText())
    }

    @Test fun cancelledCopyPropagatesAndRemovesPartialFile() = runTest {
        val target = temporaryFolder.newFile()
        val input = object : ByteArrayInputStream("abc".toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                throw CancellationException("Cancelled while copying")
            }
        }
        try {
            copyApkWithIntegrityCheck(input, target, release)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            // Cancellation is not translated into an update failure.
        }
        assertFalse(target.exists())
    }

    @Test fun cancelledCoroutineCannotPublishAnApk() = runTest {
        val target = temporaryFolder.newFile()
        val job = launch {
            currentCoroutineContext().cancel()
            copyApkWithIntegrityCheck(ByteArrayInputStream("abc".toByteArray()), target, release)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(target.exists())
    }

    @Test fun unsupportedSdkCannotReachInstaller() {
        assertEquals(UpdateError.INCOMPATIBLE, assertThrows(UpdateException::class.java) {
            validateApkIdentity(ApkIdentity("ru.spgsroot.peachy", "3.4.0", 9, 37), release, "ru.spgsroot.peachy", 8, 36)
        }.reason)
    }
}
