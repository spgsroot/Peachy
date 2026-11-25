package ru.spgsroot.peachy.data.vault

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultDataTest {
    private fun empty(translations: List<VaultTranslation> = emptyList()) = VaultData(
        entryCount = 0, videos = emptyList(), galleries = emptyList(), readHistory = emptyList(),
        searches = emptyList(), blacklist = emptyList(), memory = emptyList(), notes = emptyList(),
        bookmarks = emptyList(), browserHistory = emptyList(), allowlist = emptyList(), tagLibrary = null,
        translations = translations
    )

    @Test
    fun olderVaultWithoutTranslationsReadsAsEmpty() {
        val older = JSONObject(empty().toJson()).apply { remove("translations") }.toString()
        assertTrue(VaultData.fromJson(older).translations.isEmpty())
    }

    @Test
    fun translationsRoundTrip() {
        val data = empty(
            listOf(
                VaultTranslation(7, 0, "ru", "glm-5.3-flash", "{\"sourceLanguage\":\"ja\",\"lines\":[]}", 100),
                VaultTranslation(7, 12, "en", "m \"x\"", "{\"a\":\"line\\nbreak\"}", 5)
            )
        )
        assertEquals(data, VaultData.fromJson(data.toJson()))
    }

    @Test
    fun aTranslationMissingAFieldMakesTheVaultCorrupt() {
        val broken = JSONObject(empty().toJson())
            .put("translations", org.json.JSONArray().put(JSONObject().put("galleryId", 1).put("page", 0)))
            .toString()
        val error = org.junit.Assert.assertThrows(VaultException::class.java) { VaultData.fromJson(broken) }.error
        assertEquals(VaultError.CORRUPT, error)
    }

    @Test
    fun photosRoundTripWithDimensionsAndSeparateEntryNames() {
        val data = empty().copy(
            entryCount = 3,
            photos = listOf(
                VaultPhoto(7, "Фото \"holiday\"\n", 123, 45, 1200, 800, "jpg", true),
                VaultPhoto(9, "No thumbnail", 124, 89, 800, 1200, "webp", false)
            )
        )
        assertEquals(data, VaultData.fromJson(data.toJson()))
        assertEquals("photo/7.jpg", data.photos.first().entryName)
        assertEquals("photo_thumb/7", data.photos.first().thumbnailEntryName)
    }

    @Test
    fun legacyVersionOneWithoutPhotosOrTranslationsIsAccepted() {
        val legacy = JSONObject(empty().toJson()).apply {
            put("format", 1)
            remove("photos")
            remove("translations")
        }
        val data = VaultData.fromJson(legacy.toString())
        assertTrue(data.photos.isEmpty())
        assertTrue(data.translations.isEmpty())
    }

    @Test
    fun versionTwoWithoutPhotosIsAccepted() {
        val raw = JSONObject(empty().toJson()).apply { put("format", 2); remove("photos"); remove("albums") }
        assertTrue(VaultData.fromJson(raw.toString()).photos.isEmpty())
    }

    @Test
    fun malformedPhotoMetadataIsCorruptBeforeAnyFilesAreRestored() {
        val valid = JSONObject(empty().copy(
            entryCount = 1, photos = listOf(VaultPhoto(7, "photo", 1, 4, 20, 10, "png", false))
        ).toJson())
        val changes = listOf<Pair<String, Any>>(
            "id" to 0L, "id" to -1L, "fileSize" to -1L, "fileSize" to 0L,
            "width" to 0, "height" to -1, "extension" to "../jpg",
            "extension" to "jpg/escape", "extension" to "a\\b", "extension" to "",
            "extension" to "C:jpg", "extension" to "jpg\n"
        )
        for ((key, value) in changes) {
            val broken = JSONObject(valid.toString())
            broken.getJSONArray("photos").getJSONObject(0).put(key, value)
            val error = org.junit.Assert.assertThrows("$key=$value", VaultException::class.java) {
                VaultData.fromJson(broken.toString())
            }
            assertEquals(VaultError.CORRUPT, error.error)
        }
        valid.getJSONArray("photos").getJSONObject(0).remove("width")
        assertEquals(VaultError.CORRUPT, org.junit.Assert.assertThrows(VaultException::class.java) {
            VaultData.fromJson(valid.toString())
        }.error)
    }

    @Test
    fun repeatedPhotoIdsAreCorrupt() {
        val photo = VaultPhoto(7, "photo", 1, 4, 20, 10, "png", false)
        val data = empty().copy(entryCount = 2, photos = listOf(photo, photo.copy(title = "other")))
        assertEquals(VaultError.CORRUPT, org.junit.Assert.assertThrows(VaultException::class.java) {
            VaultData.fromJson(data.toJson())
        }.error)
    }

    @Test
    fun legacyProgressTotalRemainsOnlyAHint() {
        val raw = JSONObject(empty().toJson()).put("format", 1).put("entryCount", -1).apply { remove("photos") }
        val restored = VaultData.fromJson(raw.toString())
        assertEquals(-1, restored.entryCount)
        assertTrue(restored.photos.isEmpty())
    }

    @Test fun albumsAndMembershipSurviveRoundTrip() {
        val data = empty().copy(
            albums = listOf(ru.spgsroot.peachy.data.db.AlbumEntity(7, "Photo and video", 123)),
            photos = listOf(VaultPhoto(9, "photo", 1, 4, 20, 10, "png", false, 7)),
            videos = listOf(VaultVideo(9, "video", 4, 1, 8, false, "mp4", false, 7))
        )
        assertEquals(data, VaultData.fromJson(data.toJson()))
        val orphan = data.copy(albums = emptyList())
        assertEquals(VaultError.CORRUPT, org.junit.Assert.assertThrows(VaultException::class.java) {
            VaultData.fromJson(orphan.toJson())
        }.error)
    }

    @Test
    fun mangaIdentityAndJapaneseTitleRoundTripAndLegacyFormatThreeIsAccepted() {
        val identity = VaultMangaIdentity(
            works = listOf(ru.spgsroot.peachy.data.db.MangaWorkEntity("w-1", "Atlas", 100L)),
            units = listOf(
                ru.spgsroot.peachy.data.db.MangaReadingUnitEntity("u-1", "w-1", "1.5", 1.5, "chapter", 101L),
                ru.spgsroot.peachy.data.db.MangaReadingUnitEntity("u-col", "w-1", "Omnibus", 1.0, "collection", 102L)
            ),
            links = listOf(ru.spgsroot.peachy.data.db.MangaEditionLinkEntity(101, "w-1", "u-1", 103L)),
            rejections = listOf(ru.spgsroot.peachy.data.db.MangaRejectedLinkEntity(101, 202, 104L)),
            readMarks = listOf(ru.spgsroot.peachy.data.db.MangaReadMarkEntity(101, 105L))
        )
        val data = empty().copy(
            memory = listOf(
                ru.spgsroot.peachy.data.db.GalleryMemoryEntity(
                    101, "Star Map", "t.jpg", 24, "[]", 10L, 5, 20L, false, "星図 第1.5話"
                )
            ),
            mangaIdentity = identity
        )
        assertEquals(data, VaultData.fromJson(data.toJson()))

        val legacyV3 = JSONObject(data.toJson()).apply {
            put("format", 3)
            remove("mangaIdentity")
            getJSONArray("memory").getJSONObject(0).remove("japaneseTitle")
        }
        val parsedV3 = VaultData.fromJson(legacyV3.toString())
        assertEquals(3, parsedV3.sourceFormat)
        assertEquals(VaultMangaIdentity(), parsedV3.mangaIdentity)
        assertEquals(null, parsedV3.memory.single().japaneseTitle)

        // Broken link pointing to wrong workId must be rejected as corrupt.
        val badLink = data.copy(
            mangaIdentity = identity.copy(
                links = listOf(ru.spgsroot.peachy.data.db.MangaEditionLinkEntity(101, "missing-work", "u-1", 103L))
            )
        )
        assertEquals(VaultError.CORRUPT, org.junit.Assert.assertThrows(VaultException::class.java) {
            VaultData.fromJson(badLink.toJson())
        }.error)

        // Non-canonical rejection pair (left >= right) must be rejected as corrupt.
        val badRejection = data.copy(
            mangaIdentity = identity.copy(
                rejections = listOf(ru.spgsroot.peachy.data.db.MangaRejectedLinkEntity(202, 101, 104L))
            )
        )
        assertEquals(VaultError.CORRUPT, org.junit.Assert.assertThrows(VaultException::class.java) {
            VaultData.fromJson(badRejection.toJson())
        }.error)
    }
}
