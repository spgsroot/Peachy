package ru.spgsroot.peachy.documents.pdf

import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignerDigestMismatchException
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.bouncycastle.asn1.x500.X500Name
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date

class PdfUndoStackTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(name: String, content: String): File =
        folder.newFile(name).apply { writeText(content) }

    @Test
    fun pushAndPopRestoresExactBytes() {
        val stack = PdfUndoStack(File(folder.root, "undo"))
        val working = file("working.pdf", "version-1")
        stack.push(working)
        working.writeText("version-2")
        stack.push(working)
        working.writeText("version-3")

        assertEquals(2, stack.depth())
        assertTrue(stack.popInto(working))
        assertEquals("version-2", working.readText())
        assertTrue(stack.popInto(working))
        assertEquals("version-1", working.readText())
        assertFalse(stack.popInto(working))
        assertEquals(0, stack.depth())
    }

    @Test
    fun oldestSnapshotsAreEvictedBeyondTheCountCap() {
        val stack = PdfUndoStack(File(folder.root, "undo"), maxEntries = 2)
        val working = file("working.pdf", "0")
        for (version in 1..4) {
            working.writeText("version-$version")
            stack.push(working)
        }
        assertEquals(2, stack.depth())
        val names = stack.snapshots().map { it.file.name }
        assertEquals(listOf("000003.pdf", "000004.pdf"), names)
    }

    @Test
    fun clearRemovesEverything() {
        val stack = PdfUndoStack(File(folder.root, "undo"))
        stack.push(file("a.pdf", "a"))
        stack.clear()
        assertEquals(0, stack.depth())
    }

    @Test
    fun copyAtomicReplacesExistingTargetAndLeavesNoTemp() {
        val source = file("source.bin", "payload")
        val target = file("target.bin", "old")
        copyAtomic(source, target)
        assertEquals("payload", target.readText())
        val siblings = folder.root.listFiles()!!.map { it.name }
        assertTrue(siblings.none { it.endsWith(".tmp") })
    }

    @Test
    fun hasPdfSignatureAcceptsJunkBeforeHeaderAndRejectsOtherFiles() {
        val junk = file("junk.bin", "garbage-prefix\u0000\u0001%PDF-1.7\nrest")
        assertTrue(hasPdfSignature(junk))
        val png = file("image.png", "\u0089PNG\r\n\u001a\n")
        assertFalse(hasPdfSignature(png))
        val empty = file("empty.bin", "")
        assertFalse(hasPdfSignature(empty))
    }
}

class PdfSigningTest {

    private val provider = BouncyCastleProvider()

    private data class Identity(val key: PrivateKey, val certificate: X509Certificate)

