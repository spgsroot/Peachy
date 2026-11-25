package ru.spgsroot.peachy.data.manga

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.data.db.AppDatabase
import ru.spgsroot.peachy.data.db.GalleryMemoryEntity
import ru.spgsroot.peachy.data.db.ReadHistoryEntity
import ru.spgsroot.peachy.data.storage.InternalStorageManager
import ru.spgsroot.peachy.data.storage.PhotoStorageManager
import ru.spgsroot.peachy.data.vault.VaultArchiveReader
import ru.spgsroot.peachy.data.vault.VaultArchiveWriter
import ru.spgsroot.peachy.data.vault.VaultCrypto
import ru.spgsroot.peachy.data.vault.VaultData
import ru.spgsroot.peachy.data.vault.VaultManager
import ru.spgsroot.peachy.manga.EditionReadStatus
import ru.spgsroot.peachy.manga.GalleryDetail
import ru.spgsroot.peachy.manga.GallerySummary
import ru.spgsroot.peachy.manga.GalleryTag
import ru.spgsroot.peachy.manga.MangaIdentityProjector
import ru.spgsroot.peachy.manga.UnitKind
import ru.spgsroot.peachy.nsfw.NsfwGate
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class MangaIdentityMigrationAndVaultTest {
    private lateinit var root: File
    private lateinit var isolatedContext: Context
    private val dbPassphrase = "qa-sqlcipher-passphrase-16-to-17".toByteArray(Charsets.UTF_8)
    private val vaultPassword = "qa-vault-password".toCharArray()

    @Before
    fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(base.cacheDir, "manga-identity-mig-${System.nanoTime()}").apply { mkdirs() }
        isolatedContext = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(root, name)
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun createEncryptedV16Database(seed: (SupportSQLiteDatabase) -> Unit) {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(dbPassphrase)
        val helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(isolatedContext)
                .name(AppDatabase.DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createV16Schema(db)
                        seed(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { db ->
            assertEquals(16, db.version)
        }
        helper.close()
    }

    private fun createV16Schema(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `albums` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `videos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `filePath` TEXT NOT NULL, `duration` INTEGER NOT NULL, `thumbnailPath` TEXT, `addedAt` INTEGER NOT NULL, `fileSize` INTEGER NOT NULL, `isCorrupted` INTEGER NOT NULL, `albumId` INTEGER, `contentHash` TEXT, FOREIGN KEY(`albumId`) REFERENCES `albums`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_albumId` ON `videos` (`albumId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_videos_contentHash` ON `videos` (`contentHash`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `settings` (`id` INTEGER NOT NULL, `timerMs` INTEGER NOT NULL, `playbackSpeed` REAL NOT NULL, `dspLowFreq` INTEGER NOT NULL, `dspHighFreq` INTEGER NOT NULL, `dspSmoothingAlpha` REAL NOT NULL, `dspPowerBoost` REAL NOT NULL, `dspThreshold` REAL NOT NULL, `autoLockTimeoutMs` INTEGER NOT NULL, `languageCode` TEXT NOT NULL, `nsfwEnabled` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `playback_state` (`id` INTEGER NOT NULL, `currentVideoId` INTEGER, `positionMs` INTEGER NOT NULL, `playlistOrder` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `library_galleries` (`id` INTEGER NOT NULL, `title` TEXT NOT NULL, `thumbnailPath` TEXT NOT NULL, `pagePaths` TEXT NOT NULL, `favorite` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `read_history` (`galleryId` INTEGER NOT NULL, `title` TEXT NOT NULL, `thumbnailPath` TEXT NOT NULL, `page` INTEGER NOT NULL, `pageCount` INTEGER NOT NULL, `readAt` INTEGER NOT NULL, PRIMARY KEY(`galleryId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `search_history` (`query` TEXT NOT NULL, `usedAt` INTEGER NOT NULL, PRIMARY KEY(`query`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `tag_blacklist` (`kind` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`kind`, `value`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `gallery_memory` (`galleryId` INTEGER NOT NULL, `title` TEXT NOT NULL, `thumbnailPath` TEXT NOT NULL, `numPages` INTEGER NOT NULL, `tags` TEXT NOT NULL, `uploadedAt` INTEGER, `numFavorites` INTEGER, `seenAt` INTEGER NOT NULL, `dismissed` INTEGER NOT NULL, PRIMARY KEY(`galleryId`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `bookmarks` (`url` TEXT NOT NULL, `title` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`url`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `browser_history` (`url` TEXT NOT NULL, `title` TEXT NOT NULL, `visitedAt` INTEGER NOT NULL, PRIMARY KEY(`url`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `adblock_allowlist` (`host` TEXT NOT NULL, PRIMARY KEY(`host`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `notes` (`kind` TEXT NOT NULL, `ref` TEXT NOT NULL, `text` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`kind`, `ref`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `saved_pages` (`galleryId` INTEGER NOT NULL, `page` INTEGER NOT NULL, `title` TEXT NOT NULL, `fileName` TEXT NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`galleryId`, `page`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `page_translations` (`galleryId` INTEGER NOT NULL, `page` INTEGER NOT NULL, `lang` TEXT NOT NULL, `model` TEXT NOT NULL, `json` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`galleryId`, `page`, `lang`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `photos` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `filePath` TEXT NOT NULL, `thumbnailPath` TEXT, `addedAt` INTEGER NOT NULL, `fileSize` INTEGER NOT NULL, `width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `albumId` INTEGER, FOREIGN KEY(`albumId`) REFERENCES `albums`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_photos_albumId` ON `photos` (`albumId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `document_sessions` (`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, `sourceMime` TEXT, `format` TEXT NOT NULL, `kind` TEXT NOT NULL, `workingPath` TEXT NOT NULL, `dirty` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastPage` INTEGER, `lastScroll` INTEGER, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '855082e78078728f86fc3bd490d00ee4')")
    }

    private fun vaultManagerFor(db: AppDatabase, ctx: Context = isolatedContext): VaultManager = VaultManager(
        ctx, db, db.videoDao(), db.libraryGalleryDao(), db.readHistoryDao(),
        db.searchHistoryDao(), db.tagBlacklistDao(), db.galleryMemoryDao(), db.noteDao(),
        db.bookmarkDao(), db.browserHistoryDao(), db.allowlistDao(), TagPreferences(ctx),
        db.pageTranslationDao(), InternalStorageManager(ctx), MangaFiles(ctx), PhotoStorageManager(ctx)
    )

    @Test
    fun realSqlCipherV16ToV17MigrationPreservesAllDataAndSelectivelyPromotesReadMarks() = runBlocking {
        createEncryptedV16Database { db ->
            db.execSQL("INSERT INTO albums VALUES(1, 'Synth Album', 500)")
            db.execSQL("INSERT INTO videos VALUES(7, 'Synth Video', '/v.mp4', 1200, NULL, 501, 42, 0, 1, 'hash7')")
            db.execSQL("INSERT INTO settings VALUES(1, 0, 1.0, 20, 200, 0.3, 1.0, 0.03, 0, 'ru', 1)")
            db.execSQL("INSERT INTO library_galleries VALUES(101, 'Star Map RU', 't/101.jpg', '1.jpg\n2.jpg', 1, 600)")
            db.execSQL("INSERT INTO gallery_memory VALUES(101, 'Star Map RU', 't/101.jpg', 20, '[]', 700, 12, 800, 0)")
            db.execSQL("INSERT INTO saved_pages VALUES(101, 2, 'Star Map RU', 'p2.jpg', 810)")
            db.execSQL("INSERT INTO page_translations VALUES(101, 2, 'ru', 'm', '{\"sourceLanguage\":\"ja\",\"lines\":[]}', 820)")
            // Explicit mark-read row (-1, 0) MUST become an explicit read mark.
            db.execSQL("INSERT INTO read_history VALUES(101, 'Star Map RU', 't/101.jpg', -1, 0, 1000)")
            // Known-length last-page row (11/12) MUST stay local progress only, NOT an explicit read mark.
            db.execSQL("INSERT INTO read_history VALUES(102, 'Star Map EN', 't/102.jpg', 11, 12, 2000)")
            // Mid-read progress row (3/20) MUST NOT become an explicit read mark.
            db.execSQL("INSERT INTO read_history VALUES(103, 'Chapter 2', 't/103.jpg', 3, 20, 3000)")
        }

        val migrated = AppDatabase.create(isolatedContext, dbPassphrase.copyOf())
        try {
            // Existing data preserved verbatim.
            assertEquals("Synth Video", migrated.videoDao().getAllNow().single().title)
            assertEquals("Synth Album", migrated.albumDao().getAllNow().single().name)
            assertEquals(101, migrated.libraryGalleryDao().getAll().single().id)
            assertEquals(1, migrated.savedPageDao().observeAll().first().size)
            assertEquals(1, migrated.pageTranslationDao().getAll().size)
            val memory = migrated.galleryMemoryDao().getAll().single()
            assertEquals(101, memory.galleryId)
            assertNull(memory.japaneseTitle)
            assertEquals(3, migrated.readHistoryDao().getAll().size)

            // Selective read-mark backfill.
            val readMarks = migrated.mangaIdentityDao().getAllReadMarks()
            assertEquals(1, readMarks.size)
            assertEquals(101, readMarks.single().galleryId)
            assertEquals(1000L, readMarks.single().markedAt)

            // Foreign keys clean.
            migrated.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                assertFalse("Foreign key violation after v16->v17 migration", cursor.moveToFirst())
            }

            // Write new identity records and verify persistence across close + reopen.
            val gate = NsfwGate().apply { publish(true) }
            val store = MangaIdentityStore(migrated, gate)
            store.confirmSameChapter(101, 102, now = 4000L)
            store.rejectSuggestion(101, 999, now = 4001L)
        } finally {
            migrated.close()
        }

        val reopened = AppDatabase.create(isolatedContext, dbPassphrase.copyOf())
        try {
            val snapshot = MangaIdentityStore(reopened, NsfwGate().apply { publish(true) }).snapshot.first()
            assertEquals(1, snapshot.works.size)
            assertEquals(1, snapshot.units.size)
            assertEquals(setOf(101, 102), snapshot.links.keys)
            assertEquals(EditionReadStatus.ReadOwn(1000L), MangaIdentityProjector.readStatus(101, snapshot))
            assertTrue(MangaIdentityProjector.readStatus(102, snapshot) is EditionReadStatus.ReadInOtherTranslation)
            assertEquals(setOf(101 to 999), snapshot.rejections)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun encryptedVaultExportImportIsIdempotentAndAcceptsLegacyFormatThree() = runBlocking {
        val gate = NsfwGate().apply { publish(true) }
        val sourceDb = AppDatabase.create(isolatedContext, dbPassphrase.copyOf())
        val archiveFile = File(root, "identity-v4.peachy")
        try {
            val store = MangaIdentityStore(sourceDb, gate)
            sourceDb.galleryMemoryDao().upsert(
                GalleryMemoryEntity(
                    galleryId = 101, title = "Star Map RU", thumbnailPath = "t/101.jpg", numPages = 20,
                    tags = "[]", uploadedAt = 10L, numFavorites = 5, seenAt = 20L, dismissed = false,
                    japaneseTitle = "星図 第1話"
                )
            )
            store.indexSummaries(
                listOf(
                    GallerySummary(101, "Star Map RU", "t/101.jpg", 20, japaneseTitle = "星図 第1話"),
                    GallerySummary(102, "Star Map EN", "t/102.jpg", 20, japaneseTitle = "星図 第1話 [英訳]"),
                    GallerySummary(103, "Star Map 1.5", "t/103.jpg", 14, japaneseTitle = "星図 第1.5話"),
                    GallerySummary(200, "Star Map Omnibus", "t/200.jpg", 80, japaneseTitle = "星図 合本"),
                    GallerySummary(404, "Passive Only", "t/404.jpg", 12, japaneseTitle = "検索のみ")
                ),
                now = 100L
            )
            store.setReadMark(101, read = true, now = 200L)
            val ch1Link = store.confirmSameChapter(102, 101, defaultUnitLabel = "1", now = 210L)!!
            store.linkAsAnotherChapter(103, ch1Link.workId, null, null, "1.5", 1.5, UnitKind.Chapter, now = 220L)
            store.linkAsAnotherChapter(200, ch1Link.workId, null, null, "Сборник 1-2", 1.0, UnitKind.Collection, now = 230L)
            store.rejectSuggestion(101, 303, now = 240L)

            vaultManagerFor(sourceDb).export(Uri.fromFile(archiveFile), vaultPassword)
        } finally {
            sourceDb.close()
        }

        // Verify exported JSON contains ONLY own read marks (not inherited 102) and NO passive index (404).
        archiveFile.inputStream().use { raw ->
            val reader = VaultArchiveReader(VaultCrypto.decrypt(BufferedInputStream(raw), vaultPassword))
            val doc = reader.next()!!
            val jsonText = String(doc.readBytes(1024 * 1024), Charsets.UTF_8)
            val rootJson = JSONObject(jsonText)
            assertEquals(4, rootJson.getInt("format"))
            val identityJson = rootJson.getJSONObject("mangaIdentity")
            val exportedMarks = identityJson.getJSONArray("readMarks")
            assertEquals(1, exportedMarks.length())
            assertEquals(101, exportedMarks.getJSONObject(0).getInt("galleryId"))
            assertFalse("Passive search index must not be exported", jsonText.contains("Passive Only"))
        }

        // Restore into a brand-new encrypted DB and repeat import to prove idempotency.
        val targetRoot = File(root, "target-db").apply { mkdirs() }
        val targetContext = object : ContextWrapper(isolatedContext) {
            override fun getFilesDir(): File = targetRoot
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(targetRoot, name)
        }
        val targetDb = AppDatabase.create(targetContext, dbPassphrase.copyOf())
        try {
            val targetVault = vaultManagerFor(targetDb, targetContext)
            targetVault.import(Uri.fromFile(archiveFile), vaultPassword)
            targetVault.import(Uri.fromFile(archiveFile), vaultPassword) // repeated import must be idempotent

            val dao = targetDb.mangaIdentityDao()
            assertEquals(1, dao.getAllWorks().size)
            assertEquals(3, dao.getAllUnits().size) // ch 1, ch 1.5, collection — zero duplicates
            assertEquals(4, dao.getAllLinks().size)
            assertEquals(1, dao.getAllRejections().size)
            assertEquals(1, dao.getAllReadMarks().size)
            assertEquals("星図 第1話", targetDb.galleryMemoryDao().getAll().single().japaneseTitle)

            val restoredSnapshot = MangaIdentityStore(targetDb, gate).snapshot.first()
            val workId = restoredSnapshot.works.keys.single()
            val units = MangaIdentityProjector.workUnits(workId, restoredSnapshot)
            assertEquals(listOf("1", "1.5", "Сборник 1-2"), units.map { it.unit.label })
            assertEquals(1.5, units[1].unit.sortOrder, 0.0001)
            assertEquals(UnitKind.Collection, units[2].unit.kind)
            assertTrue(MangaIdentityProjector.readStatus(102, restoredSnapshot) is EditionReadStatus.ReadInOtherTranslation)
            assertEquals(EditionReadStatus.Unread, MangaIdentityProjector.readStatus(103, restoredSnapshot))
        } finally {
            targetDb.close()
        }

        // Legacy format 3 archive without mangaIdentity or japaneseTitle imports compatibly and promotes (-1, 0) marks only.
        val legacyFile = File(root, "legacy-v3.peachy")
        val legacyData = VaultData(
            entryCount = 0, videos = emptyList(), galleries = emptyList(),
            readHistory = listOf(
                ReadHistoryEntity(501, "Legacy Marked", "t/501.jpg", -1, 0, 900L),
                ReadHistoryEntity(502, "Legacy Last Page", "t/502.jpg", 11, 12, 950L)
            ),
            searches = emptyList(), blacklist = emptyList(),
            memory = listOf(GalleryMemoryEntity(501, "Legacy Marked", "t/501.jpg", 10, "[]", null, null, 800L, false)),
            notes = emptyList(), bookmarks = emptyList(), browserHistory = emptyList(),
            allowlist = emptyList(), tagLibrary = null
        )
        val v3Json = JSONObject(legacyData.toJson()).apply {
            put("format", 3)
            remove("mangaIdentity")
            getJSONArray("memory").getJSONObject(0).remove("japaneseTitle")
        }.toString()
        ByteArrayOutputStream().use { out ->
            VaultArchiveWriter(VaultCrypto.encrypt(out, vaultPassword, VaultCrypto.MIN_ITERATIONS)).use { writer ->
                writer.add(VaultManager.DATA_ENTRY, v3Json.toByteArray(Charsets.UTF_8))
            }
            legacyFile.writeBytes(out.toByteArray())
        }

        val legacyRoot = File(root, "legacy-db").apply { mkdirs() }
        val legacyContext = object : ContextWrapper(isolatedContext) {
            override fun getFilesDir(): File = legacyRoot
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(legacyRoot, name)
        }
        val legacyDb = AppDatabase.create(legacyContext, dbPassphrase.copyOf())
        try {
            vaultManagerFor(legacyDb, legacyContext).import(Uri.fromFile(legacyFile), vaultPassword)
            val marks = legacyDb.mangaIdentityDao().getAllReadMarks()
            assertEquals(1, marks.size)
            assertEquals(501, marks.single().galleryId)
            assertEquals(2, legacyDb.readHistoryDao().getAll().size)
        } finally {
            legacyDb.close()
        }
    }

    @Test
    fun nsfwGateBlocksPassiveIndexingAndIdentityWritesWhenDisabled() = runBlocking {
        val db = AppDatabase.create(isolatedContext, dbPassphrase.copyOf())
        try {
            val gate = NsfwGate() // null -> closed
            val store = MangaIdentityStore(db, gate)
            val summary = GallerySummary(
                id = 701, title = "Blocked", thumbnailPath = "t.jpg", numPages = 10,
                tags = listOf(GalleryTag(17, "artist", "alice"), GalleryTag(1, "language", "english")),
                japaneseTitle = "星図"
            )
            val detail = GalleryDetail(702, "Blocked 2", "t2.jpg", listOf("1.jpg"), japaneseTitle = "星図")

            store.indexSummaries(listOf(summary))
            store.indexDetail(detail)
            store.setReadMark(701, true)
            store.confirmSameChapter(701, 702)
            store.rejectSuggestion(701, 702)

            assertTrue(db.mangaIdentityDao().getAllEditions().isEmpty())
            assertTrue(db.mangaIdentityDao().getAllReadMarks().isEmpty())
            assertTrue(db.mangaIdentityDao().getAllLinks().isEmpty())
            assertTrue(db.mangaIdentityDao().getAllRejections().isEmpty())

            gate.publish(false)
            store.indexSummaries(listOf(summary))
            store.setReadMark(701, true)
            assertTrue(db.mangaIdentityDao().getAllEditions().isEmpty())
            assertTrue(db.mangaIdentityDao().getAllReadMarks().isEmpty())

            gate.publish(true)
            store.indexSummaries(listOf(summary))
            store.setReadMark(701, true)
            assertNotNull(db.mangaIdentityDao().getEdition(701))
            assertEquals(1, db.mangaIdentityDao().getAllReadMarks().size)
        } finally {
            db.close()
        }
    }
}
