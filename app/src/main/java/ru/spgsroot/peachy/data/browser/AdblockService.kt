package ru.spgsroot.peachy.data.browser

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import ru.spgsroot.peachy.browser.adblock.BrowserFilter
import ru.spgsroot.peachy.browser.adblock.NativeFilter

@Singleton
class AdblockService internal constructor(private val context: Context, private val downloader: FilterDownload) {
    @Inject constructor(@ApplicationContext context: Context) : this(context, FilterDownload(OkHttpClient.Builder()
        .dns(ru.spgsroot.peachy.data.downloader.PublicDns()).proxy(java.net.Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(2, TimeUnit.MINUTES).build()))
    val engine = BrowserFilter()
    private val lock = Mutex()
    private val prefs = context.getSharedPreferences("browser_shield", Context.MODE_PRIVATE)
    private val root = File(context.noBackupFilesDir, "shield")
    private val resources by lazy { context.assets.open("adblock/resources.json").bufferedReader().use { it.readText() } }
    private val selected = MutableStateFlow(prefs.getStringSet("selected", null)?.toSet() ?: FilterCatalog.defaultIds)
    val subscriptions = selected.asStateFlow()
    private val privateFiles = PrivateFilterFiles(context)
    private val profileState = MutableStateFlow(PrivateFilterProfile())
    val profile = profileState.asStateFlow()
    data class Mode(val network: Boolean = true, val cosmetic: Boolean = true, val generic: Boolean = true)
    private val modeState = MutableStateFlow(Mode(prefs.getBoolean("network", true), prefs.getBoolean("cosmetic", true), prefs.getBoolean("generic", true)))
    val mode = modeState.asStateFlow()
    fun setMode(next: Mode) {
        modeState.value = next
        prefs.edit().putBoolean("network", next.network).putBoolean("cosmetic", next.cosmetic).putBoolean("generic", next.generic).apply()
        engine.clearDecisions()
    }
    private val total = MutableStateFlow(prefs.getLong("blocked", 0L))
    val blocked = total.asStateFlow()
    private val refreshFailedState = MutableStateFlow(prefs.getBoolean("refreshFailed", false))
    val refreshFailed = refreshFailedState.asStateFlow()
    private var loaded = false

    /** Called on the UI thread. No host or URL is written to preferences. */
    fun countBlock() { total.value++ }
    fun saveCount() { prefs.edit().putLong("blocked", total.value).apply() }
    private fun active() = prefs.getString("generation", null)?.let { File(root, it) }

    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        lock.withLock {
            if (loaded) return@withLock
            root.mkdirs()
            val current = active()
            root.listFiles()?.filter { it != current }?.forEach { it.deleteRecursively() }
            if (current != null && current.isDirectory) {
                val paths = current.listFiles { f -> f.name.endsWith(".txt") }.orEmpty().toList()
                val saved = privateFiles.read(current)
                val privateRules = saved.rules
                val cacheOk = privateRules.isEmpty() && runCatching { File(current, "stamp").readText() == stamp(current) }.getOrDefault(false)
                val candidate = if (cacheOk) {
                    try { engine.prepare(paths, current, resources, true) }
                    catch (_: IOException) { compile(paths, current) }
                } else compile(paths, current, privateRules)
                profileState.value = saved.profile
                engine.install(candidate)
            } else {
                val dir = newGeneration()
                try {
                    val paths = listOf("easylist", "easyprivacy", "adult").map { id ->
                        val out = File(dir, "$id.txt")
                        val stored = File(context.filesDir, "filters/$id.txt")
                        if (stored.isFile) stored.copyTo(out)
                        else context.assets.open("filters/$id.txt").use { input -> out.outputStream().use { input.copyTo(it) } }
                        out
                    }
                    publish(compile(paths, dir), dir, selected.value, 0L)
                } catch (e: Exception) { dir.deleteRecursively(); throw e }
            }
            loaded = true
        }
    }

