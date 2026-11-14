package ru.spgsroot.peachy.translate

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONException
import org.json.JSONObject

/** Disposable cache only: no credentials, preferences, Room or vault data. */
class ModelSpeedCache(private val cacheDir: File) {
    fun get(baseUrl: String, model: String): ModelSpeed? = try {
        val value = JSONObject(file(baseUrl, model).readText())
        val first = value.getLong("firstTokenMs")
        val total = value.getLong("totalMs")
        val at = value.getLong("measuredAt")
        if (first < 0 || total < first || at <= 0) null else ModelSpeed(first, total, at)
    } catch (_: IOException) { null } catch (_: JSONException) { null }

    fun put(baseUrl: String, model: String, speed: ModelSpeed) {
        try {
            val target = file(baseUrl, model)
            target.parentFile?.mkdirs()
            target.writeText(JSONObject().put("firstTokenMs", speed.firstTokenMs)
                .put("totalMs", speed.totalMs).put("measuredAt", speed.measuredAt).toString())
        } catch (_: IOException) { /* Cache eviction or full disk must not hide a successful probe. */ }
    }

    private fun file(baseUrl: String, model: String): File {
        val key = (ProviderUrl.host(baseUrl).orEmpty() + "\u0000" + model).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
        return File(File(cacheDir, "provider-model-speed"), "$digest.json")
    }
}
