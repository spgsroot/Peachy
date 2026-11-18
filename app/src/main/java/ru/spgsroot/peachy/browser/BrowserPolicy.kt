package ru.spgsroot.peachy.browser

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Native navigation rules of the embedded browser. Only HTTP(S) reaches a WebView, plain HTTP is
 * upgraded, and every top-level move to another site needs the user's consent. Popups are refused
 * by the WebView client, not by hiding them with scripts.
 */
object BrowserPolicy {

    /** Schemes a subframe may use; plain HTTP would be mixed content on an HTTPS page. */
    private val SUBFRAME_SCHEMES = setOf("https", "about", "blob", "data")

    /** True for HTTP(S) input that can be normalized before the browser loads it. */
    fun isWebTarget(url: String): Boolean = BrowserUrls.isWebUrl(url)

    /** True for an address that may actually be committed or persisted by the browser. */
    fun isSecureWebTarget(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) && BrowserUrls.isWebUrl(url)

    /** True when [url] is safe for an embedded frame: HTTPS plus in-page generated content. */
    fun isAllowedSubframe(url: String): Boolean =
        url.substringBefore(':', "").lowercase() in SUBFRAME_SCHEMES

    /** HTTPS form of [url] when it is plain HTTP; any other address is returned unchanged. */
    fun httpsOnly(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring("http://".length) else url

    /** True when [url] belongs to a different registrable site than [pageHost]. */
    fun isCrossSite(pageHost: String?, url: String): Boolean {
        if (pageHost.isNullOrEmpty()) return false
        val target = url.toHttpUrlOrNull() ?: return true
        val authority = if (':' in pageHost) "[$pageHost]" else pageHost
        val origin = "https://$authority/".toHttpUrlOrNull() ?: return true
        return (target.topPrivateDomain() ?: target.host) != (origin.topPrivateDomain() ?: origin.host)
    }

    /**
     * A top-level move to another registrable site always needs the user's consent, including a
     * tapped cross-site link: a tap proves contact with the page, not the destination, and click
     * handlers can redirect elsewhere or cover links with invisible overlays. Same-site moves and
     * address-bar entries do not ask. A page whose host is still unknown is not gated.
     */
    fun requiresConfirmation(pageHost: String?, target: String): Boolean =
        isCrossSite(pageHost, target)
}