    private fun newGeneration() = File(root, UUID.randomUUID().toString()).also {
        if (!it.mkdirs()) throw IOException("Could not stage filter generation")
    }
    private fun stamp(dir: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        for (name in listOf("engine.bin", "hosts.json")) File(dir, name).inputStream().buffered().use { input ->
            while (true) { val n = input.read(buf); if (n < 0) break; hash.update(buf, 0, n) }
        }
        return "brave-0.13.5/peachy-1:" + android.util.Base64.encodeToString(hash.digest(), android.util.Base64.NO_WRAP)
    }
    private fun compile(paths: List<File>, dir: File, privateRules: String = ""): Long {
        check(paths.isNotEmpty())
        val handle = engine.prepare(paths, dir, resources, false, privateRules)
        if (privateRules.isNotEmpty()) return handle
        try { File(dir, "stamp").writeText(stamp(dir)) }
        catch (e: Exception) { NativeFilter.close(handle); throw e }
        return handle
    }
    private fun publish(handle: Long, dir: File, ids: Set<String>, time: Long) {
        val previous = active()
        if (!prefs.edit().putString("generation", dir.name).putStringSet("selected", ids)
                .putLong("updated", time).commit()) {
            NativeFilter.close(handle)
            throw IOException("Could not activate filters")
        }
        engine.install(handle)
        selected.value = ids.toSet()
        previous?.deleteRecursively()
    }

    suspend fun update(ids: Set<String> = selected.value, ifDue: Boolean = false, custom: PrivateFilterProfile = profileState.value, edit: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        lock.withLock {
            val now = System.currentTimeMillis()
            if (ifDue && prefs.getBoolean("buildingUpdate", false)) {
                refreshFailedState.value = true
                return@withLock Result.failure(IOException("Previous compilation interrupted; manual update required"))
            }
            if (ifDue && now - prefs.getLong("attempt", 0) in 0 until TimeUnit.HOURS.toMillis(6)) return@withLock Result.success(0)
            if (ifDue && now - prefs.getLong("updated", 0) in 0 until TimeUnit.DAYS.toMillis(3)) return@withLock Result.success(0)
            prefs.edit().putLong("attempt", now).apply()
            val dir = newGeneration()
            try {
                custom.validate()
                // Offline-first baseline is retained even when optional subscriptions change.
                val wanted = ids + setOf("easylist", "easyprivacy", "adult")
                require(FilterCatalog.lists.map { it.id }.containsAll(wanted))
                var count = 0
                val old = active()
                val files = FilterCatalog.lists.filter { it.id in wanted }.map { source ->
                    File(dir, "${source.id}.txt").also { file ->
                        val existing = old?.let { File(it, file.name) }
                        if (edit && existing?.isFile == true) {
                            existing.copyTo(file)
                            count += existing.useLines { lines -> lines.count { it.isNotBlank() && !it.startsWith('!') && !it.startsWith('[') } }
                        } else count += downloader.fetch(source, file)
                    }
                }
                val downloaded = StringBuilder()
                val reusePrivate = edit && old != null && profileState.value.urls == custom.urls
                if (reusePrivate) downloaded.append(privateFiles.read(checkNotNull(old)).downloaded)
                for (url in if (reusePrivate) emptyList() else custom.urls.distinct()) {
                    count += downloader.fetch(FilterCatalog.ListSpec("custom", "", listOf(url), false), downloaded, 4L * 1024 * 1024 - downloaded.length)
                }
                val saved = PrivateFilterFiles.Saved(custom, downloaded.toString())
                privateFiles.write(dir, saved)
                if (!prefs.edit().putBoolean("buildingUpdate", true).commit()) throw IOException("Could not mark pending compilation")
                publish(compile(files, dir, saved.rules), dir, wanted, if (edit) prefs.getLong("updated", 0L) else now)
                profileState.value = custom
                prefs.edit().putBoolean("buildingUpdate", false).putBoolean("refreshFailed", false).apply()
                refreshFailedState.value = false
                Result.success(count)
            } catch (e: Exception) {
                dir.deleteRecursively()
                if (e is CancellationException) throw e
                prefs.edit().putBoolean("buildingUpdate", false).putBoolean("refreshFailed", true).apply()
                refreshFailedState.value = true
                Result.failure(e)
            }
        }
    }
}