    private fun identity(): Identity {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val name = X500Name("CN=Peachy\\, Test,O=Peachy,C=RU")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 86_400_000L),
            Date(now + 30L * 86_400_000L),
            name,
            pair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.private)
        val certificate = JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
        return Identity(pair.private, certificate)
    }

    private fun pkcs12(identity: Identity, password: String): ByteArray {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setKeyEntry(
            "peachy",
            identity.key,
            password.toCharArray(),
            arrayOf(identity.certificate),
        )
        return java.io.ByteArrayOutputStream().use { out ->
            store.store(out, password.toCharArray())
            out.toByteArray()
        }
    }

    @Test
    fun pkcs12AcceptsExactLimitAndStopsAtFirstExcessByte() {
        val limit = 8 * 1024 * 1024
        val contents = ByteArray(limit) { (it % 251).toByte() }
        val shortReads = object : java.io.ByteArrayInputStream(contents) {
            override fun available() = 0
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 997))
        }
        org.junit.Assert.assertArrayEquals(contents, PdfSignerFactory.readPkcs12(shortReads))

        val oversized = java.io.ByteArrayInputStream(ByteArray(limit + 100))
        val error = assertThrows(PdfException::class.java) { PdfSignerFactory.readPkcs12(oversized) }
        assertEquals(PdfErrorCode.TOO_LARGE, error.error.code)
        assertEquals(99, oversized.available())
    }

    @Test
    fun pkcs12LoadsKeyChainAndSelfSignedTrust() {
        val identity = identity()
        val loaded = PdfSignerFactory.loadIdentity(pkcs12(identity, "secret"), "secret".toCharArray())
        assertEquals("SHA256withRSA", loaded.algorithm)
        assertEquals(1, loaded.chain.size)
        assertEquals(PdfCertTrust.SELF_SIGNED, loaded.trust)
        // Escaped comma in the CN must survive the distinguished-name parsing.
        assertEquals("Peachy, Test", loaded.displayName)
    }

    @Test
    fun wrongPasswordIsATypedError() {
        val bytes = pkcs12(identity(), "secret")
        try {
            PdfSignerFactory.loadIdentity(bytes, "wrong".toCharArray())
            fail("wrong password must not load")
        } catch (e: PdfException) {
            assertEquals(PdfErrorCode.SIGNATURE_WRONG_PASSWORD, e.error.code)
        }
    }

    @Test
    fun emptyPkcs12IsRejected() {
        try {
            PdfSignerFactory.loadIdentity(ByteArray(0), CharArray(0))
            fail("empty p12 must not load")
        } catch (e: PdfException) {
            assertEquals(PdfErrorCode.SIGNATURE_MISSING_CERT, e.error.code)
        }
    }

    @Test
    fun cmsSignatureIsDetachedAndVerifiable() {
        val identity = identity()
        val data = "signed byte range".toByteArray()
        val cmsBytes = PdfCmsSigner(identity.key, listOf(identity.certificate), "SHA256withRSA")
            .signBytes(data)

        val cms = CMSSignedData(CMSProcessableByteArray(data), cmsBytes)
        val signerInfo = cms.signerInfos.signers.firstOrNull()
        assertNotNull(signerInfo)
        val verifier = JcaSimpleSignerInfoVerifierBuilder()
            .setProvider(provider)
            .build(identity.certificate)
        assertTrue(signerInfo!!.verify(verifier))
        // Detached: the encoded SignedData must not carry the signed byte range.
        assertFalse(cmsBytes.toString(Charsets.ISO_8859_1).contains("signed byte range"))
    }

    @Test
    fun tamperedContentFailsVerification() {
        val identity = identity()
        val cmsBytes = PdfCmsSigner(identity.key, listOf(identity.certificate), "SHA256withRSA")
            .signBytes("original".toByteArray())
        val cms = CMSSignedData(CMSProcessableByteArray("modified".toByteArray()), cmsBytes)
        val signerInfo = cms.signerInfos.signers.first()
        val verifier = JcaSimpleSignerInfoVerifierBuilder()
            .setProvider(provider)
            .build(identity.certificate)
        assertThrows(CMSSignerDigestMismatchException::class.java) {
            signerInfo.verify(verifier)
        }
    }

    @Test
    fun distinguishedNameIsReadableFromTheCertificate() {
        val identity = identity()
        val cms = PdfCmsSigner(identity.key, listOf(identity.certificate), "SHA256withRSA")
        // PKCS7DistinguishedName is a plain data holder; filling it must not touch the signing key.
        val dn = cms.distinguishedName()
        assertEquals("Peachy, Test", dn.cn)
        assertEquals("Peachy", dn.o)
        assertEquals("RU", dn.c)
    }

    /**
     * MuPDF's FitzInputStream returns 0 at the end of the byte ranges instead of -1. A plain
     * `readBytes()` spins there forever, which hung every signed save; `readFully` must stop.
     */
    @Test
    fun readFullyStopsOnZeroBeforeEof() {
        val data = ByteArray(40_000) { (it % 251).toByte() }
        var calls = 0
        val stream = object : java.io.InputStream() {
            private var offset = 0
            override fun read(): Int = throw UnsupportedOperationException("bulk reads only")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                calls++
                if (calls > 100) throw IllegalStateException("read loop did not stop")
                // Feeds 16 KiB per call, then reports end-of-content as 0 like FitzInputStream.
                if (offset >= data.size) return 0
                val n = minOf(len, data.size - offset)
                System.arraycopy(data, offset, b, off, n)
                offset += n
                return n
            }
        }

        val identity = identity()
        val read = PdfCmsSigner(identity.key, listOf(identity.certificate), "SHA256withRSA")
            .readFully(stream)

        assertEquals(data.size, read.size)
        assertTrue(data.contentEquals(read))
        assertEquals(4, calls)
    }

    @Test
    fun emptyByteRangeProducesEmptyContent() {
        val identity = identity()
        val read = PdfCmsSigner(identity.key, listOf(identity.certificate), "SHA256withRSA")
            .readFully(object : java.io.InputStream() {
                override fun read(): Int = throw UnsupportedOperationException("bulk reads only")
                override fun read(b: ByteArray, off: Int, len: Int): Int = 0
            })
        assertEquals(0, read.size)
    }
}
