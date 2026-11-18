package ru.spgsroot.peachy.data.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The last screen of the unlocked app, kept for the next launch and for the next unlock.
 *
 * Only what the navigation graph can reproduce is stored: a whitelisted destination route plus
 * whether the embedded browser was the top screen. The password, the session, page addresses and
 * page content are never written here; the file lives in [Context.noBackupFilesDir], so it is not
 * included in cloud backup either.
 *
 * The reader route is stored without its `page` argument: reading position belongs to the reader's
 * own durable progress, and a stale page number from this file must not override it. An explicit
 * page chosen by the user is passed on the route at the moment of navigation, not restored later.
 *
 * A document route is stored with its session id only. What the editor had open inside the session
 * (operation list, working copy, dirty flag) is restored by the session store in the documents
 * package, so this file never carries document content, names or paths.
 */
@Singleton
class AppSessionStore @Inject constructor(@ApplicationContext context: Context) {

    data class Snapshot(val route: String?, val browserOpen: Boolean)

    /**
     * Process-local, never written: whether a browser screen is showing or starting. The restored
     * launch checks it so a recreated root behind a live browser cannot start a second one.
     */
    @Volatile
    var browserVisible: Boolean = false

    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private var cached: Snapshot? = null

    /** The last destination and whether the browser was on top; [Snapshot] with no route when unset. */
    @Synchronized
    fun read(): Snapshot {
        cached?.let { return it }
        val parsed = parse(file.takeIf { it.length() in 1L..MAX_FILE_BYTES }?.readText())
        cached = parsed
        return parsed
    }

    /** Remembers the current destination route; an unknown or malformed route is ignored. */
    @Synchronized
    fun saveRoute(route: String) {
        val safe = sanitize(route) ?: return
        val current = read()
        if (current.route == safe) return
        write(Snapshot(safe, current.browserOpen))
    }

    /** Marks that the embedded browser is the screen the user should return to after unlocking. */
    @Synchronized
    fun setBrowserOpen(open: Boolean) {
        val current = read()
        if (current.browserOpen == open) return
        write(Snapshot(current.route, open))
    }

    private fun write(snapshot: Snapshot) {
        cached = snapshot
        val text = buildString {
            append("v1\n")
            snapshot.route?.let { append("route=").append(it).append('\n') }
            append("browser=").append(if (snapshot.browserOpen) "1" else "0").append('\n')
        }
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        // A tiny file rewritten on navigation: the rename keeps a torn write from being read back.
        val written = runCatching {
            file.parentFile?.mkdirs()
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                temp.delete()
                false
            } else {
                true
            }
        }.getOrDefault(false)
        if (!written) temp.delete()
    }

    companion object {
        private const val FILE_NAME = "app_session"
        private const val MAX_FILE_BYTES = 2L * 1024

        private val TOP_LEVEL = setOf("player", "gallery", "manga", "documents")
        private val GALLERY = Regex("^manga/(\\d{1,10})$")
        private val READER = Regex("^manga/(\\d{1,10})/read$")
        private val TAGS = Regex("^mangatags/[a-z\\-]{1,24}$")

        /** A document session route: the id is the uuid that names the session directory. */
        private val DOCUMENT = Regex(
            "^documents/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
        )

        /** Parses the versioned key=value file; anything unknown, oversized or malformed is dropped. */
        internal fun parse(text: String?): Snapshot {
            if (text.isNullOrBlank()) return Snapshot(null, false)
            var versionSeen = false
            var route: String? = null
            var browserOpen = false
            for (line in text.lineSequence()) {
                if (line.length > MAX_LINE_LENGTH) continue
                when {
                    !versionSeen -> versionSeen = line == "v1"
                    line.startsWith("route=") -> route = sanitize(line.removePrefix("route="))
                    line == "browser=1" -> browserOpen = true
                }
            }
            return Snapshot(route, browserOpen)
        }

        /**
         * Only concrete routes the graph can navigate to are accepted: a hand-edited file must never
         * turn into an arbitrary navigation command. The reader loses its page argument on purpose.
         */
        internal fun sanitize(route: String?): String? {
            val text = route?.trim() ?: return null
            if (text.length > MAX_ROUTE_LENGTH) return null
            if (text in TOP_LEVEL) return text
            GALLERY.matchEntire(text)?.let { return text }
            READER.matchEntire(text)?.let { return text }
            TAGS.matchEntire(text)?.let { return text }
            DOCUMENT.matchEntire(text)?.let { return text }
            // A concrete reader route saved by an older build: keep the gallery, drop the page.
            Regex("^manga/(\\d{1,10})/read\\?page=\\d{1,9}$").matchEntire(text)?.let {
                return "manga/${it.groupValues[1]}/read"
            }
            return null
        }

        private const val MAX_ROUTE_LENGTH = 120
        private const val MAX_LINE_LENGTH = 256
    }
}
