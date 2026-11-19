package ru.spgsroot.peachy.ui.manga

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.locale.LocaleManager
import ru.spgsroot.peachy.manga.ReaderSettings
import ru.spgsroot.peachy.manga.TranslateMode
import ru.spgsroot.peachy.translate.BatchState
import ru.spgsroot.peachy.translate.PageBytes
import ru.spgsroot.peachy.translate.PageState
import ru.spgsroot.peachy.translate.EnginePageTranslator
import ru.spgsroot.peachy.translate.OcrBlock
import ru.spgsroot.peachy.translate.ProviderSettings
import ru.spgsroot.peachy.translate.ProviderSettingsStore
import ru.spgsroot.peachy.translate.TargetLanguage
import ru.spgsroot.peachy.translate.TranslateRequest
import ru.spgsroot.peachy.translate.TranslationClient
import ru.spgsroot.peachy.translate.TranslationError
import ru.spgsroot.peachy.translate.TranslationException
import ru.spgsroot.peachy.translate.TranslationKey
import ru.spgsroot.peachy.translate.TranslationRunner
import ru.spgsroot.peachy.translate.TranslationStore
import ru.spgsroot.peachy.translate.ProviderModel
import ru.spgsroot.peachy.translate.ModelSpeed
import ru.spgsroot.peachy.translate.ModelSpeedCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class TranslationChoice(val galleryId: Int, val request: TranslateRequest) {
    val key: String = TranslationKey.of(request.engine, request.source, request.target)
}

/** The tag stays attached even while stateIn still holds the previous runner's last emission. */
internal data class ReaderTranslationState(
    val choice: TranslationChoice,
    val pages: Map<Int, PageState>,
    val batch: BatchState?,
    val needsConsent: Boolean
) {
    fun forChoice(expected: TranslationChoice): ReaderTranslationState? = takeIf { choice == expected }
}

internal class ReaderTranslationSession(val choice: TranslationChoice, val runner: TranslationRunner) {
    var active: Boolean = true
        private set

    fun close() {
        active = false
        runner.cancelBatch()
    }

    suspend fun afterCacheMiss(page: Int, force: Boolean, action: () -> Unit) {
        if (!active) return
        if (!force && runner.showCached(choice.galleryId, page, choice.key)) return
        // The cache may have suspended across a gallery or translation-choice change.
        if (active) action()
    }
}

