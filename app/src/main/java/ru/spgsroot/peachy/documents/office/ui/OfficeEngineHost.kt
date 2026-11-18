package ru.spgsroot.peachy.documents.office.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.spgsroot.peachy.documents.office.engine.OfficeEngine

/**
 * The process-wide owner of the one [OfficeEngine].
 *
 * The pinned JNI glue registers document callbacks in a single static slot, so two engine instances
 * in one process are not supported. This object:
 * - creates the engine once and never lets a screen call `shutdown()` (a second
 *   `initializeNative` is not a safe operation);
 * - grants a lease to exactly one session at a time ([OfficeSessionOwnership]);
 * - closes the *document* when that session leaves, but keeps the warm engine for the next one;
 * - ignores releases from a lease the caller no longer owns, so rotation or a stale screen cannot
 *   close someone else's document.
 *
 * Calls into the engine are serialized by the engine's single thread; the mutex here serializes
 * lease changes and engine creation. A pending close is awaited before a new session is granted,
 * so a fast back-then-open cannot race `open` against the previous `close`.
 */
object OfficeEngineHost {

    sealed interface Grant {
        data class Granted(
            val lease: OfficeSessionOwnership.Lease,
            val engine: OfficeEngine,
        ) : Grant

        data class Busy(val ownerSessionId: String) : Grant
    }

    private val mutex = Mutex()
    private val lock = Any()
    private val ownership = OfficeSessionOwnership()
    private val lifecycle = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var engine: OfficeEngine? = null

    /** Guarded by [lock]; completed jobs may stay referenced, joining them is free. */
    private var closeJob: Job? = null

    suspend fun acquire(context: Context, sessionId: String): Grant {
        awaitPendingClose()
        return mutex.withLock {
            val acquisition = synchronized(lock) { ownership.acquire(sessionId) }
            when (acquisition) {
                is OfficeSessionOwnership.Acquisition.Busy -> Grant.Busy(acquisition.ownerSessionId)
                is OfficeSessionOwnership.Acquisition.Granted -> {
                    val shared = synchronized(lock) {
                        engine ?: OfficeEngine.create(context).also { engine = it }
                    }
                    Grant.Granted(acquisition.lease, shared)
                }
            }
        }
    }

    /** Closes the document for [lease] and waits for it before returning. */
    suspend fun release(lease: OfficeSessionOwnership.Lease) {
        beginRelease(lease)?.join()
    }

    /** Non-suspending cleanup for `ViewModel.onCleared`, where the view-model scope is gone. */
    fun releaseDetached(lease: OfficeSessionOwnership.Lease) {
        beginRelease(lease)
    }

    /**
     * Fallback for a grant that never reached the caller: if this session still owns the engine,
     * release it. Never touches another session's lease.
     */
    fun releaseSessionDetached(sessionId: String) {
        val lease = synchronized(lock) { ownership.leaseOf(sessionId) } ?: return
        beginRelease(lease)
    }

    /** Only the current owner closes the document; the lease is invalidated first. */
    private fun beginRelease(lease: OfficeSessionOwnership.Lease): Job? {
        var job: Job? = null
        synchronized(lock) {
            if (!ownership.release(lease)) return null
            val current = engine ?: return null
            job = lifecycle.launch(start = CoroutineStart.LAZY) { current.close() }
            closeJob = job
        }
        job?.start()
        return job
    }

    private suspend fun awaitPendingClose() {
        while (true) {
            val pending = synchronized(lock) { closeJob }
            if (pending == null || pending.isCompleted) return
            pending.join()
        }
    }
}
