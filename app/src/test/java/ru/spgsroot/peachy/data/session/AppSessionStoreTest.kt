package ru.spgsroot.peachy.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppSessionStoreTest {

    @Test
    fun onlyRoutesTheGraphCanNavigateToAreAccepted() {
        assertEquals("player", AppSessionStore.sanitize("player"))
        assertEquals("gallery", AppSessionStore.sanitize("gallery"))
        assertEquals("manga", AppSessionStore.sanitize("manga"))
        assertEquals("manga/12", AppSessionStore.sanitize("manga/12"))
        assertEquals("manga/12/read", AppSessionStore.sanitize("manga/12/read"))
        assertEquals("mangatags/character", AppSessionStore.sanitize("mangatags/character"))
        assertEquals("documents", AppSessionStore.sanitize("documents"))
        assertEquals(
            "documents/2f1c7f4e-1d2b-4a3c-9b8e-0123456789ab",
            AppSessionStore.sanitize("documents/2f1c7f4e-1d2b-4a3c-9b8e-0123456789ab")
        )
    }

    @Test
    fun aDocumentRouteNeedsARealSessionId() {
        assertNull(AppSessionStore.sanitize("documents/.."))
        assertNull(AppSessionStore.sanitize("documents/not-a-uuid"))
        assertNull(AppSessionStore.sanitize("documents/2f1c7f4e-1d2b-4a3c-9b8e-0123456789ab/ops"))
        assertNull(AppSessionStore.sanitize("documents/2f1c7f4e-1d2b-4a3c-9b8e-0123456789ab?x=1"))
    }

    @Test
    fun anythingElseIsNeverNavigatedTo() {
        assertNull(AppSessionStore.sanitize(null))
        assertNull(AppSessionStore.sanitize(""))
        assertNull(AppSessionStore.sanitize("player?x=1"))
        assertNull(AppSessionStore.sanitize("javascript:alert(1)"))
        assertNull(AppSessionStore.sanitize("manga/-1"))
        assertNull(AppSessionStore.sanitize("manga/1/read?page=abc"))
        assertNull(AppSessionStore.sanitize("mangatags/tag/extra"))
        assertNull(AppSessionStore.sanitize("manga/12/../admin"))
    }

    @Test
    fun aReaderPageArgumentIsDroppedSoDurableProgressWins() {
        assertEquals("manga/12/read", AppSessionStore.sanitize("manga/12/read?page=7"))
    }

    @Test
    fun storedFileKeepsRouteAndBrowserMarker() {
        val snapshot = AppSessionStore.parse("v1\nroute=manga/12/read\nbrowser=1\n")

        assertEquals("manga/12/read", snapshot.route)
        assertEquals(true, snapshot.browserOpen)
    }

    @Test
    fun aFileWithoutTheVersionLineRestoresNothing() {
        val snapshot = AppSessionStore.parse("route=manga/12\nbrowser=1\n")

        assertNull(snapshot.route)
        assertEquals(false, snapshot.browserOpen)
    }

    @Test
    fun aMalformedStoredRouteIsIgnored() {
        val snapshot = AppSessionStore.parse("v1\nroute=javascript:alert(1)\nbrowser=0\n")

        assertNull(snapshot.route)
        assertEquals(false, snapshot.browserOpen)
    }
}
