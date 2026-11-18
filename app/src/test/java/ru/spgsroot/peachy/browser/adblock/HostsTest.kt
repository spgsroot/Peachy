package ru.spgsroot.peachy.browser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostsTest {
    @Test
    fun registrableDomainHandlesTwoPartSuffixes() {
        assertEquals("example.co.uk", Hosts.registrable("a.b.example.co.uk"))
        assertEquals("example.com", Hosts.registrable("cdn.example.com"))
        assertTrue(Hosts.isThirdParty("a.co.uk", "b.co.uk"))
        assertFalse(Hosts.isThirdParty("x.example.co.uk", "y.example.co.uk"))
        assertEquals("host.test", Hosts.hostOf("https://user:pw@Host.Test:8443/p?q#f"))
    }
}
