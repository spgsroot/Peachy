package ru.spgsroot.peachy.data.manga

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.data.db.AppDatabase
import ru.spgsroot.peachy.data.db.GalleryMemoryEntity
import ru.spgsroot.peachy.data.db.ReadHistoryEntity
import ru.spgsroot.peachy.manga.GallerySummary
import ru.spgsroot.peachy.manga.GalleryTag
import ru.spgsroot.peachy.manga.TagCodec
import ru.spgsroot.peachy.manga.toRemembered

@RunWith(AndroidJUnit4::class)
class GalleryMemoryStoreTest {
    private lateinit var database: AppDatabase
    private lateinit var store: GalleryMemoryStore
    private val tags = listOf(GalleryTag(7, "tag", "adventure", 50))

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java
        ).build()
        store = GalleryMemoryStore(database)
    }

    @After fun close() { database.close() }

    @Test fun completionPreservesDetailedMetadataAndClearsDismissal() = runBlocking {
        val known = remembered(dismissed = true)
        database.galleryMemoryDao().upsert(known)
        database.readHistoryDao().upsert(ReadHistoryEntity(1, "Old", "old.jpg", 2, 9, 10))
        val sparse = GallerySummary(1, "Catalog", "catalog.jpg", 2)

        store.markRead(sparse, now = 100)

        assertEquals(known.copy(dismissed = false), database.galleryMemoryDao().getById(1))
        val expected = ReadHistoryEntity(1, known.title, known.thumbnailPath, 8, 9, 100)
        assertEquals(expected, database.readHistoryDao().getById(1))
        assertEquals(1.0, expected.readFraction, 0.0)
        assertEquals(tags, GalleryMemoryStore(database).all.first().single().tags)

        store.markRead(sparse, now = 200)
        assertEquals(listOf(expected.copy(readAt = 200)), database.readHistoryDao().getAll())
    }

    @Test fun newSummaryPersistsItsTagsAndActualPageCount() = runBlocking {
        val summary = GallerySummary(1, "New", "new.jpg", 4, tags = tags, numFavorites = 12)

        store.markRead(summary, now = 100)

        assertEquals(summary.toRemembered(100), GalleryMemoryStore(database).all.first().single())
        assertEquals(ReadHistoryEntity(1, "New", "new.jpg", 3, 4, 100), database.readHistoryDao().getById(1))
    }

    @Test fun previousHistorySuppliesMetadataWhenMemoryAndCatalogHaveNone() = runBlocking {
        database.readHistoryDao().upsert(ReadHistoryEntity(1, "Known", "known.jpg", 4, 13, 10))

        store.markRead(GallerySummary(1, "", "", 0), now = 100)

        assertEquals(ReadHistoryEntity(1, "Known", "known.jpg", 12, 13, 100), database.readHistoryDao().getById(1))
        val memory = database.galleryMemoryDao().getById(1)!!
        assertEquals("Known", memory.title)
        assertEquals("known.jpg", memory.thumbnailPath)
        assertEquals(13, memory.numPages)
    }

    @Test fun unknownCountsCompleteWithoutInventingPageMetadata() = runBlocking {
        for ((id, count) in listOf(1 to 0, 2 to -3)) {
            store.markRead(GallerySummary(id, "Unknown", "thumb.jpg", count, tags = tags), now = 100)
            val history = database.readHistoryDao().getById(id)!!
            assertEquals(-1, history.page)
            assertEquals(0, history.pageCount)
            assertEquals(1.0, history.readFraction, 0.0)
            assertEquals(0, database.galleryMemoryDao().getById(id)!!.numPages)
        }
    }

    @Test fun failedHistoryWriteRollsBackRememberedMetadataAndDismissal() = runBlocking {
        val known = remembered(dismissed = true)
        val history = ReadHistoryEntity(1, known.title, known.thumbnailPath, 2, 9, 10)
        database.galleryMemoryDao().upsert(known)
        database.readHistoryDao().upsert(history)
        withContext(Dispatchers.IO) {
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_completion BEFORE INSERT ON read_history " +
                    "BEGIN SELECT RAISE(ABORT, 'reject completion'); END"
            )
        }

        try {
            store.markRead(GallerySummary(1, "Catalog", "catalog.jpg", 9), now = 100)
            fail("The rejected history write must fail the completion operation")
        } catch (_: SQLiteException) {
            // The memory write precedes history in the same transaction and must roll back too.
        }

        assertEquals(known, database.galleryMemoryDao().getById(1))
        assertEquals(history, database.readHistoryDao().getById(1))
    }

    @Test fun dismissingAKnownGalleryUsesFeedbackTimeWithoutLosingMetadata() = runBlocking {
        val known = remembered(dismissed = false)
        database.galleryMemoryDao().upsert(known)

        store.dismiss(GallerySummary(1, "Sparse", "", 0), now = 100)

        assertEquals(
            known.copy(dismissed = true, seenAt = 100),
            database.galleryMemoryDao().getById(1)
        )
    }

    private fun remembered(dismissed: Boolean) = GalleryMemoryEntity(
        galleryId = 1,
        title = "Detailed title",
        thumbnailPath = "detail.jpg",
        numPages = 9,
        tags = TagCodec.encode(tags),
        uploadedAt = 123,
        numFavorites = 42,
        seenAt = 5,
        dismissed = dismissed
    )
}
