package ru.spgsroot.peachy.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMigrationSqlTest {
    @Test fun migration15To16AddsNsfwSwitchDefaultOff() {
        val statements = mutableListOf<String>()
        val database = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            check(method.name == "execSQL") { "Unexpected database call: ${method.name}" }
            statements += args!![0] as String
            null
        } as SupportSQLiteDatabase
        val migration = AppDatabase.MIGRATION_15_16
        assertEquals(15, migration.startVersion)
        assertEquals(16, migration.endVersion)
        migration.migrate(database)
        assertEquals(
            listOf("ALTER TABLE settings ADD COLUMN nsfwEnabled INTEGER NOT NULL DEFAULT 0"),
            statements
        )
    }
}
