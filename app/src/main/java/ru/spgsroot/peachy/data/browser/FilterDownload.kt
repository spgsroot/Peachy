package ru.spgsroot.peachy.data.browser

import java.io.File
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.spgsroot.peachy.data.downloader.PublicNetworkPolicy

/** All children must be real, relative files below the publisher's directory. No cookies, referer, or redirects. */
internal class FilterDownload(private val client: OkHttpClient) {
    fun fetch(list: FilterCatalog.ListSpec, file: File) = file.bufferedWriter().use { fetch(list, it, 32L * 1024 * 1024) }

    fun fetch(list: FilterCatalog.ListSpec, out: Appendable, maxBytes: Long): Int {
        var total = 0L
        var rules = 0
        val stack = HashSet<HttpUrl>()
            fun read(url: HttpUrl, depth: Int) {
                if (depth > 8 || !stack.add(url)) throw IOException("Invalid include tree")
                client.newCall(Request.Builder().url(url).header("User-Agent", "Peachy filter updater").build()).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Filter update HTTP ${r.code}")
                    val input = r.body?.source() ?: throw IOException("Missing filter data")
                    val branches = ArrayDeque<Boolean>()
                    var first = true
                    while (!input.exhausted()) {
                        // A large or unterminated line must not allocate arbitrary memory.
                        val end = input.indexOf(10, 0, 128L * 1024)
                        if (end < 0 && input.buffer.size >= 128L * 1024) throw IOException("Oversized filter line")
                        val size = if (end < 0) input.buffer.size else end
                        val line = input.readUtf8(size).trimEnd('\r'); if (end >= 0) input.skip(1)
                        total += size + 1
                        if (total > maxBytes) throw IOException("Oversized subscription")
                        if (first && line.isNotBlank()) {
                            first = false
                            if (line.trimStart().startsWith('<')) throw IOException("Expected filter list, got HTML")
                            if (depth == 0 && list.id != "adult" && !line.startsWith("!") && !line.startsWith("[Adblock")) throw IOException("No subscription header")
                        }
                        when {
                            line.startsWith("!#if ") -> branches.addLast(condition(line.substring(5)))
                            line.trim() == "!#else" -> { if (branches.isEmpty()) throw IOException("Unmatched else"); branches.addLast(!branches.removeLast()) }
                            line.trim() == "!#endif" -> { if (branches.removeLastOrNull() == null) throw IOException("Unmatched endif") }
                            false in branches -> Unit
                            line.startsWith("!#include ") -> {
                                val rel = line.substring(10).trim()
                                if (!rel.matches(Regex("[A-Za-z0-9_./-]+")) || rel.startsWith('/') || rel.split('/').any { it == ".." || it.isEmpty() }) throw IOException("Nonlocal include refused")
                                read(url.resolve(rel) ?: throw IOException("Invalid include"), depth + 1)
                            }
                            else -> { out.appendLine(line); if (line.isNotBlank() && !line.startsWith('!') && !line.startsWith('[')) rules++ }
                        }
                    }
                    if (first || branches.isNotEmpty()) throw IOException("Incomplete subscription")
                }
                stack.remove(url)
            }
        list.urls.forEach { read(PublicNetworkPolicy.url(it), 0) }
        if (rules == 0) throw IOException("Empty subscription")
        return rules
    }

    // uBO conditions use a disjunction of conjunctions of optionally-negated capability names.
    private fun condition(expression: String): Boolean = expression.split("||").any { group ->
        group.split("&&").all { raw ->
            val token = raw.trim(); val value = token.removePrefix("!") in setOf("env_chromium", "env_mobile", "ext_ublock")
            if (token.startsWith('!')) !value else value
        }
    }
}
