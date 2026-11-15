package ru.spgsroot.peachy.ui.share

import ru.spgsroot.peachy.data.downloader.isDownloadUrl

/** Finds the download links in text that another app shared (a bare link, or a link in a sentence). */
object ShareLinks {
    private val link = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?)]}»"

    fun extract(text: CharSequence?): List<String> =
        link.findAll(text ?: "")
            .map { it.value.trimEnd { char -> char in TRAILING } }
            .filter(::isDownloadUrl)
            .distinct()
            .toList()
}
