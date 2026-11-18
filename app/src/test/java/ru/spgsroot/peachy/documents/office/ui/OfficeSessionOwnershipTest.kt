package ru.spgsroot.peachy.documents.office.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeSessionOwnershipTest {

    private val ownership = OfficeSessionOwnership()

    @Test
    fun `one session owns the engine at a time`() {
        val first = ownership.acquire("session-a")
        assertTrue(first is OfficeSessionOwnership.Acquisition.Granted)
        val lease = (first as OfficeSessionOwnership.Acquisition.Granted).lease
        assertEquals("session-a", lease.sessionId)
        assertTrue(ownership.owns(lease))

        val second = ownership.acquire("session-b")
        assertEquals(
            "session-a",
            (second as OfficeSessionOwnership.Acquisition.Busy).ownerSessionId,
        )
    }

    @Test
    fun `a duplicate acquisition is busy rather than a second owner`() {
        ownership.acquire("session-a")
        val again = ownership.acquire("session-a")
        assertTrue(again is OfficeSessionOwnership.Acquisition.Busy)
    }

    @Test
    fun `foreign and stale leases cannot release the document`() {
        val granted = ownership.acquire("session-a") as OfficeSessionOwnership.Acquisition.Granted
        val forged = OfficeSessionOwnership.Lease("session-a", granted.lease.token + 1)
        val other = OfficeSessionOwnership.Lease("session-b", granted.lease.token)

        assertFalse(ownership.release(forged))
        assertFalse(ownership.release(other))
        assertTrue(ownership.owns(granted.lease))

        assertTrue(ownership.release(granted.lease))
        assertNull(ownership.ownerSessionId)
        assertFalse(ownership.owns(granted.lease))
        assertFalse(ownership.release(granted.lease))
    }

    @Test
    fun `the engine can be re-leased after the owner leaves`() {
        val first = ownership.acquire("session-a") as OfficeSessionOwnership.Acquisition.Granted
        ownership.release(first.lease)

        val second = ownership.acquire("session-b")
        assertTrue(second is OfficeSessionOwnership.Acquisition.Granted)
        assertTrue((second as OfficeSessionOwnership.Acquisition.Granted).lease.token > first.lease.token)
    }

    @Test
    fun `a lease can be found by session but not by another session id`() {
        val granted = ownership.acquire("session-a") as OfficeSessionOwnership.Acquisition.Granted
        assertEquals(granted.lease, ownership.leaseOf("session-a"))
        assertNull(ownership.leaseOf("session-b"))
    }
}
