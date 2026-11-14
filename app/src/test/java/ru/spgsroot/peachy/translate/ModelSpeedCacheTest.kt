package ru.spgsroot.peachy.translate

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelSpeedCacheTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun roundTripKeepsHostsModelsAndTimestampSeparateWithoutCredentials() {
        val cache = ModelSpeedCache(temp.root)
        cache.put("https://API.example/v1", "a/b", ModelSpeed(12, 30, 100))
        cache.put("https://other.example/v1", "a/b", ModelSpeed(20, 40, 200))
        cache.put("https://api.example/v2", "a/c", ModelSpeed(25, 50, 300))
        val reopened = ModelSpeedCache(temp.root)
        assertEquals(ModelSpeed(12, 30, 100), reopened.get("https://api.example/v3", "a/b"))
        assertEquals(ModelSpeed(20, 40, 200), reopened.get("https://other.example/v1", "a/b"))
        assertEquals(ModelSpeed(25, 50, 300), reopened.get("https://api.example/v1", "a/c"))
        assertNull(reopened.get("https://api.example/v1", "missing"))
    }

    @Test fun cacheEvictionOrCorruptionJustLeavesModelUnmeasured() {
        val cache = ModelSpeedCache(temp.root)
        cache.put("https://example.com/v1", "m", ModelSpeed(10, 20, 50))
        temp.root.walkTopDown().filter { it.isFile }.forEach { it.writeText("bad") }
        assertNull(cache.get("https://example.com/v1", "m"))
        temp.root.deleteRecursively()
        assertNull(cache.get("https://example.com/v1", "m"))
    }
}
