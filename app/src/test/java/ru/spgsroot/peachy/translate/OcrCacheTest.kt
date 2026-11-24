package ru.spgsroot.peachy.translate

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OcrCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val blocks = listOf(OcrBlock("文字", Box(.1f, .2f, .7f, .8f)))
    private val scan = PageScan(blocks, listOf(Region(Box(.05f, .1f, .8f, .9f), .9f)), .7f)
    private val validJson = """{"aspect":0.7,"blocks":[{"text":"文字","box":[0.1,0.2,0.7,0.8]}],"regions":[{"box":[0.05,0.1,0.8,0.9],"score":0.9}]}"""

    private fun fixture(name: String = "1/0-ja-v$OCR_PIPELINE_VERSION-dc6b6341.json", json: String = validJson): File =
        File(folder.root, name).apply { parentFile!!.mkdirs(); writeText(json) }

    @Test fun roundTripKeepsBlocksRegionsAndAspect() = runBlocking {
        FileOcrCache(folder.root).put(1, 0, "ja", scan)
        assertEquals(scan, FileOcrCache(folder.root).get(1, 0, "ja"))
        val json = JSONObject(File(folder.root, "1/0-ja-v$OCR_PIPELINE_VERSION-dc6b6341.json").readText())
        assertEquals(setOf("aspect", "blocks", "regions"), json.keySet())
        assertEquals(.7, json.getDouble("aspect"), 1e-6)
        val block = json.getJSONArray("blocks").getJSONObject(0)
        assertEquals(setOf("text", "box"), block.keySet())
        assertEquals("文字", block.getString("text"))
        assertEquals(listOf(.1, .2, .7, .8), (0..3).map { block.getJSONArray("box").getDouble(it) })
        val region = json.getJSONArray("regions").getJSONObject(0)
        assertEquals(setOf("box", "score"), region.keySet())
        assertEquals(listOf(.05, .1, .8, .9), (0..3).map { region.getJSONArray("box").getDouble(it) })
        assertEquals(.9, region.getDouble("score"), 1e-6)
    }

    @Test fun readsTheDocumentedJsonShape() = runBlocking {
        fixture()
        assertEquals(scan, FileOcrCache(folder.root).get(1, 0, "ja"))
    }

    @Test fun cacheKeepsTheTightSourceForCropsAndTheSeparateInteriorForSpeech() = runBlocking {
        val source = blocks.single().copy(interior = Box(.07f, .16f, .74f, .85f))
        val cache = FileOcrCache(folder.root)
        cache.put(1, 0, "en", scan.copy(blocks = listOf(source)))
        val restored = cache.get(1, 0, "en")!!.blocks.single()
        assertEquals(source.box, restored.box)
        assertEquals(source.interior, restored.translated(LineKind.Speech, "Hello").box)
        assertEquals(source.box, restored.translated(LineKind.Narration, "Caption").box)
    }

    @Test fun emptyRegionsAreValid() = runBlocking {
        val noRegions = PageScan(blocks, emptyList(), .7f)
        val cache = FileOcrCache(folder.root)
        cache.put(1, 0, "ja", noRegions)
        assertEquals(noRegions, cache.get(1, 0, "ja"))
    }

    @Test fun missingEntryIsNull() = runBlocking { assertNull(FileOcrCache(folder.root).get(1, 0, "ja")) }

    @Test fun emptyScanRoundTripsAsEmptyNotNull() = runBlocking {
        val empty = PageScan(emptyList(), emptyList(), 1f)
        val cache = FileOcrCache(folder.root)
        cache.put(1, 0, "ja", empty)
        assertEquals(empty, cache.get(1, 0, "ja"))
    }

    @Test fun corruptFileIsAMiss() = runBlocking {
        fixture(json = "{\"blocks\":[")
        assertNull(FileOcrCache(folder.root).get(1, 0, "ja"))
    }

    @Test fun differentSourceOrPageAreSeparateEntries() = runBlocking {
        val cache = FileOcrCache(folder.root)
        cache.put(1, 0, "ja", scan)
        assertNull(cache.get(1, 1, "ja"))
        assertNull(cache.get(1, 0, "en"))
        assertNull(cache.get(2, 0, "ja"))
    }

    @Test fun staleVersionFileIsIgnored() = runBlocking {
        fixture("1/0-ja-v2-dc6b6341.json")
        assertNull(FileOcrCache(folder.root).get(1, 0, "ja"))
    }

    @Test fun invalidBlockBoxIsAMiss() = runBlocking {
        val badBoxes = listOf("[0.9,0,0.1,1]", "[0,0,0,1]", "[-0.1,0,1,1]", "[0,0,1,1.1]", "[0,0,1]", "[0,0,1,\"NaN\"]")
        for (box in badBoxes) {
            fixture(json = validJson.replace("[0.1,0.2,0.7,0.8]", box))
            assertNull(box, FileOcrCache(folder.root).get(1, 0, "ja"))
        }
    }

    @Test fun invalidRegionBoxIsAMiss() = runBlocking {
        val badBoxes = listOf("[0,0,1,0]", "[0,0.9,1,0.1]", "[0,-0.1,1,1]", "[0,0,1.1,1]", "[0,0,1,1,1]", "[0,0,\"Infinity\",1]")
        for (box in badBoxes) {
            fixture(json = validJson.replace("[0.05,0.1,0.8,0.9]", box))
            assertNull(box, FileOcrCache(folder.root).get(1, 0, "ja"))
        }
    }

    @Test fun badAspectIsAMiss() = runBlocking {
        for (aspect in listOf("0", "-1", "\"NaN\"", "\"Infinity\"", "1e1000", "1e-1000")) {
            fixture(json = validJson.replace("\"aspect\":0.7", "\"aspect\":$aspect"))
            assertNull(aspect, FileOcrCache(folder.root).get(1, 0, "ja"))
        }
    }

    @Test fun invalidScoreIsAMiss() = runBlocking {
        for (score in listOf("-0.1", "1.1", "\"NaN\"", "\"Infinity\"")) {
            fixture(json = validJson.replace("\"score\":0.9", "\"score\":$score"))
            assertNull(score, FileOcrCache(folder.root).get(1, 0, "ja"))
        }
    }

    @Test fun scoreEndpointsAndFullPageBoxesAreValid() = runBlocking {
        val endpoints = PageScan(blocks, listOf(Region(Box(0f, 0f, 1f, 1f), 0f), Region(Box(0f, 0f, 1f, 1f), 1f)), 1f)
        val cache = FileOcrCache(folder.root)
        cache.put(1, 0, "ja", endpoints)
        assertEquals(endpoints, cache.get(1, 0, "ja"))
    }

    @Test fun otherTagIsAMiss() = runBlocking {
        val other = FileOcrCache(folder.root, "deadbeef")
        other.put(1, 0, "ja", scan)
        assertTrue(File(folder.root, "1/0-ja-v$OCR_PIPELINE_VERSION-deadbeef.json").isFile)
        assertNull(FileOcrCache(folder.root).get(1, 0, "ja"))
        assertEquals(scan, other.get(1, 0, "ja"))
    }

    @Test fun putRemovesStaleGenerations() = runBlocking {
        val old = fixture("1/3-auto-v1.json", "{\"blocks\":[]}")
        val otherTag = fixture("1/3-auto-v2-deadbeef.json")
        val otherPage = fixture("1/4-auto-v1.json")
        val otherSource = fixture("1/3-ja-v1.json")
        val prefixedPage = fixture("1/13-auto-v1.json")
        FileOcrCache(folder.root).put(1, 3, "auto", scan)
        assertFalse(old.exists())
        assertFalse(otherTag.exists())
        assertTrue(otherPage.isFile)
        assertTrue(otherSource.isFile)
        assertTrue(prefixedPage.isFile)
        assertEquals(scan, FileOcrCache(folder.root).get(1, 3, "auto"))
    }

    @Test fun oldBlocksOnlyFileIsAMiss() = runBlocking {
        val malformed = fixture(json = "{\"blocks\":[]}")
        assertNull(FileOcrCache(folder.root).get(1, 0, "ja"))
        malformed.delete()
        fixture("1/0-ja-v1.json", "{\"blocks\":[]}")
        assertNull(FileOcrCache(folder.root).get(1, 0, "ja"))
    }

    @Test fun writeFailureIsIgnored() = runBlocking {
        val cache = FileOcrCache(folder.newFile("blocked"))
        cache.put(1, 0, "ja", scan)
        assertNull(cache.get(1, 0, "ja"))
    }

    @Test fun failedAtomicReplacementKeepsStaleGenerationsAndCleansTemp() = runBlocking {
        val old = fixture("1/3-auto-v1.json", "{\"blocks\":[]}")
        File(folder.root, "1/3-auto-v$OCR_PIPELINE_VERSION-dc6b6341.json").mkdir()
        FileOcrCache(folder.root).put(1, 3, "auto", scan)
        assertTrue(old.isFile)
        assertEquals(emptyList<String>(), File(folder.root, "1").listFiles()!!.filter { it.extension == "tmp" }.map { it.name })
    }
}
