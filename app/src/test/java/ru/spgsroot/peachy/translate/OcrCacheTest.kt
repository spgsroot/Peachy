package ru.spgsroot.peachy.translate

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OcrCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val blocks = listOf(OcrBlock("文字", Box(.1f,.2f,.7f,.8f)))
    @Test fun roundTripKeepsTextAndBox() = runBlocking {
        FileOcrCache(folder.root).put(1,0,"ja",blocks)
        assertEquals(blocks,FileOcrCache(folder.root).get(1,0,"ja"))
    }
    @Test fun missingEntryIsNull() = runBlocking { assertNull(FileOcrCache(folder.root).get(1,0,"ja")) }
    @Test fun emptyListRoundTripsAsEmptyNotNull() = runBlocking {
        val c=FileOcrCache(folder.root); c.put(1,0,"ja",emptyList()); assertEquals(emptyList<OcrBlock>(),c.get(1,0,"ja"))
    }
    @Test fun corruptFileIsAMiss() = runBlocking {
        val file=File(folder.root,"1/0-ja-v${OCR_PIPELINE_VERSION}.json"); file.parentFile!!.mkdirs(); file.writeText("{\"blocks\":[")
        assertNull(FileOcrCache(folder.root).get(1,0,"ja"))
    }
    @Test fun differentSourceOrPageAreSeparateEntries() = runBlocking {
        val c=FileOcrCache(folder.root); c.put(1,0,"ja",blocks)
        assertNull(c.get(1,1,"ja")); assertNull(c.get(1,0,"en")); assertNull(c.get(2,0,"ja"))
    }
    @Test fun staleVersionFileIsIgnored() = runBlocking {
        val f=File(folder.root,"1/0-ja-v${OCR_PIPELINE_VERSION+1}.json"); f.parentFile!!.mkdirs(); f.writeText("{\"blocks\":[]}")
        assertNull(FileOcrCache(folder.root).get(1,0,"ja"))
    }
    @Test fun invalidBoxIsAMissAndWriteFailureIsIgnored() = runBlocking {
        val f=File(folder.root,"1/0-ja-v${OCR_PIPELINE_VERSION}.json"); f.parentFile!!.mkdirs()
        f.writeText("{\"blocks\":[{\"text\":\"text\",\"box\":[0.9,0,0.1,1]}]}")
        assertNull(FileOcrCache(folder.root).get(1,0,"ja"))
        val blocked=folder.newFile("blocked"); val c=FileOcrCache(blocked); c.put(1,0,"ja",blocks); assertNull(c.get(1,0,"ja"))
    }
}
