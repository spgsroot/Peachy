package ru.spgsroot.peachy.browser

import ru.spgsroot.peachy.browser.adblock.Hosts

/**
 * Native navigation rules of the embedded browser. Only HTTP(S) reaches a WebView, plain HTTP is
 * upgraded, and every top-level move to another site needs the user's consent. Popups are refused
 * by the WebView client, not by hiding them with scripts.
 */
object BrowserPolicy {

    /** Schemes a subframe may use; plain HTTP would be mixed content on an HTTPS page. */
    private val SUBFRAME_SCHEMES = setOf("https", "about", "blob", "data")

    /** True for the only addresses the browser loads. */
    fun isWebTarget(url: String): Boolean = BrowserUrls.isWebUrl(url)

    /** True when [url] is safe for an embedded frame: HTTPS plus in-page generated content. */
    fun isAllowedSubframe(url: String): Boolean =
        url.substringBefore(':', "").lowercase() in SUBFRAME_SCHEMES

    /** HTTPS form of [url] when it is plain HTTP; any other address is returned unchanged. */
    fun httpsOnly(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring("http://".length) else url

    /** True when [url] belongs to a different registrable site than [pageHost]. */
    fun isCrossSite(pageHost: String?, url: String): Boolean {
        if (pageHost.isNullOrEmpty()) return false
        val host = Hosts.hostOf(url) ?: return true
        return Hosts.registrable(host) != Hosts.registrable(pageHost)
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
