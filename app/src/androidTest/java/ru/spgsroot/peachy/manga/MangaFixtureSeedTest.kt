package ru.spgsroot.peachy.manga

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.data.db.AppDatabase
import ru.spgsroot.peachy.data.db.KeystoreManager
import ru.spgsroot.peachy.data.db.LibraryGalleryEntity
import ru.spgsroot.peachy.data.db.ReadHistoryEntity
import ru.spgsroot.peachy.data.manga.ReaderPositionStore
import java.io.File

/**
 * Seeds the disposable emulator with the local fixture used by the reader-resume and
 * collapsed-recent-queries checkpoints. Test-only: no production source, schema or manifest change.
 *
 * Safety contract:
 *  - both entry points require an explicit instrumentation argument; a bare class run does nothing;
 *  - the target must be an emulator/AVD (`Build` fingerprint guard), never a physical phone;
 *  - [seed] checks every place it will write *before* the first write and aborts with the list of
 *    conflicts: existing library rows/files for the fixture ids, existing read-history rows, existing
 *    progress keys, a leftover backup from a previous seed, or any search-history row outside the
 *    `peachy-fixture-*` namespace. `SearchHistoryDao.remember` rewrites `usedAt` for every retained
 *    row, so unrelated history is never touched, not merely kept under the prune limit;
 *  - the `manga_reader` preferences are snapshotted before the change and restored exactly by [clear];
 *  - nothing outside the fixture ids/keys/namespace and that preference snapshot is written or deleted.
 *
 * What it writes once the checks pass, all through the app's real schema and APIs:
 *  - `library_galleries` rows for two synthetic galleries, so the app treats them as downloaded
 *    (the reader prefers `filesDir/manga/<id>/<page>` over the network);
 *  - the synthetic PNG pages from the test APK assets, under the exact names
 *    [MangaFiles.pageFile] derives from the stored page paths;
 *  - `read_history` rows (page 5 of 12 for the resume gallery, page 30 of 12 for the clamp gallery,
 *    both 0-based like the column);
 *  - `manga_progress` positions through [ReaderPositionStore];
 *  - `search_history` entries through the real `SearchHistoryDao.remember`;
 *  - the reader settings vertical + continuous, the only mode that stores an in-page offset.
 *
 * The fixture is non-sensitive: generated geometric pages, invented titles and query strings.
 * Nothing here contacts a provider; run the device pass with the emulator offline.
 *
 * Run:
 * `adb shell am instrument -w -e peachyMangaFixture seed \
 *    -e class ru.spgsroot.peachy.manga.MangaFixtureSeedTest#seed \
 *    ru.spgsroot.peachy.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class MangaFixtureSeedTest {

    @Test
    fun seed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        requireMode(InstrumentationRegistry.getArguments(), MODE_SEED)
        requireDisposableEmulator()

        val context = instrumentation.targetContext
        val database = AppDatabase.create(context, KeystoreManager.getOrCreateKey(context))
        try {
            val conflicts = validateCleanSlate(database, context)
            if (conflicts.isNotEmpty()) {
                throw AssertionError(
                    "fixture seed refused before any write; resolve first: " + conflicts.joinToString("; ")
                )
            }

            val backup = context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
            snapshotReaderPrefs(backup, context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE))

            val assets = instrumentation.context.assets
            copyPages(context, assets, RESUME_ID)
            copyPages(context, assets, CLAMP_ID)

            runBlocking {
                database.libraryGalleryDao().upsert(
                    LibraryGalleryEntity(
                        id = RESUME_ID,
                        title = RESUME_TITLE,
                        thumbnailPath = pagePaths().first(),
                        pagePaths = pagePaths().joinToString("\n"),
                        favorite = true,
                        addedAt = SEEDED_AT
                    )
                )
                database.libraryGalleryDao().upsert(
                    LibraryGalleryEntity(
                        id = CLAMP_ID,
                        title = CLAMP_TITLE,
                        thumbnailPath = pagePaths().first(),
                        pagePaths = pagePaths().joinToString("\n"),
                        favorite = false,
                        addedAt = SEEDED_AT + 1
                    )
                )
                database.readHistoryDao().upsert(
                    ReadHistoryEntity(
                        galleryId = RESUME_ID,
                        title = RESUME_TITLE,
                        thumbnailPath = pagePaths().first(),
                        page = RESUME_PAGE,
                        pageCount = PAGE_COUNT,
                        readAt = SEEDED_AT
                    )
                )
                // Progress that no longer fits: page index 30 of a gallery that now holds 12 pages.
                database.readHistoryDao().upsert(
                    ReadHistoryEntity(
                        galleryId = CLAMP_ID,
                        title = CLAMP_TITLE,
                        thumbnailPath = pagePaths().first(),
                        page = CLAMP_PAGE,
                        pageCount = CLAMP_PAGE,
                        readAt = SEEDED_AT + 1
                    )
                )
                for ((index, query) in QUERIES.withIndex()) {
                    database.searchHistoryDao().remember(query, SEEDED_AT + index)
                }
            }

            val positions = ReaderPositionStore(context)
            positions.save(RESUME_ID, ReaderPosition(RESUME_PAGE, RESUME_OFFSET))
            positions.save(CLAMP_ID, ReaderPosition(CLAMP_PAGE, RESUME_OFFSET))
            ReaderSettingsStore.save(
                context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE),
                ReaderSettings(orientation = ReaderOrientation.Vertical, continuous = true)
            )

            assertSeeded(database, context, positions)
        } finally {
            database.close()
        }
    }

    /** Every place [seed] writes, checked before the first write; an empty list means the slate is clean. */
    private fun validateCleanSlate(database: AppDatabase, context: Context): List<String> {
        val conflicts = mutableListOf<String>()
        val backup = context.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
        if (backup.getBoolean(MARKER_SEEDED, false)) {
            conflicts += "a fixture backup already exists ($BACKUP_PREFS); run clear before seeding again"
        }
        runBlocking {
            for (id in FIXTURE_IDS) {
                if (database.libraryGalleryDao().getById(id) != null) {
                    conflicts += "library gallery $id already exists"
                }
                if (database.readHistoryDao().getById(id) != null) {
                    conflicts += "read_history row $id already exists"
                }
            }
            val foreign = database.searchHistoryDao().getAll().map { it.query }.filterNot { it in QUERY_SET }
            if (foreign.isNotEmpty()) {
                conflicts += "${foreign.size} search-history row(s) outside the peachy-fixture namespace " +
                    "would have their usedAt rewritten by SearchHistoryDao.remember: ${foreign.take(3)}"
            }
        }
        val progress = context.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE)
        for (id in FIXTURE_IDS) {
            if (progress.contains(progressKey(id))) conflicts += "manga_progress key ${progressKey(id)} already exists"
        }
        for (id in FIXTURE_IDS) {
            val directory = File(filesDirManga(context), id.toString())
            if (directory.exists()) conflicts += "page directory $directory already exists"
        }
        val readerPrefs = context.getSharedPreferences(READER_PREFS, Context.MODE_PRIVATE)
        for ((key, value) in readerPrefs.all) {
            if (!isSnapshottable(value)) {
                conflicts += "reader preference $key has unsupported type ${value?.javaClass?.name}; refusing to guess a restore"
            }
        }
        return conflicts
    }

    private fun isSnapshottable(value: Any?): Boolean = when (value) {
        is String, is Boolean, is Int, is Long, is Float -> true
        is Set<*> -> value.all { it is String }
        else -> false
    }

    private fun snapshotReaderPrefs(backup: SharedPreferences, readerPrefs: SharedPreferences) {
        val editor = backup.edit().clear()
        val keys = mutableListOf<String>()
        for ((key, value) in readerPrefs.all) {
            val (type, encoded) = when (value) {
                is String -> "s" to value
                is Boolean -> "b" to value.toString()
                is Int -> "i" to value.toString()
                is Long -> "l" to value.toString()
                is Float -> "f" to value.toString()
                is Set<*> -> "t" to value.filterIsInstance<String>().joinToString(SET_SEPARATOR)
                else -> throw AssertionError("cannot snapshot reader preference $key of ${value?.javaClass?.name}")
            }
            editor.putString(key + TYPE_SUFFIX, type)
            editor.putString(key + VALUE_SUFFIX, encoded)
            keys += key
        }
        editor.putString(README_KEYS, keys.joinToString(SET_SEPARATOR))
        editor.putBoolean(MARKER_SEEDED, true)
        if (!editor.commit()) throw AssertionError("could not write the fixture backup")
    }

    private fun copyPages(context: Context, assets: AssetManager, galleryId: Int) {
        val directory = File(filesDirManga(context), galleryId.toString())
        directory.mkdirs()
        for (name in assetNames()) {
            val target = File(directory, name)
            assets.open("$ASSET_DIR/$name").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    private fun assertSeeded(database: AppDatabase, context: Context, positions: ReaderPositionStore) {
        runBlocking {
            val resume = database.libraryGalleryDao().getById(RESUME_ID)
            val clamp = database.libraryGalleryDao().getById(CLAMP_ID)
            assertEquals(RESUME_TITLE, resume?.title)
            assertEquals(PAGE_COUNT, resume?.pagePaths?.split('\n')?.count { it.isNotBlank() })
            assertEquals(CLAMP_TITLE, clamp?.title)
            assertEquals(PAGE_COUNT, clamp?.pagePaths?.split('\n')?.count { it.isNotBlank() })
            assertEquals(RESUME_PAGE, database.readHistoryDao().getById(RESUME_ID)?.page)
            assertEquals(CLAMP_PAGE, database.readHistoryDao().getById(CLAMP_ID)?.page)
            // Newest first, as the catalog renders the chips.
            assertEquals(QUERIES.reversed(), database.searchHistoryDao().getAll().map { it.query })
        }

        assertEquals(ReaderPosition(RESUME_PAGE, RESUME_OFFSET), positions.load(RESUME_ID))
        assertEquals(ReaderPosition(CLAMP_PAGE, RESUME_OFFSET), positions.load(CLAMP_ID))

        // The expected reader starts, computed with the product resolution used by MangaViewModel.open.
        assertEquals(
            ReaderPosition(RESUME_PAGE, RESUME_OFFSET),
            readerStart(explicitPage = null, saved = positions.load(RESUME_ID), pageCount = PAGE_COUNT)
        )
        assertEquals(
            ReaderPosition(PAGE_COUNT - 1, 0f),
            readerStart(explicitPage = null, saved = positions.load(CLAMP_ID), pageCount = PAGE_COUNT)
        )
        assertEquals(
            ReaderPosition(2, 0f),
            readerStart(explicitPage = 2, saved = positions.load(RESUME_ID), pageCount = PAGE_COUNT)
        )

        val directory = File(filesDirManga(context), RESUME_ID.toString())
        assertEquals(PAGE_COUNT, assetNames().count { name ->
            val page = File(directory, name)
            val header = ByteArray(8)
            page.length() > 0L && page.inputStream().use { it.read(header) == 8 } &&
                header.contentEquals(PNG_SIGNATURE)
        })
    }

    companion object {
        const val ASSET_DIR = "manga-fixture"
        const val FIXTURE_ARG = "peachyMangaFixture"
        const val MODE_SEED = "seed"
        const val MODE_CLEAR = "clear"
        const val READER_PREFS = "manga_reader"
        const val PROGRESS_PREFS = "manga_progress"
        const val BACKUP_PREFS = "manga_fixture_backup"
        const val MARKER_SEEDED = "fixture_seeded"
        const val README_KEYS = "reader_keys"
        const val TYPE_SUFFIX = ".type"
        const val VALUE_SUFFIX = ".value"
        const val SET_SEPARATOR = "\u0000"
        val FIXTURE_IDS = listOf(990001, 990002)
        val QUERIES = listOf(
            "peachy-fixture-alpha",
            "peachy-fixture-beta",
            "peachy-fixture-gamma",
            "peachy-fixture-delta"
        )
        val QUERY_SET = QUERIES.toSet()
        private const val RESUME_ID = 990001
        private const val CLAMP_ID = 990002
        private const val PAGE_COUNT = 12
        private const val RESUME_PAGE = 5
        private const val RESUME_OFFSET = 0.5f
        private const val CLAMP_PAGE = 30
        private const val SEEDED_AT = 1_760_000_000_000L
        private const val RESUME_TITLE = "PEACHY FIXTURE 990001 - NUMBERED STRIP"
        private const val CLAMP_TITLE = "PEACHY FIXTURE 990002 - SHRUNK CLAMP"
        private val PNG_SIGNATURE =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        internal fun filesDirManga(context: Context): File = File(context.filesDir, "manga")

        internal fun progressKey(galleryId: Int): String = "gallery_$galleryId"

        /** Explicit mode argument; a bare `am instrument` run must not touch the device. */
        internal fun requireMode(arguments: android.os.Bundle, expected: String) {
            val mode = arguments.getString(FIXTURE_ARG)
            if (mode != expected) {
                throw AssertionError(
                    "fixture refused: pass -e $FIXTURE_ARG $expected (got ${mode ?: "nothing"})"
                )
            }
        }

        /** Hard guard: this fixture must never run on a physical phone. */
        internal fun requireDisposableEmulator() {
            val identity = listOf(Build.FINGERPRINT, Build.HARDWARE, Build.PRODUCT)
                .joinToString(" ").lowercase()
            val emulator = listOf("generic", "emulator", "ranchu", "goldfish", "sdk_gphone")
                .any { identity.contains(it) }
            if (!emulator) {
                throw AssertionError(
                    "fixture refused: only a disposable emulator/AVD is allowed " +
                        "(fingerprint=${Build.FINGERPRINT}, hardware=${Build.HARDWARE}, product=${Build.PRODUCT})"
                )
            }
        }

        /** Stored page paths look like production ones; MangaFiles keeps only the last segment. */
        private fun pagePaths(): List<String> = assetNames().map { "fixture/$it" }

        private fun assetNames(): List<String> =
            (1..PAGE_COUNT).map { "${it.toString().padStart(3, '0')}.png" }
    }
}

/**
 * Removes exactly what [MangaFixtureSeedTest.seed] wrote and restores the exact reader preferences
 * that were present before the seed. Requires the same explicit instrumentation argument plus the
 * backup marker written by the seed; without it nothing is deleted.
 */
@RunWith(AndroidJUnit4::class)
class MangaFixtureClearTest {

    @Test
    fun clear() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        MangaFixtureSeedTest.requireMode(InstrumentationRegistry.getArguments(), MangaFixtureSeedTest.MODE_CLEAR)
        MangaFixtureSeedTest.requireDisposableEmulator()

        val context = instrumentation.targetContext
        val backup = context.getSharedPreferences(MangaFixtureSeedTest.BACKUP_PREFS, Context.MODE_PRIVATE)
        if (!backup.getBoolean(MangaFixtureSeedTest.MARKER_SEEDED, false)) {
            throw AssertionError(
                "fixture clear refused: no seed backup in ${MangaFixtureSeedTest.BACKUP_PREFS}; nothing is deleted"
            )
        }

        val database = AppDatabase.create(context, KeystoreManager.getOrCreateKey(context))
        try {
            runBlocking {
                // Only the fixture ids and the peachy-fixture namespace; no table-wide delete.
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM read_history WHERE galleryId IN (990001, 990002)"
                )
                database.openHelper.writableDatabase.execSQL(
                    "DELETE FROM search_history WHERE query IN " +
                        "('peachy-fixture-alpha', 'peachy-fixture-beta', 'peachy-fixture-gamma', 'peachy-fixture-delta')"
                )
                for (id in MangaFixtureSeedTest.FIXTURE_IDS) {
                    database.libraryGalleryDao().deleteById(id)
                    assertNull(database.libraryGalleryDao().getById(id))
                    assertNull(database.readHistoryDao().getById(id))
                }
                assertTrue(database.searchHistoryDao().getAll().none { it.query in MangaFixtureSeedTest.QUERY_SET })
            }
            for (id in MangaFixtureSeedTest.FIXTURE_IDS) {
                File(MangaFixtureSeedTest.filesDirManga(context), id.toString()).deleteRecursively()
            }
            val progress = context.getSharedPreferences(MangaFixtureSeedTest.PROGRESS_PREFS, Context.MODE_PRIVATE)
            val progressEditor = progress.edit()
            for (id in MangaFixtureSeedTest.FIXTURE_IDS) {
                progressEditor.remove(MangaFixtureSeedTest.progressKey(id))
            }
            if (!progressEditor.commit()) throw AssertionError("could not remove the fixture progress keys")

            val readerPrefs = context.getSharedPreferences(MangaFixtureSeedTest.READER_PREFS, Context.MODE_PRIVATE)
            restoreReaderPrefs(backup, readerPrefs)
            if (!backup.edit().clear().commit()) throw AssertionError("could not remove the fixture backup")
        } finally {
            database.close()
        }
    }

    /** Restores every snapshotted key and verifies the preference map is byte-for-byte the old set. */
    private fun restoreReaderPrefs(backup: SharedPreferences, readerPrefs: SharedPreferences) {
        val keys = backup.getString(MangaFixtureSeedTest.README_KEYS, null)
            ?.split(MangaFixtureSeedTest.SET_SEPARATOR)
            ?.filter { it.isNotEmpty() }
            ?: throw AssertionError("fixture backup is missing its reader key list")
        val expected = linkedMapOf<String, Any>()
        val editor = readerPrefs.edit().clear()
        for (key in keys) {
            val type = backup.getString(key + MangaFixtureSeedTest.TYPE_SUFFIX, null)
                ?: throw AssertionError("fixture backup is missing type for $key")
            val encoded = backup.getString(key + MangaFixtureSeedTest.VALUE_SUFFIX, null)
            when (type) {
                "s" -> {
                    editor.putString(key, encoded)
                    expected[key] = encoded ?: ""
                }
                "b" -> {
                    val value = encoded?.toBooleanStrict() ?: throw AssertionError("bad boolean $key")
                    editor.putBoolean(key, value)
                    expected[key] = value
                }
                "i" -> {
                    val value = encoded?.toIntOrNull() ?: throw AssertionError("bad int $key")
                    editor.putInt(key, value)
                    expected[key] = value
                }
                "l" -> {
                    val value = encoded?.toLongOrNull() ?: throw AssertionError("bad long $key")
                    editor.putLong(key, value)
                    expected[key] = value
                }
                "f" -> {
                    val value = encoded?.toFloatOrNull() ?: throw AssertionError("bad float $key")
                    editor.putFloat(key, value)
                    expected[key] = value
                }
                "t" -> {
                    val value = encoded.orEmpty().split(MangaFixtureSeedTest.SET_SEPARATOR)
                        .filter { it.isNotEmpty() }.toSet()
                    editor.putStringSet(key, value)
                    expected[key] = value
                }
                else -> throw AssertionError("fixture backup has unknown type $type for $key")
            }
        }
        if (!editor.commit()) throw AssertionError("could not restore the reader preferences")
        assertEquals(expected, readerPrefs.all)
    }
}
