package ru.spgsroot.peachy.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MangaIdentityDao {
    @Query("SELECT * FROM manga_works")
    fun observeWorks(): Flow<List<MangaWorkEntity>>

    @Query("SELECT * FROM manga_works")
    suspend fun getAllWorks(): List<MangaWorkEntity>

    @Query("SELECT * FROM manga_works WHERE workId = :workId")
    suspend fun getWork(workId: String): MangaWorkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWork(work: MangaWorkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorks(works: List<MangaWorkEntity>)

    @Query("DELETE FROM manga_works WHERE workId = :workId")
    suspend fun deleteWork(workId: String)

    @Query("SELECT * FROM manga_reading_units")
    fun observeUnits(): Flow<List<MangaReadingUnitEntity>>

    @Query("SELECT * FROM manga_reading_units")
    suspend fun getAllUnits(): List<MangaReadingUnitEntity>

    @Query("SELECT * FROM manga_reading_units WHERE unitId = :unitId")
    suspend fun getUnit(unitId: String): MangaReadingUnitEntity?

    @Query("SELECT COUNT(*) FROM manga_reading_units WHERE workId = :workId")
    suspend fun countUnitsInWork(workId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUnit(unit: MangaReadingUnitEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUnits(units: List<MangaReadingUnitEntity>)

    @Query("DELETE FROM manga_reading_units WHERE unitId = :unitId")
    suspend fun deleteUnit(unitId: String)

    @Query("SELECT * FROM manga_edition_links")
    fun observeLinks(): Flow<List<MangaEditionLinkEntity>>

    @Query("SELECT * FROM manga_edition_links")
    suspend fun getAllLinks(): List<MangaEditionLinkEntity>

    @Query("SELECT * FROM manga_edition_links WHERE galleryId = :galleryId")
    suspend fun getLink(galleryId: Int): MangaEditionLinkEntity?

    @Query("SELECT * FROM manga_edition_links WHERE unitId = :unitId")
    suspend fun getLinksForUnit(unitId: String): List<MangaEditionLinkEntity>

    @Query("SELECT COUNT(*) FROM manga_edition_links WHERE unitId = :unitId")
    suspend fun countLinksInUnit(unitId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLink(link: MangaEditionLinkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLinks(links: List<MangaEditionLinkEntity>)

    @Query("DELETE FROM manga_edition_links WHERE galleryId = :galleryId")
    suspend fun deleteLink(galleryId: Int)

    @Query("SELECT * FROM manga_rejected_links")
    fun observeRejections(): Flow<List<MangaRejectedLinkEntity>>

    @Query("SELECT * FROM manga_rejected_links")
    suspend fun getAllRejections(): List<MangaRejectedLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRejection(rejection: MangaRejectedLinkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRejections(rejections: List<MangaRejectedLinkEntity>)

    @Query("DELETE FROM manga_rejected_links WHERE leftGalleryId = :left AND rightGalleryId = :right")
    suspend fun deleteRejection(left: Int, right: Int)

    @Query("SELECT * FROM manga_read_marks")
    fun observeReadMarks(): Flow<List<MangaReadMarkEntity>>

    @Query("SELECT * FROM manga_read_marks")
    suspend fun getAllReadMarks(): List<MangaReadMarkEntity>

    @Query("SELECT * FROM manga_read_marks WHERE galleryId = :galleryId")
    suspend fun getReadMark(galleryId: Int): MangaReadMarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadMark(mark: MangaReadMarkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReadMarks(marks: List<MangaReadMarkEntity>)

    @Query("DELETE FROM manga_read_marks WHERE galleryId = :galleryId")
    suspend fun deleteReadMark(galleryId: Int)

    @Query("SELECT * FROM manga_edition_index")
    fun observeEditions(): Flow<List<MangaEditionIndexEntity>>

    @Query("SELECT * FROM manga_edition_index")
    suspend fun getAllEditions(): List<MangaEditionIndexEntity>

    @Query("SELECT * FROM manga_edition_index WHERE galleryId = :galleryId")
    suspend fun getEdition(galleryId: Int): MangaEditionIndexEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEdition(edition: MangaEditionIndexEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEditions(editions: List<MangaEditionIndexEntity>)
}
