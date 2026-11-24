package ru.spgsroot.peachy.nsfw

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.spgsroot.peachy.security.screenshotsBlocked

class NsfwPolicyTest {
    @Test fun mangaRoutesAreClosedUntilTheSwitchIsOn() {
        assertTrue(NsfwRoutes.isNsfw("manga"))
        assertTrue(NsfwRoutes.isNsfw("manga/12"))
        assertTrue(NsfwRoutes.isNsfw("manga/12/read"))
        assertTrue(NsfwRoutes.isNsfw("manga/{id}"))
        assertTrue(NsfwRoutes.isNsfw("manga/{id}/read?page={page}"))
        assertTrue(NsfwRoutes.isNsfw("mangatags/tag"))
        assertTrue(NsfwRoutes.isNsfw("mangatags/{type}"))
    }

    @Test fun ordinaryDestinationsStayOpen() {
        assertFalse(NsfwRoutes.isNsfw(null))
        assertFalse(NsfwRoutes.isNsfw(""))
        assertFalse(NsfwRoutes.isNsfw("player"))
        assertFalse(NsfwRoutes.isNsfw("gallery"))
        assertFalse(NsfwRoutes.isNsfw("documents"))
        assertFalse(NsfwRoutes.isNsfw("documents/2f1c7f4e-1d2b-4a3c-9b8e-0123456789ab"))
    }

    @Test fun anUnknownSwitchValueDoesNotCountAsEnabled() {
        val gate = NsfwGate()
        assertFalse(gate.allows())
        gate.publish(null)
        assertFalse(gate.allows())
        gate.publish(false)
        assertFalse(gate.allows())
        gate.publish(true)
        assertTrue(gate.allows())
    }

    @Test fun onlyADebuggableBuildMayTakeScreenshots() {
        assertTrue(screenshotsBlocked(debuggable = false))
        assertFalse(screenshotsBlocked(debuggable = true))
    }
}
