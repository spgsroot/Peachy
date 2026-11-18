package ru.spgsroot.peachy.data.downloader

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

class NetworkPolicyException(message: String) : IOException(message)

internal object PublicNetworkPolicy {
    const val MAX_REDIRECTS = 10

    /** Public HTTPS is validated again for every redirect, before any connection is attempted. */
    fun url(raw: String): HttpUrl {
        val parsed = raw.trim().toHttpUrlOrNull()
            ?: throw NetworkPolicyException("A valid HTTPS URL is required")
        validate(parsed)
        return parsed
    }

    fun requestBuilder(raw: String): Request.Builder = Request.Builder().url(url(raw))

    fun validate(request: Request) = validate(request.url)

    private fun validate(url: HttpUrl) {
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw NetworkPolicyException("Only public HTTPS URLs are allowed")
        }

        // OkHttp routes numeric hosts without calling PublicDns. Validate them before a call.
        val host = url.host
        val address = when {
            ':' in host -> try {
                InetAddress.getByName(host) // HttpUrl has already parsed and normalized this IPv6 literal.
            } catch (_: UnknownHostException) {
                throw NetworkPolicyException("Invalid IP address")
            }
            host.all { it in '0'..'9' || it == '.' } -> {
                val octets = host.split('.')
                if (octets.size != 4) throw NetworkPolicyException("Invalid IPv4 address")
                val bytes = ByteArray(4) { index ->
                    val octet = octets[index]
                    val value = octet.toIntOrNull()
                    if (value == null || value !in 0..255 ||
                        (octet.length > 1 && octet.startsWith('0'))
                    ) throw NetworkPolicyException("Invalid IPv4 address")
                    value.toByte()
                }
                InetAddress.getByAddress(bytes)
            }
            else -> return
        }
        if (!isPublicAddress(address)) throw NetworkPolicyException("The URL does not use a public address")
    }

    fun redirect(request: Request, response: Response): Request {
        val location = response.header("Location")
            ?: throw NetworkPolicyException("Redirect has no destination")
        val target = request.url.resolve(location) ?: throw NetworkPolicyException("Invalid redirect")
        validate(target)

        val sameOrigin = request.url.sameOrigin(target)
        val method = when (response.code) {
            301, 302, 303 -> if (request.method == "POST" ||
                (response.code == 303 && request.method != "HEAD")) "GET" else request.method
            else -> request.method
        }
        return request.newBuilder()
            .url(target)
            .apply {
                if (!sameOrigin) {
                    SENSITIVE_HEADERS.forEach(::removeHeader)
                }
                if (method == "GET") method("GET", null) else method(method, request.body)
            }
            .build()
    }

    private fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    private val SENSITIVE_HEADERS = setOf("Authorization", "Proxy-Authorization", "Cookie", "Cookie2", "Referer")
}

/** Rejects the whole answer when any route reaches a local, private, or otherwise non-public address. */
internal class PublicDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) {
            throw NetworkPolicyException("The URL does not resolve to a public address")
        }
        return addresses
    }
}

internal fun isPublicAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return false
    val bytes = address.address ?: return false
    return when (bytes.size) {
        4 -> isPublicIpv4(bytes)
        16 -> isPublicIpv6(bytes)
        else -> false
    }
}

private fun isPublicIpv4(a: ByteArray): Boolean {
    fun octet(index: Int) = a[index].toInt() and 0xff
    val first = octet(0)
    val second = octet(1)
    return when {
        first == 0 || first == 10 || first == 127 || first >= 224 -> false
        first == 100 && second in 64..127 -> false
        first == 169 && second == 254 -> false
        first == 172 && second in 16..31 -> false
        first == 192 && second == 168 -> false
        first == 192 && second == 0 && (octet(2) == 0 || octet(2) == 2) -> false
        (first == 198 && second in 18..19) || (first == 198 && second == 51 && octet(2) == 100) -> false
        first == 203 && second == 0 && octet(2) == 113 -> false
        else -> true
    }
}

private fun isPublicIpv6(a: ByteArray): Boolean {
    val first = a[0].toInt() and 0xff
    val second = a[1].toInt() and 0xff
    if (first and 0xfe == 0xfc) return false // Unique local fc00::/7.
    if (first == 0x20 && second == 0x01 && a[2].toInt() and 0xff == 0x0d &&
        a[3].toInt() and 0xff == 0xb8) return false // Documentation 2001:db8::/32.
    if (first == 0x64 && second == 0xff && a[2].toInt() and 0xff == 0x9b &&
        (a[3].toInt() and 0xff) != 1) {
        // DNS64/NAT64 can still expose an embedded non-public IPv4 destination.
        return isPublicIpv4(a.copyOfRange(12, 16))
    }
    if ((a.copyOfRange(0, 10).all { it == 0.toByte() } &&
            (a[10] == 0.toByte() || a[10] == 0xff.toByte()) && a[11] == a[10]) ||
        (a.copyOfRange(0, 12).all { it == 0.toByte() })) {
        return isPublicIpv4(a.copyOfRange(12, 16))
    }
    return true
}

/**
 * Common HTTP path for host pages and video bytes. Redirects are handled here so every hop gets
 * fresh URL and DNS checks, and credentials never cross an origin boundary.
 */
internal class SafeHttpClient(connectTimeoutSeconds: Long, readTimeoutSeconds: Long) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .dns(PublicDns())
        .proxy(Proxy.NO_PROXY) // A proxy would resolve the destination outside PublicDns.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun execute(request: Request): Response {
        PublicNetworkPolicy.validate(request)
        var current = request
        var redirects = 0
        while (true) {
            val response = client.newCall(current).execute()
            if (response.code !in REDIRECT_CODES) return response
            response.use {
                if (redirects == PublicNetworkPolicy.MAX_REDIRECTS) {
                    throw NetworkPolicyException("Too many redirects")
                }
                current = PublicNetworkPolicy.redirect(current, response)
                redirects++
            }
        }
    }

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
