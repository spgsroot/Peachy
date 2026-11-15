package ru.spgsroot.peachy.ui.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinksTest {
    @Test fun aBareLinkAndALinkInsideASentenceAreBothFound() {
        assertEquals(listOf("https://cdn.example.com/a/clip.mp4?token=1"),
            ShareLinks.extract("https://cdn.example.com/a/clip.mp4?token=1"))
        assertEquals(listOf("https://example.com/v.mp4"),
            ShareLinks.extract("Смотри: https://example.com/v.mp4, это круто!"))
    }

    @Test fun punctuationAfterTheLinkIsNotPartOfIt() {
        assertEquals(listOf("http://example.com/v.mp4"), ShareLinks.extract("(http://example.com/v.mp4)."))
    }

    @Test fun severalLinksKeepTheirOrderAndRepeatsAreDropped() {
        assertEquals(
            listOf("https://a.example/1.mp4", "https://b.example/2.mp4"),
            ShareLinks.extract("https://a.example/1.mp4\nhttps://b.example/2.mp4 https://a.example/1.mp4")
        )
    }

    @Test fun textWithoutAWebLinkYieldsNothing() {
        assertTrue(ShareLinks.extract(null).isEmpty())
        assertTrue(ShareLinks.extract("no link here, ftp://example.com/file").isEmpty())
    }

    @Test fun onlyWebAddressesCountAsLinksToDownload() {
        assertTrue(ShareUriPolicy.isLink("HTTPS://example.com/v.mp4"))
        assertFalse(ShareUriPolicy.isLink("content://media/1"))
    }
}
