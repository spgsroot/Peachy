package ru.spgsroot.peachy.data.browser

import android.content.Context
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import ru.spgsroot.peachy.data.downloader.PublicNetworkPolicy

data class PrivateFilterProfile(val rules: String = "", val urls: List<String> = emptyList()) {
    internal fun validate() {
        if (rules.length > 128 * 1024 || urls.size > 8) throw IOException("Custom profile too large")
        urls.forEach { url ->
            val parsed = PublicNetworkPolicy.url(url)
            if (url.length > 2048 || parsed.fragment != null) throw IOException("Invalid subscription URL")
        }
    }
}

/** Authenticated profile and expanded subscription bodies live in the same generation as the public lists. */
internal class PrivateFilterFiles(private val context: Context) {
    data class Saved(val profile: PrivateFilterProfile, val downloaded: String) {
        val rules get() = if (downloaded.isEmpty()) profile.rules else profile.rules + "\n" + downloaded
    }
    private val key by lazy { MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }
    private fun encrypted(file: File) = EncryptedFile.Builder(context, file, key, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()
    fun read(dir: File): Saved {
        val file = File(dir, "profile.enc")
        if (!file.exists()) return Saved(PrivateFilterProfile(), "")
        val json = encrypted(file).openFileInput().bufferedReader().use { JSONObject(it.readText()) }
        val urls = json.getJSONArray("urls")
        return Saved(PrivateFilterProfile(json.getString("rules"), List(urls.length()) { urls.getString(it) }), json.getString("downloaded"))
    }
    fun write(dir: File, saved: Saved) {
        if (saved.profile == PrivateFilterProfile()) return
        val json = JSONObject().put("rules", saved.profile.rules).put("urls", JSONArray(saved.profile.urls)).put("downloaded", saved.downloaded)
        encrypted(File(dir, "profile.enc")).openFileOutput().bufferedWriter().use { it.write(json.toString()) }
    }
}