/**
 * Translation for the gallery the reader has open. Nothing is sent before the user has agreed to
 * which host receives the pages; a request that arrives earlier waits for the answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TranslationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providerStore: ProviderSettingsStore,
    private val pageBytes: PageBytes,
    private val store: TranslationStore,
    private val client: TranslationClient,
    private val translator: EnginePageTranslator
) : ViewModel() {
    private val runner = MutableStateFlow<ReaderTranslationSession?>(null)
    private var galleryId = -1
    private var pageCount = 0
    private var pendingAfterConsent: (() -> Unit)? = null

    // The user said no this session: a page turn must not ask again, only a deliberate press does.
    private var declined = false

    private val _needsConsent = MutableStateFlow(false)

    internal val state: StateFlow<ReaderTranslationState?> = runner
        .flatMapLatest { session ->
            if (session == null) flowOf(null)
            else combine(session.runner.pages, session.runner.batch, _needsConsent) { pages, batch, consent ->
                ReaderTranslationState(session.choice, pages, batch, consent)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val provider: StateFlow<ProviderSettings> = providerStore.settings

    internal fun choice(galleryId: Int, settings: ReaderSettings) = TranslationChoice(galleryId, request(settings))

    /** Binds cache checks and their results to the exact choices the reader is composing. */
    internal fun open(choice: TranslationChoice, pagePaths: List<String>) {
        if (runner.value?.choice == choice) return
        if (galleryId != choice.galleryId) {
            declined = false
            pageBytes.bind(pagePaths)
        }
        galleryId = choice.galleryId
        pageCount = pagePaths.size
        select(choice)
    }

    internal suspend fun bubbleBlocks(choice: TranslationChoice, page: Int) =
        translator.bubbleBlocks(choice.galleryId, page, choice.request.source) { pageBytes.raw(choice.galleryId, page) }

    internal suspend fun translateBubble(choice: TranslationChoice, page: Int, block: OcrBlock) =
        translator.translateBubble(choice.galleryId, page, choice.request, block) { pageBytes.raw(choice.galleryId, page) }

    /**
     * Translates [page]. A page the cache already holds is shown without the consent question: nothing
     * is sent. [explicit] is false for a request nobody pressed a button for (a page turn).
     */
    fun translateNow(page: Int, settings: ReaderSettings, force: Boolean = false, explicit: Boolean = true) {
        val request = request(settings)
        val current = runnerFor(request) ?: return
        val id = galleryId
        val key = current.choice.key
        viewModelScope.launch {
            current.afterCacheMiss(page, force) {
                gated(explicit) {
                    viewModelScope.launch {
                        if (current.active) current.runner.translate(id, page, key, request, force)
                    }
                }
            }
        }
    }

    /**
     * Called once per page the reader has settled on, and once for the page it opens on. A page
     * that is already translated is shown in every mode; only the on-turn mode pays for a new one.
     */
    fun onPageSettled(page: Int, settings: ReaderSettings) {
        val request = request(settings)
        val current = runnerFor(request) ?: return
        val key = current.choice.key
        val state = current.runner.pages.value[page]
        if (state == PageState.Loading || state is PageState.Done) return
        if (settings.translateMode == TranslateMode.OnTurn) {
            translateNow(page, settings, explicit = false)
        } else {
            val id = galleryId
            viewModelScope.launch { current.runner.showCached(id, page, key) }
        }
    }

    fun startWhole(settings: ReaderSettings) {
        val request = request(settings)
        val current = runnerFor(request) ?: return
        val key = current.choice.key
        val id = galleryId
        val count = pageCount
        gated(explicit = true) {
            if (current.active) current.runner.startBatch(id, count, key, request)
        }
    }

    fun cancelWhole() {
        runner.value?.runner?.cancelBatch()
    }

    fun grantProviderConsent() {
        providerStore.update { it.copy(consented = true) }
        declined = false
    }

    fun acceptConsent() {
        grantProviderConsent()
        _needsConsent.value = false
        val action = pendingAfterConsent
        pendingAfterConsent = null
        action?.invoke()
    }

    fun declineConsent() {
        declined = true
        _needsConsent.value = false
        pendingAfterConsent = null
    }

    fun saveProvider(change: (ProviderSettings) -> ProviderSettings) {
        providerStore.update(change)
    }

    /**
     * Sends a tiny text-only request built from what the dialog holds, saved or not; null means the
     * address, key and model work.
     */
    suspend fun testProvider(baseUrl: String, model: String, apiKey: String): TranslationError? = try {
        client.check(provider.value.edited(baseUrl, model, apiKey))
        null
    } catch (e: TranslationException) {
        e.error
    }

    suspend fun listModels(draft: ProviderSettings): List<ProviderModel> = client.models(draft)

    suspend fun modelSpeeds(baseUrl: String, models: List<ProviderModel>): Map<String, ModelSpeed> = withContext(Dispatchers.IO) {
        val cache = ModelSpeedCache(context.cacheDir)
        models.mapNotNull { model -> cache.get(baseUrl, model.id)?.let { model.id to it } }.toMap()
    }

    suspend fun measureModel(draft: ProviderSettings): ModelSpeed {
        val speed = client.measureModel(draft)
        withContext(Dispatchers.IO) { ModelSpeedCache(context.cacheDir).put(draft.baseUrl, draft.model, speed) }
        return speed
    }

    /**
     * Runs [action] when pages may be sent. A provider that is not set up still runs it: the client
     * refuses without touching the network and the page shows that the provider is missing.
     */
    private fun gated(explicit: Boolean, action: () -> Unit) {
        val settings = provider.value
        if (settings.isConfigured && !settings.consented) {
            if (!explicit && declined) return
            pendingAfterConsent = action
            _needsConsent.value = true
        } else {
            action()
        }
    }

    private fun request(settings: ReaderSettings) = TranslateRequest(
        settings.engine,
        settings.sourceLanguage,
        TargetLanguage.resolve(
            LocaleManager.getSavedLocale(context), Locale.getDefault().language, settings.targetLanguage
        )
    )

    private fun newRunner() =
        TranslationRunner(pageBytes, translator, store, { provider.value.model }, viewModelScope)

    private fun runnerFor(request: TranslateRequest): ReaderTranslationSession? {
        val current = runner.value ?: return null
        val choice = TranslationChoice(galleryId, request)
        return if (current.choice == choice) current else select(choice)
    }

    private fun select(choice: TranslationChoice): ReaderTranslationSession {
        runner.value?.close()
        pendingAfterConsent = null
        _needsConsent.value = false
        return ReaderTranslationSession(choice, newRunner()).also { runner.value = it }
    }
}
