package ru.spgsroot.peachy.documents.pdf

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream
import com.artifex.mupdf.fitz.FitzInputStream
import com.artifex.mupdf.fitz.PKCS7DistinguishedName
import com.artifex.mupdf.fitz.PKCS7Signer
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Provider
import java.security.cert.X509Certificate

/** Where the signing certificate sits relative to the device trust store. */
enum class PdfCertTrust { TRUSTED, NOT_TRUSTED, SELF_SIGNED }

data class PdfSigningIdentity(
    val displayName: String,
    val trust: PdfCertTrust,
    val signer: PKCS7Signer,
    val cmsSigner: PdfCmsSigner,
)

/**
 * PKCS#12 identity for PDF signing.
 *
 * The password is used only to load the key store and is wiped afterwards; [PdfCmsSigner] keeps the
 * private-key handle for the signature operation and nothing else. CMS is BouncyCastle (SHA-256,
 * detached SignedData with the certificate chain), which is the PKCS#7 blob MuPDF expects from
 * [PKCS7Signer.sign]. PKCS#12 parsing uses the platform key store first and the bundled provider
 * as a fallback for key stores the platform cannot read.
 */
object PdfSignerFactory {

    private const val TAG = "PdfSigner"
    private const val MAX_PKCS12_BYTES = 8 * 1024 * 1024

    /** SAF providers need not know the input length. Probe one byte before growing the buffer. */
    internal fun readPkcs12(input: InputStream): ByteArray {
        var bytes = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0
        while (true) {
            if (size == bytes.size) {
                val next = input.read()
                if (next < 0) return bytes
                if (size == MAX_PKCS12_BYTES) throw PdfException(PdfError(PdfErrorCode.TOO_LARGE))
                bytes = bytes.copyOf(minOf(size * 2, MAX_PKCS12_BYTES))
                bytes[size++] = next.toByte()
            }
            val n = input.read(bytes, size, bytes.size - size)
            if (n < 0) return bytes.copyOf(size)
            if (n == 0) throw java.io.IOException("Certificate stream returned no data")
            size += n
        }
    }

    internal val provider: Provider by lazy { BouncyCastleProvider() }

    data class LocalIdentity(
        val key: PrivateKey,
        val chain: List<X509Certificate>,
        val algorithm: String,
        val displayName: String,
        val trust: PdfCertTrust,
    )

    /**
     * Reads the private key, chain and trust state out of a PKCS#12 blob. Kept separate from
     * [fromPkcs12] so that password handling and chain selection can be exercised on the JVM.
     */
    @Throws(PdfException::class)
    fun loadIdentity(bytes: ByteArray, password: CharArray): LocalIdentity {
        if (bytes.isEmpty()) throw PdfException(PdfError(PdfErrorCode.SIGNATURE_MISSING_CERT))
        val keyStore = loadKeyStore(bytes, password)
        val alias = keyStore.aliases().toList().firstOrNull { alias ->
            try {
                keyStore.isKeyEntry(alias) && keyStore.getCertificateChain(alias)?.isNotEmpty() == true
            } catch (e: Exception) {
                false
            }
        } ?: throw PdfException(PdfError(PdfErrorCode.SIGNATURE_MISSING_CERT))

        val key = try {
            keyStore.getKey(alias, password) as? PrivateKey
        } catch (e: Exception) {
            throw PdfException(PdfError(PdfErrorCode.SIGNATURE_WRONG_PASSWORD))
        } ?: throw PdfException(PdfError(PdfErrorCode.SIGNATURE_MISSING_CERT))

        val chain = keyStore.getCertificateChain(alias)
            ?.filterIsInstance<X509Certificate>()
            ?.takeIf { it.isNotEmpty() }
            ?: throw PdfException(PdfError(PdfErrorCode.SIGNATURE_MISSING_CERT))
        val leaf = chain.first()

        val algorithm = when (leaf.publicKey.algorithm.uppercase()) {
            "EC", "ECDSA" -> "SHA256withECDSA"
            "RSA" -> "SHA256withRSA"
            else -> throw PdfException(PdfError(PdfErrorCode.SIGNATURE_UNSUPPORTED, leaf.publicKey.algorithm))
        }
        val trust = when {
            isSelfSigned(leaf) -> PdfCertTrust.SELF_SIGNED
            chainTrusted(chain) -> PdfCertTrust.TRUSTED
            else -> PdfCertTrust.NOT_TRUSTED
        }
        return LocalIdentity(key, chain, algorithm, commonName(leaf), trust)
    }

