package ru.spgsroot.peachy.documents.office.ui

/**
 * Single-session ownership bookkeeping for the process-wide engine.
 *
 * The pinned JNI glue keeps one static document-callback slot, so exactly one session may own the
 * engine's document at a time. A lease is the proof: only the owner may close the document, and a
 * stale lease from an earlier screen (or a rotation that arrived late) is ignored.
 *
 * Pure Kotlin on purpose: the rules above are unit-testable without an engine, a device or
 * Android. Callers must still serialize access (see `OfficeEngineHost`).
 */
class OfficeSessionOwnership {

    data class Lease(val sessionId: String, val token: Long)

    sealed interface Acquisition {
        data class Granted(val lease: Lease) : Acquisition
        data class Busy(val ownerSessionId: String) : Acquisition
    }

    private var counter = 0L
    private var owner: Lease? = null

    val ownerSessionId: String? get() = owner?.sessionId

    fun acquire(sessionId: String): Acquisition {
        require(sessionId.isNotBlank()) { "session id is required" }
        val current = owner
        if (current != null) return Acquisition.Busy(current.sessionId)
        val lease = Lease(sessionId, ++counter)
        owner = lease
        return Acquisition.Granted(lease)
    }

    fun owns(lease: Lease): Boolean = owner == lease

    /** The current lease for [sessionId], used to clean up a grant the caller never received. */
    fun leaseOf(sessionId: String): Lease? = owner?.takeIf { it.sessionId == sessionId }

    /** Returns true only for the current owner; every other release is a no-op. */
    fun release(lease: Lease): Boolean {
        if (owner != lease) return false
        owner = null
        return true
    }
}
