package ru.spgsroot.peachy.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DocumentMigrations {
    /** Adds the document session index. No document content is stored here. */
    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS document_sessions (" +
                    "id TEXT NOT NULL PRIMARY KEY, " +
                    "displayName TEXT NOT NULL, " +
                    "sourceMime TEXT, " +
                    "format TEXT NOT NULL, " +
                    "kind TEXT NOT NULL, " +
                    "workingPath TEXT NOT NULL, " +
                    "dirty INTEGER NOT NULL, " +
                    "createdAt INTEGER NOT NULL, " +
                    "updatedAt INTEGER NOT NULL, " +
                    "lastPage INTEGER, " +
                    "lastScroll INTEGER)"
            )
        }
    }
}
