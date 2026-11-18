package ru.spgsroot.peachy.documents.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MuPDF's JNI array getters return `null` for "nothing to report" — a page without widgets,
 * annotations, search hits or text blocks. Iterating one of those values directly threw
 * NullPointerException and the editor refused every PDF, so the engine reads them through
 * [orEmptyList]; the contract is pinned here because the native side cannot run on the JVM.
 */
class PdfNativeArrayTest {

    @Test
    fun nullArrayFromNativeReadsAsEmptyList() {
        val widgets: Array<String>? = null

        assertTrue(widgets.orEmptyList().isEmpty())
    }

    @Test
    fun presentArrayKeepsEveryElement() {
        val widgets: Array<String>? = arrayOf("first", "second")

        assertEquals(listOf("first", "second"), widgets.orEmptyList())
    }
}
