package ru.spgsroot.peachy.ui.manga

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.data.db.AppDatabase
import ru.spgsroot.peachy.data.db.ReadHistoryEntity
import ru.spgsroot.peachy.data.manga.MangaIdentityStore
import ru.spgsroot.peachy.manga.EditionReadStatus
import ru.spgsroot.peachy.manga.GalleryDetail
import ru.spgsroot.peachy.manga.GallerySummary
import ru.spgsroot.peachy.manga.GalleryTag
import ru.spgsroot.peachy.manga.MangaIdentityProjector
import ru.spgsroot.peachy.manga.MangaIdentitySnapshot
import ru.spgsroot.peachy.manga.NhentaiCdn
import ru.spgsroot.peachy.manga.ReaderPosition
import ru.spgsroot.peachy.manga.UnitKind
import ru.spgsroot.peachy.manga.readerStart
import ru.spgsroot.peachy.manga.toEditionMetadata
import ru.spgsroot.peachy.nsfw.NsfwGate
import ru.spgsroot.peachy.ui.theme.PeachyTheme
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class MangaIdentityEndToEndTest {
    @get:Rule
    val compose = createComposeRule()

    private lateinit var root: File
    private lateinit var isolatedContext: Context
    private val passphrase = "qa-e2e-sqlcipher-passphrase".toByteArray(Charsets.UTF_8)
    private var openDb: AppDatabase? = null

    @Before
    fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(base.cacheDir, "manga-identity-e2e-${System.nanoTime()}").apply { mkdirs() }
        isolatedContext = object : ContextWrapper(base) {
            override fun getFilesDir(): File = root
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(root, name)
        }
    }

    @After
    fun tearDown() {
        openDb?.close()
        openDb = null
        root.deleteRecursively()
    }

    private fun openStore(): Pair<AppDatabase, MangaIdentityStore> {
        openDb?.close()
        val db = AppDatabase.create(isolatedContext, passphrase.copyOf())
        openDb = db
        val gate = NsfwGate().apply { publish(true) }
        return db to MangaIdentityStore(db, gate)
    }

    private fun russianContext(): Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag("ru")) }
        return base.createConfigurationContext(config)
    }

    private fun setRussianContent(
        widthDp: Int = 360,
        fontScale: Float = 1.0f,
        content: @Composable () -> Unit
    ): Context {
        val context = russianContext()
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(baseDensity.density, fontScale = fontScale)
            ) {
                PeachyTheme {
                    Surface(Modifier.requiredSize(widthDp.dp, 720.dp)) {
                        content()
                    }
                }
            }
        }
        return context
    }

    private val artistTag = GalleryTag(17, "artist", "hoshino")
    private val ruTag = GalleryTag(1, "language", "russian")
    private val enTag = GalleryTag(2, "language", "english")

    private val ruSummary = GallerySummary(
        id = 101,
        title = "Звёздная карта — Глава 1",
        thumbnailPath = "t/101.jpg",
        numPages = 20,
        tags = listOf(artistTag, ruTag),
        japaneseTitle = "星図 第1話",
        englishTitle = "Star Map Ch. 1"
    )
    private val enSummary = GallerySummary(
        id = 102,
        title = "Star Map Ch. 1",
        thumbnailPath = "t/102.jpg",
        numPages = 20,
        tags = listOf(artistTag, enTag),
        japaneseTitle = "星図 第1話 [英訳]",
        englishTitle = "Star Map Ch. 1"
    )
    private val ch15Summary = GallerySummary(
        id = 103,
        title = "Star Map Ch. 1.5",
        thumbnailPath = "t/103.jpg",
        numPages = 12,
        tags = listOf(artistTag, enTag),
        japaneseTitle = "星図 第1.5話",
        englishTitle = "Star Map Ch. 1.5"
    )
    private val vol2Ch1Summary = GallerySummary(
        id = 104,
        title = "Star Map Vol. 2 Ch. 1",
        thumbnailPath = "t/104.jpg",
        numPages = 24,
        tags = listOf(artistTag, enTag),
        japaneseTitle = "星図 2巻 第1話",
        englishTitle = "Star Map Vol. 2 Ch. 1"
    )
    private val collectionSummary = GallerySummary(
        id = 200,
        title = "Star Map Omnibus",
        thumbnailPath = "t/200.jpg",
        numPages = 90,
        tags = listOf(artistTag, enTag),
        japaneseTitle = "星図 合本",
        englishTitle = "Star Map Omnibus"
    )

    @Test
    fun autoGroupExpandsWithoutImplicitReadPropagationAndRejectPersistsAcrossRestart() {
        val (_, store) = openStore()
        runBlocking {
            store.indexSummaries(listOf(ruSummary, enSummary))
            store.setReadMark(ruSummary.id, read = true, now = 100L)
        }

        var mounted by mutableStateOf(true)
        val ctx = setRussianContent {
            if (!mounted) return@setRussianContent
            val snapshot by store.snapshot.collectAsState(initial = MangaIdentitySnapshot())
            val scope = rememberCoroutineScope()
            val groups = remember(snapshot) {
                MangaIdentityProjector.projectList(
                    items = listOf(ruSummary, enSummary),
                    idOf = { it.id },
                    metadataOf = { it.toEditionMetadata() },
                    preferredLanguages = listOf("russian", "english"),
                    snapshot = snapshot
                )
            }
            var expandedKeys by remember { mutableStateOf(emptySet<String>()) }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                groups.forEach { group ->
                    val expanded = group.key in expandedKeys
                    ProjectedGroupFooter(
                        group = group,
                        idOf = { it.id },
                        titleOf = { it.title },
                        languageOf = { s -> s.tags.firstOrNull { it.type == "language" }?.name },
                        snapshot = snapshot,
                        expanded = expanded,
                        onToggleExpand = {
                            expandedKeys = if (expanded) expandedKeys - group.key else expandedKeys + group.key
                        },
                        onOpenEdition = {},
                        onConfirmSame = { a, b -> scope.launch(Dispatchers.IO) { store.confirmSameChapter(a, b) } },
                        onReject = { a, b -> scope.launch(Dispatchers.IO) { store.rejectSuggestion(a, b) } }
                    )
                }
            }
        }

        // Auto-group collapses RU + EN into 1 group with "2 вер." and "Автогруппа".
        val versionsText = ctx.getString(R.string.manga_identity_versions_count, 2)
        val readOwnText = ctx.getString(R.string.manga_identity_read_own)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(readOwnText).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(versionsText).assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.manga_identity_autogroup_badge)).assertIsDisplayed()
        compose.onNodeWithText(readOwnText).assertIsDisplayed()

        // Expand auto-group: EN is visible and MUST NOT have inherited read status.
        compose.onNodeWithText(versionsText).performClick()
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText(ctx.getString(R.string.manga_identity_read_inherited)).fetchSemanticsNodes().size)
        compose.onNodeWithText(ctx.getString(R.string.manga_identity_action_reject)).assertIsDisplayed().performClick()

        // Wait for DB rejection to split the group.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(versionsText).fetchSemanticsNodes().isEmpty()
        }

        // Unmount UI collectors before closing DB to simulate a clean process restart.
        mounted = false
        compose.waitForIdle()

        // Simulate app restart by closing and reopening the encrypted SQLCipher DB.
        val (_, restartedStore) = openStore()
        val persisted = runBlocking { restartedStore.snapshot.first() }
        assertTrue((101 to 102) in persisted.rejections)
        val afterRestart = MangaIdentityProjector.projectList(
            items = listOf(ruSummary, enSummary),
            idOf = { it.id },
            metadataOf = { it.toEditionMetadata() },
            preferredLanguages = listOf("russian", "english"),
            snapshot = persisted
        )
        assertEquals(2, afterRestart.size)
        assertEquals(EditionReadStatus.Unread, MangaIdentityProjector.readStatus(102, persisted))
    }

    @Test
    fun fullFlowRuReadConfirmEnOwnPageAnotherChapterUnreadUnlinkAndRestart() {
        val (db, store) = openStore()
        runBlocking {
            store.indexSummaries(listOf(ruSummary, enSummary, ch15Summary, vol2Ch1Summary, collectionSummary))
            // RU was read to page 16 (index 16) and explicitly marked read; EN is on its own page 3 (index 3).
            db.readHistoryDao().upsert(ReadHistoryEntity(101, ruSummary.title, ruSummary.thumbnailPath, page = 16, pageCount = 20, readAt = 100L))
            db.readHistoryDao().upsert(ReadHistoryEntity(102, enSummary.title, enSummary.thumbnailPath, page = 3, pageCount = 20, readAt = 110L))
            store.setReadMark(101, read = true, now = 120L)
        }

        val enDetail = GalleryDetail(
            id = 102,
            title = enSummary.title,
            thumbnailPath = enSummary.thumbnailPath,
            pagePaths = List(20) { "$it.jpg" },
            tags = enSummary.tags,
            japaneseTitle = enSummary.japaneseTitle
        )

        var mounted by mutableStateOf(true)
        val ctx = setRussianContent {
            if (!mounted) return@setRussianContent
            val snapshot by store.snapshot.collectAsState(initial = MangaIdentitySnapshot())
            val historyList by db.readHistoryDao().observeAll().collectAsState(initial = emptyList())
            val historyById = remember(historyList) { historyList.associateBy { it.galleryId } }
            val scope = rememberCoroutineScope()

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GalleryDetailActions(
                    detail = enDetail,
                    savedPage = historyById[102]?.page,
                    saved = false,
                    favorite = false,
                    progress = null,
                    onRead = {},
                    onDownload = {},
                    onDeleteDownload = {},
                    onFavorite = {},
                    hasNote = false,
                    onNote = {}
                )
                GalleryIdentitySection(
                    galleryId = 102,
                    liveMetadata = enDetail.toEditionMetadata(),
                    snapshot = snapshot,
                    historyByGallery = historyById,
                    onOpenGallery = {},
                    onSetReadMark = { read -> scope.launch(Dispatchers.IO) { store.setReadMark(102, read) } },
                    onConfirmSameChapter = { target -> scope.launch(Dispatchers.IO) { store.confirmSameChapter(102, target) } },
                    onLinkAsTranslationOfUnit = { workId, unitId ->
                        scope.launch(Dispatchers.IO) { store.linkAsTranslationOfUnit(102, workId, unitId) }
                    },
                    onLinkAsAnotherChapter = { workId, anchorId, title, label, order, kind ->
                        scope.launch(Dispatchers.IO) {
                            store.linkAsAnotherChapter(102, workId, anchorId, title, label, order, kind)
                        }
                    },
                    onUnlink = { scope.launch(Dispatchers.IO) { store.unlink(102) } },
                    onRejectSuggestion = { peer -> scope.launch(Dispatchers.IO) { store.rejectSuggestion(102, peer) } }
                )
            }
        }

        // Management stays out of the reading hierarchy until explicitly disclosed.
        val continuePage4 = ctx.getString(R.string.manga_detail_continue, 4)
        val inheritedLabel = ctx.getString(R.string.manga_identity_read_inherited)
        val confirmButtonText = ctx.getString(R.string.manga_identity_action_confirm_same)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(continuePage4).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(continuePage4).assertIsDisplayed()
        compose.onNodeWithText(confirmButtonText).assertDoesNotExist()
        compose.onNodeWithText(ctx.getString(R.string.manga_identity_action_unlink)).assertDoesNotExist()
        assertEquals(0, compose.onAllNodesWithText(inheritedLabel).fetchSemanticsNodes().size)
        compose.onNodeWithText(ctx.getString(R.string.manga_identity_section_title)).performScrollTo().performClick()
        // Confirm EN as same chapter as RU via the UI button "Та же глава".
        compose.onNodeWithText(confirmButtonText).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(inheritedLabel).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(inheritedLabel).performScrollTo().assertIsDisplayed()

        // EN keeps its own page (index 3 -> "Продолжить со страницы 4") and readerStart resolves to page 3 (never RU's 16).
        compose.onNodeWithText(continuePage4).performScrollTo().assertIsDisplayed()
        runBlocking {
            val enHistory = db.readHistoryDao().getById(102)!!
            assertEquals(3, enHistory.page)
            assertEquals(ReaderPosition(3, 0f), readerStart(null, ReaderPosition(enHistory.page, 0f), 20))
            assertEquals(setOf(101, 102), db.readHistoryDao().getAll().map { it.galleryId }.toSet())
        }

        // Add chapter 1.5, collection, and vol 2 ch 1 to the same work; verify known-chapters counter & collection isolation.
        runBlocking {
            val workId = store.snapshot.first().links.getValue(102).workId
            store.linkAsAnotherChapter(103, workId, null, null, "1.5", 1.5, UnitKind.Chapter)
            store.linkAsAnotherChapter(200, workId, null, null, "Сборник 1-2", 1.0, UnitKind.Collection)
            store.setReadMark(200, read = true) // reading collection MUST NOT mark chapter 1.5 read
        }
        val counter1of2 = ctx.getString(R.string.manga_identity_known_chapters, 1, 2)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(counter1of2).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(counter1of2).performScrollTo().assertIsDisplayed()
        runBlocking {
            val snap = store.snapshot.first()
            assertEquals(EditionReadStatus.Unread, MangaIdentityProjector.readStatus(103, snap))
            // Adding a third chapter updates counter from 1/2 to 1/3.
            val workId = snap.links.getValue(102).workId
            store.linkAsAnotherChapter(104, workId, null, null, "Том 2 / Глава 1", 2.0, UnitKind.Chapter)
        }
        val counter1of3 = ctx.getString(R.string.manga_identity_known_chapters, 1, 3)
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(counter1of3).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(counter1of3).performScrollTo().assertIsDisplayed()

        // Explicit EN read marks are independent of inherited status and never change either page.
        compose.onNodeWithText(ctx.getString(R.string.manga_identity_mark_read)).performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(ctx.getString(R.string.manga_identity_unmark_read)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(3, runBlocking { db.readHistoryDao().getById(102)!!.page })
        compose.onNodeWithText(ctx.getString(R.string.manga_identity_unmark_read)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(inheritedLabel).fetchSemanticsNodes().isNotEmpty()
        }

        // Unlink EN via UI button "Отвязать": inherited read status disappears immediately, RU stays read, EN keeps page 3.
        compose.onNodeWithText(ctx.getString(R.string.manga_identity_action_unlink)).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(inheritedLabel).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText(continuePage4).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(ctx.getString(R.string.manga_identity_management_close)).performScrollTo().performClick()
        compose.onNodeWithText(ctx.getString(R.string.manga_identity_action_same_chapter)).assertDoesNotExist()
        compose.onNodeWithText(ctx.getString(R.string.manga_identity_section_title))
            .assert(hasStateDescription(ctx.getString(R.string.manga_identity_management_expand)))

        // Unmount UI collectors before closing DB to simulate a clean process restart.
        mounted = false
        compose.waitForIdle()

        // Restart: close SQLCipher DB and reopen from disk; verify full persisted state.
        val (reopenedDb, restartedStore) = openStore()
        runBlocking {
            val snap = restartedStore.snapshot.first()
            assertEquals(EditionReadStatus.ReadOwn(120L), MangaIdentityProjector.readStatus(101, snap))
            assertEquals(EditionReadStatus.Unread, MangaIdentityProjector.readStatus(102, snap))
            assertEquals(EditionReadStatus.Unread, MangaIdentityProjector.readStatus(103, snap))
            assertEquals(EditionReadStatus.Unread, MangaIdentityProjector.readStatus(104, snap))
            assertTrue(MangaIdentityProjector.readStatus(200, snap) is EditionReadStatus.ReadOwn)
            assertEquals(3, reopenedDb.readHistoryDao().getById(102)!!.page)
            assertEquals(16, reopenedDb.readHistoryDao().getById(101)!!.page)

            val workId = snap.links.getValue(101).workId
            val progress = MangaIdentityProjector.workProgress(workId, snap)!!
            assertEquals(1, progress.readChapters)
            assertEquals(3, progress.knownChapters)
            assertEquals(1, progress.collectionCount)
            val units = MangaIdentityProjector.workUnits(workId, snap)
            assertEquals(listOf(1.0, 1.5, 2.0), units.filter { it.unit.kind == UnitKind.Chapter }.map { it.unit.sortOrder })
        }
    }

    @Test
    fun compact320dpAnd13xFontScaleRendersIdentityActionsWith48dpTargets() {
        val detail = GalleryDetail(
            id = 102, title = enSummary.title, thumbnailPath = enSummary.thumbnailPath,
            pagePaths = List(20) { "$it.jpg" }, tags = enSummary.tags, japaneseTitle = enSummary.japaneseTitle
        )
        val ctx = setRussianContent(widthDp = 320, fontScale = 1.3f) {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
                GalleryIdentitySection(
                    galleryId = 102,
                    liveMetadata = detail.toEditionMetadata(),
                    snapshot = MangaIdentitySnapshot(),
                    historyByGallery = emptyMap(),
                    onOpenGallery = {},
                    onSetReadMark = {},
                    onConfirmSameChapter = {},
                    onLinkAsTranslationOfUnit = { _, _ -> },
                    onLinkAsAnotherChapter = { _, _, _, _, _, _ -> },
                    onUnlink = {},
                    onRejectSuggestion = {}
                )
            }
        }
        val density = ctx.resources.displayMetrics.density
        val disclosure = compose.onNodeWithText(ctx.getString(R.string.manga_identity_section_title))
        val disclosureBounds = disclosure.fetchSemanticsNode().touchBoundsInRoot
        assertTrue("Disclosure target height must be >= 48dp", disclosureBounds.height >= 48f * density - 1f)
        compose.onNodeWithText(ctx.getString(R.string.manga_identity_action_same_chapter)).assertDoesNotExist()
        compose.onNodeWithText(ctx.getString(R.string.manga_identity_section_title)).performScrollTo().performClick()
        for (resId in listOf(
            R.string.manga_identity_mark_read,
            R.string.manga_identity_action_same_chapter,
            R.string.manga_identity_action_another_chapter,
            R.string.manga_identity_management_close
        )) {
            val label = ctx.getString(resId)
            val node = compose.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().touchBoundsInRoot
            assertTrue("Touch target height must be >= 48dp", bounds.height >= 48f * density - 1f)
            assertTrue("Touch target width must be >= 48dp", bounds.width >= 48f * density - 1f)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1))
            repeat(layout.lineCount) { line ->
                assertTrue("Ellipsized action: $label", !layout.isLineEllipsized(line))
                assertTrue("Clipped action: $label", layout.getLineRight(line) <= layout.size.width + 1f)
            }
        }
        compose.onNodeWithText(ctx.getString(R.string.manga_identity_management_close)).performScrollTo().performClick()
        compose.onNodeWithText(ctx.getString(R.string.manga_identity_action_same_chapter)).assertDoesNotExist()
    }

    @Test
    fun detailRejectLeavesIndependentProgressAndReadMarksUntouched() {
        val (db, store) = openStore()
        runBlocking {
            store.indexSummaries(listOf(ruSummary, enSummary))
            store.setReadMark(101, read = true, now = 100L)
            db.readHistoryDao().upsert(ReadHistoryEntity(
                102, enSummary.title, enSummary.thumbnailPath, page = 3, pageCount = 20, readAt = 1L
            ))
        }
        var opened: Int? = null
        val context = setRussianContent {
            val snapshot by store.snapshot.collectAsState(initial = MangaIdentitySnapshot())
            val scope = rememberCoroutineScope()
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                GalleryIdentitySection(
                    102, enSummary.toEditionMetadata(), snapshot, emptyMap(),
                    onOpenGallery = { opened = it }, onSetReadMark = {},
                    onConfirmSameChapter = {}, onLinkAsTranslationOfUnit = { _, _ -> },
                    onLinkAsAnotherChapter = { _, _, _, _, _, _ -> }, onUnlink = {},
                    onRejectSuggestion = { peer -> scope.launch(Dispatchers.IO) { store.rejectSuggestion(102, peer) } }
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.manga_identity_section_title)).performClick()
        val reject = context.getString(R.string.manga_identity_action_reject)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(reject).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(ruSummary.title, substring = true) and hasClickAction())
            .performScrollTo().performClick()
        compose.runOnIdle { assertEquals(101, opened) }
        compose.onNodeWithText(reject).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(reject).fetchSemanticsNodes().isEmpty() }
        runBlocking {
            val snapshot = store.snapshot.first()
            assertTrue((101 to 102) in snapshot.rejections)
            assertEquals(EditionReadStatus.ReadOwn(100L), MangaIdentityProjector.readStatus(101, snapshot))
            assertEquals(EditionReadStatus.Unread, MangaIdentityProjector.readStatus(102, snapshot))
            assertEquals(3, db.readHistoryDao().getById(102)!!.page)
            assertTrue(snapshot.links.isEmpty())
        }
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    fun disclosureKeyboardDraftAndLegacyStatusSurviveRestoreAndClose() {
        val context = russianContext()
        val restoration = StateRestorationTester(compose)
        val metadata = enSummary.toEditionMetadata()
        val marked = mutableListOf<Boolean>()
        lateinit var inputMode: InputModeManager
        restoration.setContent {
            inputMode = LocalInputModeManager.current
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration
            ) {
                PeachyTheme {
                    Surface {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            GalleryIdentitySection(
                                galleryId = 102, liveMetadata = metadata, snapshot = MangaIdentitySnapshot(),
                                historyByGallery = mapOf(102 to ReadHistoryEntity(
                                    102, enSummary.title, enSummary.thumbnailPath, page = 19, pageCount = 20, readAt = 1L
                                )),
                                onOpenGallery = {}, onSetReadMark = { marked += it },
                                onConfirmSameChapter = {}, onLinkAsTranslationOfUnit = { _, _ -> },
                                onLinkAsAnotherChapter = { _, _, _, _, _, _ -> },
                                onUnlink = {}, onRejectSuggestion = {}
                            )
                        }
                    }
                }
            }
        }
        val section = context.getString(R.string.manga_identity_section_title)
        val sameChapter = context.getString(R.string.manga_identity_action_same_chapter)
        val legacy = context.getString(R.string.manga_identity_legacy_last_page)
        compose.onNodeWithText(legacy).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.manga_identity_read_own)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.manga_identity_mark_read)).performClick()
        compose.runOnIdle { assertEquals(listOf(true), marked) }
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        compose.onNodeWithText(section).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(section).assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.onNodeWithText(sameChapter).assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText(sameChapter).assertDoesNotExist()
        compose.onNodeWithText(section).performClick()
        compose.onNodeWithText(sameChapter).performScrollTo().performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("999")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("999").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText(context.getString(R.string.manga_identity_management_close))
            .performScrollTo().performClick()
        compose.onNodeWithText(sameChapter).assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(sameChapter).assertDoesNotExist()
        compose.onNodeWithText(legacy).assertIsDisplayed()
    }

    @Test
    fun detailCardHierarchyVisualProof() {
        val (db, store) = openStore()
        val title = "Kimi ni Naru — Interlude 1"
        val english = enSummary.copy(title = title, englishTitle = title, japaneseTitle = "君になる 幕間 1")
        val russian = ruSummary.copy(
            title = "$title (RU)", englishTitle = title, japaneseTitle = english.japaneseTitle
        )
        val nextChapter = ch15Summary.copy(
            title = "Kimi ni Naru — Interlude 1.5",
            englishTitle = "Kimi ni Naru — Interlude 1.5",
            japaneseTitle = "君になる 幕間 1.5"
        )
        val detail = GalleryDetail(
            id = 102, title = title, thumbnailPath = english.thumbnailPath,
            pagePaths = List(20) { "$it.jpg" }, tags = english.tags, japaneseTitle = english.japaneseTitle
        )
        runBlocking {
            store.indexSummaries(listOf(russian, english, nextChapter))
            store.confirmSameChapter(101, 102)
            val workId = store.snapshot.first().links.getValue(102).workId
            store.linkAsAnotherChapter(103, workId, null, null, "1.5", 1.5, UnitKind.Chapter)
            store.setReadMark(101, read = true)
            db.readHistoryDao().upsert(ReadHistoryEntity(
                102, title, english.thumbnailPath, page = 3, pageCount = 20, readAt = 1L
            ))
        }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = target.createConfigurationContext(
            Configuration(target.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        )
        val bitmap = InstrumentationRegistry.getInstrumentation().context.assets
            .open("manga-fixture/001.png").use { BitmapFactory.decodeStream(it)!! }
        val cache = MemoryCache.Builder(context).maxSizePercent(0.1).build()
        cache[MemoryCache.Key(NhentaiCdn.thumb(detail.thumbnailPath)!!)] = MemoryCache.Value(bitmap)
        val loader = ImageLoader.Builder(context).memoryCache(cache).build()
        val snapshot = runBlocking { store.snapshot.first() }
        val history = runBlocking { db.readHistoryDao().getAll().associateBy { it.galleryId } }
        val opened = mutableListOf<Int>()
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalImageLoader provides loader
            ) {
                PeachyTheme {
                    Scaffold(
                        modifier = Modifier.testTag("detail-proof"),
                        topBar = { GalleryDetailTopBar(detail) {} }
                    ) { padding ->
                        Column(
                            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            GalleryDetailHeader(detail)
                            GalleryDetailActions(
                                detail, 3, false, false, null, {}, {}, {}, {}, false, {}
                            )
                            GalleryIdentitySection(
                                102, detail.toEditionMetadata(), snapshot, history,
                                onOpenGallery = { opened += it }, onSetReadMark = {},
                                onConfirmSameChapter = {}, onLinkAsTranslationOfUnit = { _, _ -> },
                                onLinkAsAnotherChapter = { _, _, _, _, _, _ -> }, onUnlink = {}, onRejectSuggestion = {}
                            )
                            GalleryDetailTagGroup("artist", listOf(artistTag)) {}
                            GalleryDetailTagGroup("language", listOf(enTag)) {}
                        }
                    }
                }
            }
        }
        val output = File(target.getExternalFilesDir(null), "detail-hierarchy-proof").apply { mkdirs() }
        fun capture(name: String) {
            File(output, "$name.png").outputStream().use {
                compose.onNodeWithTag("detail-proof").captureToImage().asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.onNodeWithText(context.getString(R.string.manga_identity_read_inherited)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.manga_identity_action_same_chapter)).assertDoesNotExist()
        capture("collapsed")
        compose.onNodeWithText(context.getString(R.string.manga_identity_section_title)).performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.manga_identity_confirmed_translations)).performScrollTo()
        compose.onNode(hasText(russian.title, substring = true) and hasClickAction())
            .performScrollTo().performClick()
        capture("expanded-translations")
        compose.onNodeWithText(
            context.getString(R.string.manga_identity_unit_prefix_chapter, "1.5")
        ).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(101, 103), opened) }
        compose.onNodeWithText(context.getString(R.string.manga_identity_management_close)).performScrollTo()
        capture("expanded-management")
        compose.onNodeWithText(context.getString(R.string.manga_identity_management_close)).performClick()
        compose.onNodeWithText(context.getString(R.string.manga_identity_action_same_chapter)).assertDoesNotExist()
        loader.shutdown()
    }
}
