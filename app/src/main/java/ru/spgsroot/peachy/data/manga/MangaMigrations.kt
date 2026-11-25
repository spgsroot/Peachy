package ru.spgsroot.peachy.data.manga

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object MangaMigrations {
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS read_history (galleryId INTEGER NOT NULL PRIMARY KEY, " +
                    "title TEXT NOT NULL, thumbnailPath TEXT NOT NULL, page INTEGER NOT NULL, " +
                    "pageCount INTEGER NOT NULL, readAt INTEGER NOT NULL)"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS search_history (query TEXT NOT NULL PRIMARY KEY, usedAt INTEGER NOT NULL)"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS tag_blacklist (kind TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(kind, value))"
            )
        }
    }

    /** Adds the table that remembers what each opened gallery looked like; nothing else is touched. */
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS gallery_memory (galleryId INTEGER NOT NULL PRIMARY KEY, " +
                    "title TEXT NOT NULL, thumbnailPath TEXT NOT NULL, numPages INTEGER NOT NULL, " +
                    "tags TEXT NOT NULL, uploadedAt INTEGER, numFavorites INTEGER, " +
                    "seenAt INTEGER NOT NULL, dismissed INTEGER NOT NULL)"
            )
        }
    }

    /** Adds the table of pages saved one by one from the reader; nothing else is touched. */
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS saved_pages (galleryId INTEGER NOT NULL, page INTEGER NOT NULL, " +
                    "title TEXT NOT NULL, fileName TEXT NOT NULL, savedAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(galleryId, page))"
            )
        }
    }

    /** Adds the cache of page translations; nothing else is touched. */
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS page_translations (galleryId INTEGER NOT NULL, page INTEGER NOT NULL, " +
                    "lang TEXT NOT NULL, model TEXT NOT NULL, json TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(galleryId, page, lang))"
            )
        }
    }

    /**
     * Adds manga work/unit/edition links, pair rejections, explicit read marks and the local edition
     * metadata index, plus gallery_memory.japaneseTitle. Only unknown-length (-1, 0) rows in
     * read_history are promoted to explicit read marks; known-length last-page rows remain progress.
     */
    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE gallery_memory ADD COLUMN japaneseTitle TEXT")
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS manga_works (" +
                    "workId TEXT NOT NULL PRIMARY KEY, " +
                    "title TEXT NOT NULL, " +
                    "updatedAt INTEGER NOT NULL)"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS manga_reading_units (" +
                    "unitId TEXT NOT NULL PRIMARY KEY, " +
                    "workId TEXT NOT NULL, " +
                    "label TEXT NOT NULL, " +
                    "sortOrder REAL NOT NULL, " +
                    "kind TEXT NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, " +
                    "FOREIGN KEY(workId) REFERENCES manga_works(workId) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_manga_reading_units_workId ON manga_reading_units (workId)"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS manga_edition_links (" +
                    "galleryId INTEGER NOT NULL PRIMARY KEY, " +
                    "workId TEXT NOT NULL, " +
                    "unitId TEXT NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, " +
                    "FOREIGN KEY(workId) REFERENCES manga_works(workId) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(unitId) REFERENCES manga_reading_units(unitId) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_manga_edition_links_workId ON manga_edition_links (workId)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS index_manga_edition_links_unitId ON manga_edition_links (unitId)"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS manga_rejected_links (" +
                    "leftGalleryId INTEGER NOT NULL, " +
                    "rightGalleryId INTEGER NOT NULL, " +
                    "createdAt INTEGER NOT NULL, " +
                    "PRIMARY KEY(leftGalleryId, rightGalleryId))"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS manga_read_marks (" +
                    "galleryId INTEGER NOT NULL PRIMARY KEY, " +
                    "markedAt INTEGER NOT NULL)"
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS manga_edition_index (" +
                    "galleryId INTEGER NOT NULL PRIMARY KEY, " +
                    "displayTitle TEXT NOT NULL, " +
                    "japaneseTitle TEXT, " +
                    "thumbnailPath TEXT NOT NULL, " +
                    "numPages INTEGER NOT NULL, " +
                    "artistIds TEXT NOT NULL, " +
                    "language TEXT, " +
                    "tagsComplete INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL)"
            )
            database.execSQL(
                "INSERT OR IGNORE INTO manga_read_marks (galleryId, markedAt) " +
                    "SELECT galleryId, readAt FROM read_history WHERE page = -1 AND pageCount = 0"
            )
        }
    }
}