    @Throws(PdfException::class)
    fun fromPkcs12(bytes: ByteArray, password: CharArray): PdfSigningIdentity {
        val identity = try {
            loadIdentity(bytes, password)
        } finally {
            password.fill('\u0000')
        }
        val cms = PdfCmsSigner(identity.key, identity.chain, identity.algorithm)
        val signer = object : PKCS7Signer() {
            override fun name(): PKCS7DistinguishedName = cms.distinguishedName()

            override fun sign(stm: FitzInputStream): ByteArray = try {
                cms.signBytes(cms.readFully(stm)).also {
                    Log.d(TAG, "CMS signature produced (" + it.size + " bytes)")
                }
            } catch (e: Exception) {
                // MuPDF cannot report a JNI-side failure, so the stack is logged here; the file is
                // then written with an empty signature and the UI reports SIGN_FAILED.
                Log.e(TAG, "CMS signature failed", e)
                throw e
            }

            override fun maxDigest(): Int = 16_384
        }
        return PdfSigningIdentity(identity.displayName, identity.trust, signer, cms)
    }

    /** PKCS#12 that the platform cannot read is retried with the bundled provider. */
    @Throws(PdfException::class)
    private fun loadKeyStore(bytes: ByteArray, password: CharArray): KeyStore {
        try {
            val store = KeyStore.getInstance("PKCS12")
            store.load(ByteArrayInputStream(bytes), password)
            return store
        } catch (e: Exception) {
            // fall through to the bundled provider
        }
        return try {
            val store = KeyStore.getInstance("PKCS12", provider)
            store.load(ByteArrayInputStream(bytes), password)
            store
        } catch (e: Exception) {
            throw PdfException(PdfError(PdfErrorCode.SIGNATURE_WRONG_PASSWORD))
        }
    }

    private fun commonName(certificate: X509Certificate): String =
        relativeDistinguishedName(certificate.subjectX500Principal.name, "CN")
            ?: certificate.subjectX500Principal.name

    /**
     * Reads one attribute out of an X.500 distinguished name string. `javax.naming` is not part of
     * the Android platform API, so the short, unambiguous CN/OU/O/C extraction is done here.
     */
    internal fun relativeDistinguishedName(dn: String, type: String): String? {
        val pattern = Regex("(?:^|,)\\s*" + Regex.escape(type) + "=((?:[^,\\\\]|\\\\.)+)", RegexOption.IGNORE_CASE)
        return pattern.find(dn)?.groupValues?.getOrNull(1)
            ?.replace("\\,", ",")
            ?.replace("\\+", "+")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun isSelfSigned(certificate: X509Certificate): Boolean = try {
        certificate.subjectX500Principal == certificate.issuerX500Principal &&
            run {
                certificate.verify(certificate.publicKey)
                true
            }
    } catch (e: Exception) {
        false
    }

    /** True when the chain validates against the platform trust anchors. */
    private fun chainTrusted(chain: List<X509Certificate>): Boolean = try {
        val factory = javax.net.ssl.TrustManagerFactory.getInstance("PKIX")
        factory.init(null as KeyStore?)
        val manager = factory.trustManagers
            .filterIsInstance<javax.net.ssl.X509TrustManager>()
            .firstOrNull()
        if (manager == null) {
            false
        } else {
            try {
                manager.checkClientTrusted(chain.toTypedArray(), chain.first().publicKey.algorithm)
                true
            } catch (e: Exception) {
                false
            }
        }
    } catch (e: Exception) {
        false
    }
}

/** Builds the detached PKCS#7/CMS blob that MuPDF embeds in the signature dictionary. */
class PdfCmsSigner(
    private val privateKey: PrivateKey,
    private val chain: List<X509Certificate>,
    private val algorithm: String,
) {
    @Throws(PdfException::class)
    /**
     * MuPDF hands the byte ranges over as a native [FitzInputStream] whose `read` returns 0 at the
     * end of the stream instead of -1. `InputStream.readBytes`/`copyTo` loop while the count is
     * non-negative, so they spin forever there and the save never completes.
     */
    internal fun readFully(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer, 0, buffer.size)
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun signBytes(byteRange: ByteArray): ByteArray = try {
        val digest = JcaDigestCalculatorProviderBuilder()
            .setProvider(PdfSignerFactory.provider)
            .build()
        val signerInfo = JcaSignerInfoGeneratorBuilder(digest)
            .build(
                JcaContentSignerBuilder(algorithm)
                    .setProvider(PdfSignerFactory.provider)
                    .build(privateKey),
                chain.first(),
            )
        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(signerInfo)
        generator.addCertificates(JcaCertStore(chain))
        val signed = generator.generate(CMSProcessableByteArray(byteRange), false)
        signed.encoded
    } catch (e: Exception) {
        throw PdfException(PdfError(PdfErrorCode.SIGN_FAILED))
    }

    fun distinguishedName(): PKCS7DistinguishedName = PKCS7DistinguishedName().apply {
        val principal = chain.first().subjectX500Principal
        val dn = principal.name
        cn = PdfSignerFactory.relativeDistinguishedName(dn, "CN") ?: dn
        o = PdfSignerFactory.relativeDistinguishedName(dn, "O")
        ou = PdfSignerFactory.relativeDistinguishedName(dn, "OU")
        c = PdfSignerFactory.relativeDistinguishedName(dn, "C")
        email = PdfSignerFactory.relativeDistinguishedName(dn, "E")
            ?: PdfSignerFactory.relativeDistinguishedName(dn, "EMAILADDRESS")
    }
}
