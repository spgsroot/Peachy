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

const val OCR_PIPELINE_VERSION = 3
const val DETECTOR_MODEL_TAG = "dc6b6341"

interface OcrCache {
    suspend fun get(galleryId: Int, page: Int, source: String): PageScan?
    suspend fun put(galleryId: Int, page: Int, source: String, scan: PageScan)
}

/**
 * Derived local data only; a miss never prevents recognition.
 * Files: {gallery}/{page}-{source}-v3-{tag}.json. Blocks store source text bounds and,
 * when known, a separate safe balloon interior; detector regions store bounds and score.
 * Invalid geometry, aspect or scores are misses. A successful atomic replacement precedes
 * removal of older versions/tags for the same page and source; unrelated entries stay intact.
 */
class FileOcrCache(private val dir: File, private val tag: String = DETECTOR_MODEL_TAG) : OcrCache {
    private fun entry(galleryId: Int, page: Int, source: String): File =
        File(File(dir, galleryId.toString()), "$page-$source-v$OCR_PIPELINE_VERSION-$tag.json")

    override suspend fun get(galleryId: Int, page: Int, source: String): PageScan? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(entry(galleryId, page, source).readText())
            val aspect = json.getDouble("aspect").toFloat()
            if (!aspect.isFinite() || aspect <= 0f) throw JSONException("Invalid aspect")
            val blocks = json.getJSONArray("blocks")
            val regions = json.getJSONArray("regions")
            PageScan(
                List(blocks.length()) { index ->
                    val block = blocks.getJSONObject(index)
                    val text = block.get("text") as? String ?: throw JSONException("Invalid text")
                    val box = readBox(block.getJSONArray("box"))
                    val interior = if (block.has("interior")) readBox(block.getJSONArray("interior")) else null
                    if (interior != null && (interior.left > box.left || interior.top > box.top ||
                            interior.right < box.right || interior.bottom < box.bottom)) throw JSONException("Invalid interior")
                    OcrBlock(text, box, interior)
                },
                List(regions.length()) { index ->
                    val region = regions.getJSONObject(index)
                    val score = region.getDouble("score")
                    if (!score.isFinite() || score !in 0.0..1.0) throw JSONException("Invalid score")
                    Region(readBox(region.getJSONArray("box")), score.toFloat())
                },
                aspect
            )
        } catch (_: IOException) { null }
        catch (_: JSONException) { null }
        catch (_: SecurityException) { null }
    }

    override suspend fun put(galleryId: Int, page: Int, source: String, scan: PageScan): Unit = withContext(Dispatchers.IO) {
        var temp: File? = null
        try {
            val target = entry(galleryId, page, source)
            target.parentFile!!.mkdirs()
            // Unique same-directory temp + atomic replacement: readers see all-old or all-new JSON.
            // A crash leaves at most an ignored .tmp; malformed existing JSON is a cache miss.
            temp = File.createTempFile("ocr-", ".tmp", target.parentFile)
            val json = JSONObject().put("aspect", scan.aspect).put("blocks", JSONArray().apply {
                scan.blocks.forEach { block ->
                    val item = JSONObject().put("text", block.text).put("box", boxJson(block.box))
                    block.interior?.let { item.put("interior", boxJson(it)) }
                    put(item)
                }
            }).put("regions", JSONArray().apply {
                scan.regions.forEach { region -> put(JSONObject().put("box", boxJson(region.box)).put("score", region.score)) }
            })
            temp.writeText(json.toString())
            Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            val generation = Regex("${Regex.escape("$page-$source")}-v[0-9]+(?:-.+)?\\.json")
            target.parentFile!!.listFiles()?.forEach { file ->
                if (file.name != target.name && generation.matches(file.name) && file.isFile) file.delete()
            }
        } catch (_: IOException) { /* Optional cache: keep translating when storage is unavailable. */ }
        catch (_: JSONException) { /* Invalid derived data is not persisted. */ }
        catch (_: SecurityException) { /* Storage access can be revoked independently. */ }
        finally { temp?.delete() }
    }

    private fun readBox(json: JSONArray): Box {
        if (json.length() != 4) throw JSONException("Invalid box")
        val l = json.getDouble(0); val t = json.getDouble(1)
        val r = json.getDouble(2); val b = json.getDouble(3)
        if (!(l >= 0.0 && t >= 0.0 && r <= 1.0 && b <= 1.0 && l < r && t < b)) throw JSONException("Invalid box")
        val box = Box(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
        if (box.left >= box.right || box.top >= box.bottom) throw JSONException("Invalid box")
        return box
    }

    private fun boxJson(box: Box): JSONArray = JSONArray(listOf(box.left, box.top, box.right, box.bottom))
}
