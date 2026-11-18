package ru.spgsroot.peachy.data.browser

import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowserSessionStoreTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun store(): Pair<BrowserSessionStore, File> {
        val file = File(temporary.newFolder(), "browser_session.bin")
        return BrowserSessionStore(file) to file
    }

    /** Writes a session file by hand, to check what the store refuses to trust. */
    private fun raw(file: File, version: Int = BrowserSessionStore.VERSION, body: DataOutputStream.() -> Unit) {
        DataOutputStream(file.outputStream()).use { out ->
            out.writeInt(BrowserSessionStore.MAGIC)
            out.writeInt(version)
            out.body()
        }
    }

    @Test
    fun sessionRoundTripsTabsHistoryAndScroll() {
        val (store, _) = store()
        val snapshot = BrowserSessionStore.Snapshot(
            tabs = listOf(
                BrowserSessionStore.Tab(
                    id = 3,
                    url = "https://a.example/b",
                    title = "B",
                    scrollY = 1200,
                    history = listOf("https://a.example/a", "https://a.example/b"),
                    historyIndex = 1
                ),
                BrowserSessionStore.Tab(
                    id = 7,
                    url = "https://b.example/",
                    title = "B",
                    scrollY = 0,
                    history = listOf("https://b.example/"),
                    historyIndex = 0
                )
            ),
            activeId = 7
        )
        store.save(snapshot)

        assertEquals(snapshot, store.load())
    }

    @Test
    fun blankStartPageTabSurvivesRestart() {
        val (store, _) = store()
        store.save(
            BrowserSessionStore.Snapshot(
                tabs = listOf(BrowserSessionStore.Tab(1, "", "", 0)),
                activeId = 1
            )
        )

        assertEquals(BrowserSessionStore.Tab(1, "", "", 0), store.load()?.tabs?.single())
    }

    @Test
    fun activeTabFallsBackToTheFirstStoredTab() {
        val (store, _) = store()
        store.save(
            BrowserSessionStore.Snapshot(
                tabs = listOf(
                    BrowserSessionStore.Tab(4, "https://a.example/", "A", 0),
                    BrowserSessionStore.Tab(5, "https://b.example/", "B", 0)
                ),
                activeId = 99
            )
        )

        assertEquals(4, store.load()?.activeId)
    }

    @Test
    fun invalidTabsAreDroppedWhenSaving() {
        val (store, _) = store()
        store.save(
            BrowserSessionStore.Snapshot(
                tabs = listOf(
                    BrowserSessionStore.Tab(0, "https://a.example/", "A", 0),
                    BrowserSessionStore.Tab(2, "javascript:alert(1)", "bad", 0),
                    BrowserSessionStore.Tab(3, "https://b.example/", "B", 0)
                ),
                activeId = 3
            )
        )

        assertEquals(listOf(3), store.load()?.tabs?.map { it.id })
    }

    @Test
    fun savingNothingRemovesThePreviousSession() {
        val (store, file) = store()
        store.save(
            BrowserSessionStore.Snapshot(listOf(BrowserSessionStore.Tab(1, "https://a.example/", "A", 0)), 1)
        )
        assertTrue(file.isFile)

        store.save(BrowserSessionStore.Snapshot(emptyList(), 0))

        assertFalse(file.exists())
        assertNull(store.load())
    }

    @Test
    fun tabsWithoutHistoryKeepTheirAddressAsTheEntry() {
        val (store, _) = store()
        store.save(
            BrowserSessionStore.Snapshot(listOf(BrowserSessionStore.Tab(1, "https://a.example/", "A", 0)), 1)
        )

        val tab = store.load()?.tabs?.single()
        assertEquals(listOf("https://a.example/"), tab?.history)
        assertEquals(0, tab?.historyIndex)
    }

    @Test
    fun historyIsBoundedAndKeepsTheCurrentPosition() {
        val (store, _) = store()
        val entries = (1..BrowserSessionStore.MAX_HISTORY * 2).map { "https://a.example/p$it" }
        store.save(
            BrowserSessionStore.Snapshot(
                tabs = listOf(
                    BrowserSessionStore.Tab(
                        id = 1,
                        url = entries.last(),
                        title = "long",
                        scrollY = 0,
                        history = entries,
                        historyIndex = entries.lastIndex
                    )
                ),
                activeId = 1
            )
        )

        val tab = store.load()?.tabs?.single()
        assertEquals(BrowserSessionStore.MAX_HISTORY, tab?.history?.size)
        assertEquals(BrowserSessionStore.MAX_HISTORY - 1, tab?.historyIndex)
        assertEquals(entries.last(), tab?.history?.last())
    }

    @Test
    fun boundedWindowKeepsTheCurrentEntryInside() {
        val (store, _) = store()
        val entries = (1..BrowserSessionStore.MAX_HISTORY * 2).map { "https://a.example/p$it" }
        // The user is looking at the oldest entry; trimming must leave it at the front, not behind.
        store.save(
            BrowserSessionStore.Snapshot(
                tabs = listOf(
                    BrowserSessionStore.Tab(1, entries.first(), "old", 0, entries, 0)
                ),
                activeId = 1
            )
        )

        val tab = store.load()?.tabs?.single()
        assertEquals(0, tab?.historyIndex)
        assertEquals(entries.take(BrowserSessionStore.MAX_HISTORY), tab?.history)
    }

    @Test
    fun legacyVersionUsesItsAddressAsHistory() {
        val (store, file) = store()
        raw(file, version = BrowserSessionStore.LEGACY_VERSION) {
            writeInt(1)
            writeInt(4)
            writeUTF("https://a.example/page")
            writeUTF("A")
            writeInt(120)
            writeInt(4)
        }

        val tab = store.load()?.tabs?.single()
        assertEquals("https://a.example/page", tab?.url)
        assertEquals(listOf("https://a.example/page"), tab?.history)
        assertEquals(0, tab?.historyIndex)
    }

    @Test
    fun foreignFileIsRejectedAndRemoved() {
        val (store, file) = store()
        file.writeText("not a session")

        assertNull(store.load())
        assertFalse(file.exists())
    }

    @Test
    fun otherVersionIsRejected() {
        val (store, file) = store()
        raw(file, version = BrowserSessionStore.VERSION + 1) {
            writeInt(1)
            writeInt(1)
            writeUTF("https://a.example/")
            writeUTF("A")
            writeInt(0)
            writeInt(1)
        }

        assertNull(store.load())
    }

    @Test
    fun oversizedFileIsRejectedWithoutReadingIt() {
        val (store, file) = store()
        file.writeBytes(ByteArray((BrowserSessionStore.MAX_FILE_BYTES + 1).toInt()))

        assertNull(store.load())
        assertFalse(file.exists())
    }

    @Test
    fun nonWebAddressIsRejected() {
        val (store, file) = store()
        raw(file) {
            writeInt(1)
            writeInt(1)
            writeUTF("file:///data/local/tmp/x.html")
            writeUTF("A")
            writeInt(0)
            writeInt(0)
            writeInt(-1)
            writeInt(1)
        }

        assertNull(store.load())
    }

    @Test
    fun nonWebHistoryEntryIsRejected() {
        val (store, file) = store()
        raw(file) {
            writeInt(1)
            writeInt(1)
            writeUTF("https://a.example/")
            writeUTF("A")
            writeInt(0)
            writeInt(2)
            writeUTF("https://a.example/")
            writeUTF("javascript:alert(1)")
            writeInt(0)
            writeInt(1)
        }

        assertNull(store.load())
    }

    @Test
    fun historyIndexOutsideHistoryIsRejected() {
        val (store, file) = store()
        raw(file) {
            writeInt(1)
            writeInt(1)
            writeUTF("https://a.example/")
            writeUTF("A")
            writeInt(0)
            writeInt(1)
            writeUTF("https://a.example/")
            writeInt(1)
            writeInt(1)
        }

        assertNull(store.load())
    }

    @Test
    fun emptyHistoryMustCarryTheStartPageIndex() {
        val (store, file) = store()
        raw(file) {
            writeInt(1)
            writeInt(1)
            writeUTF("")
            writeUTF("")
            writeInt(0)
            writeInt(0)
            writeInt(0)
            writeInt(1)
        }

        assertNull(store.load())
    }

    @Test
    fun startPageTabWithHistoryIsRejected() {
        val (store, file) = store()
        raw(file) {
            writeInt(1)
            writeInt(1)
            writeUTF("")
            writeUTF("")
            writeInt(0)
            writeInt(1)
            writeUTF("https://a.example/")
            writeInt(0)
            writeInt(1)
        }

        assertNull(store.load())
    }

    @Test
    fun duplicateTabIdsAreRejected() {
        val (store, file) = store()
        raw(file) {
            writeInt(2)
            repeat(2) {
                writeInt(1)
                writeUTF("https://a.example/")
                writeUTF("A")
                writeInt(0)
                writeInt(1)
                writeUTF("https://a.example/")
                writeInt(0)
            }
            writeInt(1)
        }

        assertNull(store.load())
    }

    @Test
    fun truncatedFileIsRejected() {
        val (store, file) = store()
        store.save(
            BrowserSessionStore.Snapshot(listOf(BrowserSessionStore.Tab(1, "https://a.example/", "A", 9)), 1)
        )
        val whole = file.readBytes()
        file.writeBytes(whole.copyOf(whole.size - 4))

        assertNull(store.load())
    }
}
