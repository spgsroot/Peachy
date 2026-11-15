package ru.spgsroot.peachy.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object VideoHashMigrations {
    /** Existing videos keep a null hash; it is computed the first time a same-sized file is imported. */
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE videos ADD COLUMN contentHash TEXT")
            database.execSQL("CREATE INDEX index_videos_contentHash ON videos(contentHash)")
        }
    }
}
