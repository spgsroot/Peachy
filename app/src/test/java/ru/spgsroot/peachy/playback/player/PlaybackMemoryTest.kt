package ru.spgsroot.peachy.playback.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackMemoryTest {
    @Test fun aVideoComesBackWhereItWasLeftAndPlayStateSurvives() {
        val memory = PlaybackMemory()
        memory.remember(1, positionMs = 42_000, durationMs = 600_000, playing = false)
        memory.remember(2, positionMs = 5_000, durationMs = 600_000, playing = true)
        assertEquals(PlaybackSnapshot(42_000, false), memory.recall(1))
        assertEquals(PlaybackSnapshot(5_000, true), memory.recall(2))
    }

    @Test fun recallingConsumesTheSnapshotSoTheLivePlayerIsTheTruth() {
        val memory = PlaybackMemory()
        memory.remember(1, 10_000, 60_000, true)
        memory.recall(1)
        assertNull(memory.recall(1))
    }

    @Test fun aVideoLeftAtItsStartOrItsEndRestartsFromTheBeginning() {
        val memory = PlaybackMemory()
        memory.remember(1, 20_000, 60_000, true)
        memory.remember(1, 0, 60_000, true)
        assertNull(memory.recall(1))
        memory.remember(2, 59_500, 60_000, true)
        assertNull(memory.recall(2))
        memory.remember(3, 58_900, 60_000, true)
        assertEquals(58_900L, memory.recall(3)?.positionMs)
    }

    @Test fun anUnknownDurationStillKeepsThePosition() {
        val memory = PlaybackMemory()
        memory.remember(1, 7_000, -1, true)
        assertEquals(7_000L, memory.recall(1)?.positionMs)
    }

    @Test fun forgettingDropsTheSnapshot() {
        val memory = PlaybackMemory()
        memory.remember(1, 7_000, 60_000, true)
        memory.forget(1)
        assertNull(memory.recall(1))
    }
}
