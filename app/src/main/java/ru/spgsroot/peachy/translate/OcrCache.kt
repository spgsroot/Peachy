package ru.spgsroot.peachy.translate

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

const val OCR_PIPELINE_VERSION = 1

interface OcrCache {
    suspend fun get(galleryId: Int, page: Int, source: String): List<OcrBlock>?
    suspend fun put(galleryId: Int, page: Int, source: String, blocks: List<OcrBlock>)
}

/** Derived local data only; a miss never prevents recognition. */
class FileOcrCache(private val dir: File) : OcrCache {
    private fun entry(galleryId: Int, page: Int, source: String): File =
        File(File(dir, galleryId.toString()), "$page-$source-v$OCR_PIPELINE_VERSION.json")

    override suspend fun get(galleryId: Int, page: Int, source: String): List<OcrBlock>? = withContext(Dispatchers.IO) {
        try {
            val blocks = JSONObject(entry(galleryId, page, source).readText()).getJSONArray("blocks")
            List(blocks.length()) { index ->
                val block = blocks.getJSONObject(index)
                val text = block.get("text") as? String ?: throw JSONException("Invalid text")
                val box = block.getJSONArray("box")
                if (box.length() != 4) throw JSONException("Invalid box")
                val l = box.getDouble(0).toFloat(); val t = box.getDouble(1).toFloat()
                val r = box.getDouble(2).toFloat(); val b = box.getDouble(3).toFloat()
                if (!(l >= 0f && t >= 0f && r <= 1f && b <= 1f && l < r && t < b)) throw JSONException("Invalid box")
                OcrBlock(text, Box(l,t,r,b))
            }
        } catch (_: IOException) { null }
        catch (_: JSONException) { null }
        catch (_: SecurityException) { null }
    }

    override suspend fun put(galleryId: Int, page: Int, source: String, blocks: List<OcrBlock>): Unit = withContext(Dispatchers.IO) {
        var temp: File? = null
        try {
            val target = entry(galleryId, page, source)
            target.parentFile!!.mkdirs()
            // Unique same-directory temp + atomic replacement: readers see all-old or all-new JSON.
            // A crash leaves at most an ignored .tmp; malformed existing JSON is a cache miss.
            temp = File.createTempFile("ocr-", ".tmp", target.parentFile)
            val json = JSONObject().put("blocks", JSONArray().apply {
                blocks.forEach { block -> put(JSONObject().put("text",block.text).put("box",JSONArray(listOf(
                    block.box.left,block.box.top,block.box.right,block.box.bottom
                )))) }
            })
            temp.writeText(json.toString())
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: IOException) { /* Optional cache: keep translating when storage is unavailable. */ }
        catch (_: JSONException) { /* Invalid derived data is not persisted. */ }
        catch (_: SecurityException) { /* Storage access can be revoked independently. */ }
        finally { temp?.delete() }
    }
}
