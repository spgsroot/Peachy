package ru.spgsroot.peachy.data.downloader

import okhttp3.Protocol
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PublicNetworkPolicyTest {
    @Test fun publicHttpsPolicyAlsoAppliesToNumericHostsAndRedirects() {
        assertEquals("8.8.8.8", PublicNetworkPolicy.url("https://8.8.8.8/video").host)
        assertThrows(NetworkPolicyException::class.java) {
            PublicNetworkPolicy.url("https://[::1]/video")
        }
        val request = PublicNetworkPolicy.requestBuilder("https://media.example/video")
            .header("Cookie", "session=fixture")
            .header("Referer", "https://media.example/")
            .build()
        fun redirect(location: String) = PublicNetworkPolicy.redirect(
            request,
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(302).message("Found").header("Location", location).build(),
        )
        assertEquals("session=fixture", redirect("/other").header("Cookie"))
        val nextOrigin = redirect("https://cdn.example/other")
        assertNull(nextOrigin.header("Cookie"))
        assertNull(nextOrigin.header("Referer"))
        assertThrows(NetworkPolicyException::class.java) { redirect("https://127.0.0.1/video") }
    }
}
