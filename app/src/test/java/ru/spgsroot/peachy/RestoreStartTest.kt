package ru.spgsroot.peachy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestoreStartTest {
    @Test
    fun anUnknownAdultRouteComposesNothing() {
        assertNull(restoreLaunchChain("manga", null))
        assertNull(restoreLaunchChain("manga/12", null))
    }

    @Test
    fun aBlockedAdultRouteFallsBackToThePlayer() {
        assertEquals(listOf("player"), restoreLaunchChain("manga", false))
        assertEquals(listOf("player"), restoreLaunchChain(null, false))
    }

    @Test
    fun aSavedHubRoutePutsThePlayerUnderItWithoutOpeningThePlayerFirst() {
        assertEquals(listOf("player", "gallery"), restoreLaunchChain("gallery", true))
        assertEquals(listOf("player", "manga"), restoreLaunchChain("manga", true))
        assertEquals(listOf("player", "manga", "manga/12"), restoreLaunchChain("manga/12", true))
    }
}
