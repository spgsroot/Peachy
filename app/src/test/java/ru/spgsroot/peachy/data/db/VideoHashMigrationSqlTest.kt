package ru.spgsroot.peachy.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoHashMigrationSqlTest {
    @Test fun migration13To14AddsANullableHashColumnAndItsIndexWithoutTouchingRows() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            check(method.name == "execSQL") { "Unexpected database call: ${method.name}" }
            statements += args!![0] as String
            null
        } as SupportSQLiteDatabase
        val migration = VideoHashMigrations.MIGRATION_13_14
        assertEquals(13, migration.startVersion)
        assertEquals(14, migration.endVersion)
        migration.migrate(database)
        assertEquals(
            listOf(
                "ALTER TABLE videos ADD COLUMN contentHash TEXT",
                "CREATE INDEX index_videos_contentHash ON videos(contentHash)"
            ),
            statements
        )
    }
}
