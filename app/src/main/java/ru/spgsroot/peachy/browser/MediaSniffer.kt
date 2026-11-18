package ru.spgsroot.peachy.browser

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONTokener
import ru.spgsroot.peachy.browser.adblock.Hosts

enum class MediaKind { FILE, STREAM }

/** A video the page loads or embeds. [stream] items (HLS, DASH) cannot be saved as one file. */
data class FoundVideo(val url: String, val kind: MediaKind)

/** Finds downloadable videos on a page and decides whether a download target is acceptable. */
object MediaSniffer {
    private val FILE_EXTENSIONS = setOf("mp4", "m4v", "webm", "mkv", "mov")
    private val STREAM_EXTENSIONS = setOf("m3u8", "mpd")
    private const val MAX_FOUND = 40

    /** Classifies a request URL by the extension of its path, or null when it is not video. */
    fun classify(url: String): MediaKind? {
        val path = url.substringBefore('#').substringBefore('?')
        val name = path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in FILE_EXTENSIONS -> MediaKind.FILE
            in STREAM_EXTENSIONS -> MediaKind.STREAM
            else -> null
        }
    }

    /**
     * Script run on demand (never injected into every page): reads `<video>` and `<source>`
     * elements and returns their addresses as a JSON string. Nothing is exposed to the page.
     */
    const val COLLECT_SCRIPT = "(function(){var o=[];function a(u){if(u&&typeof u==='string'&&u.indexOf('http')===0)o.push(u);}" +
        "document.querySelectorAll('video').forEach(function(v){a(v.currentSrc);a(v.src);});" +
        "document.querySelectorAll('video source,audio source').forEach(function(s){a(s.src);});" +
        "return JSON.stringify(o);})()"

    /** Parses what `evaluateJavascript(COLLECT_SCRIPT)` returned into videos; junk becomes empty. */
    fun parseCollected(raw: String?): List<FoundVideo> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        return try {
            val value = JSONTokener(raw).nextValue()
            val array = JSONArray(value as? String ?: return emptyList())
            val out = LinkedHashMap<String, FoundVideo>()
            for (i in 0 until array.length()) {
                val url = array.optString(i)
                if (!BrowserUrls.isWebUrl(url)) continue
                // blob: sources are skipped above; a typeless URL is shown as a file, the user decides.
                val kind = classify(url) ?: MediaKind.FILE
                out.putIfAbsent(url, FoundVideo(url, kind))
                if (out.size >= MAX_FOUND) break
            }
            out.values.toList()
        } catch (e: JSONException) {
            emptyList()
        }
    }

    /**
     * Downloads are started by a page's content, so a page must not be able to aim the app at the
     * user's own network. Loopback, private and link-local literals and `.local` names are
     * refused unless the user is browsing that very host. DNS names that resolve to such
     * addresses are not detected here.
     */
    fun isAllowedDownload(url: String, pageHost: String?): Boolean {
        if (!BrowserUrls.isWebUrl(url)) return false
        val host = Hosts.hostOf(url) ?: return false
        if (pageHost != null && host == pageHost) return true
        return !isPrivateHost(host)
    }

    internal fun isPrivateHost(host: String): Boolean {
        val h = host.trim().trimEnd('.').removeSurrounding("[", "]").lowercase()
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) return true
        if (h.contains(':')) {
            val words = ipv6Words(h) ?: return true
            val first = words[0]
            if (words.all { it == 0 } || words.drop(6).let { it[0] == 0 && it[1] == 1 } && words.take(6).all { it == 0 }) return true
            if (first and 0xfe00 == 0xfc00 || first and 0xffc0 == 0xfe80) return true
            // IPv4-mapped and IPv4-compatible literals inherit the exact IPv4 range decision;
            // a mapped public address is not private merely because it has an IPv6 spelling.
            if (words.take(5).all { it == 0 } && (words[5] == 0xffff || words[5] == 0)) {
                return isPrivateIpv4(((words[6].toLong() shl 16) or words[7].toLong()))
            }
            return false
        }
        val address = ipv4Address(h)
        if (address != null) return isPrivateIpv4(address)
        // Malformed numeric address spellings stay blocked instead of becoming a DNS bypass.
        return h.isNotEmpty() && h.split('.').all { part ->
            part.matches(Regex("0[xX][0-9a-fA-F]+|0[0-7]*|[0-9]+"))
        }
    }

    /** Parses the four-part and legacy one/two/three-part numeric IPv4 forms without DNS. */
    private fun ipv4Address(host: String): Long? {
        val labels = host.split('.')
        if (labels.size !in 1..4 || labels.any { it.isEmpty() }) return null
        val values = labels.map { label ->
            when {
                label.startsWith("0x") -> label.substring(2).toLongOrNull(16)
                label.length > 1 && label.startsWith('0') -> label.substring(1).toLongOrNull(8)
                else -> label.toLongOrNull(10)
            } ?: return null
        }
        if (values.dropLast(1).any { it !in 0..255 }) return null
        val lastBits = 8 * (5 - labels.size)
        if (values.last() !in 0..((1L shl lastBits) - 1)) return null
        var address = 0L
        values.dropLast(1).forEach { address = (address shl 8) or it }
        return (address shl lastBits) or values.last()
    }

    private fun isPrivateIpv4(address: Long): Boolean {
        val a = (address ushr 24).toInt()
        val b = (address ushr 16).toInt() and 0xff
        return a == 10 || a == 127 || a == 0 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 100 && b in 64..127)
    }

    /** Eight 16-bit words, or null for a malformed literal; no resolver is consulted. */
    private fun ipv6Words(host: String): List<Int>? {
        val halves = host.substringBefore('%').split("::")
        if (halves.size !in 1..2) return null

        fun words(part: String, ipv4AtEnd: Boolean): List<Int>? {
            if (part.isEmpty()) return emptyList()
            val pieces = part.split(':')
            val out = ArrayList<Int>(8)
            pieces.forEachIndexed { index, piece ->
                if (piece.contains('.')) {
                    if (!ipv4AtEnd || index != pieces.lastIndex) return null
                    val address = ipv4Address(piece) ?: return null
                    out += (address ushr 16).toInt() and 0xffff
                    out += address.toInt() and 0xffff
                } else {
                    val word = piece.toIntOrNull(16) ?: return null
                    if (word !in 0..0xffff) return null
                    out += word
                }
            }
            return out
        }

        val left = words(halves[0], ipv4AtEnd = halves.size == 1) ?: return null
        val right = if (halves.size == 2) words(halves[1], ipv4AtEnd = true) ?: return null else emptyList()
        if (halves.size == 1 && left.size != 8 || halves.size == 2 && left.size + right.size >= 8) return null
        return left + List(8 - left.size - right.size) { 0 } + right
    }
}
