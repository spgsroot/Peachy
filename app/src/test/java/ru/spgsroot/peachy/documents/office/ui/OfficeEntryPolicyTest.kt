package ru.spgsroot.peachy.documents.office.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.spgsroot.peachy.documents.DocumentFormat
import ru.spgsroot.peachy.documents.office.engine.OfficeLegacyBlock
import ru.spgsroot.peachy.documents.office.engine.OfficeLegacySlide

class OfficeEntryPolicyTest {

    @Test
    fun `integration is off in the delivered build`() {
        assertFalse(OfficeIntegration.ENABLED)
    }

    @Test
    fun `without the opt-in every Office format is refused`() {
        listOf(DocumentFormat.DOCX, DocumentFormat.XLSX, DocumentFormat.PPTX, DocumentFormat.PPT)
            .forEach { format ->
                val decision = OfficeEntryPolicy.decide(format, enabled = false)
                assertTrue(decision is OfficeEntryDecision.Refused)
                assertEquals(
                    OfficeEntryRefusal.INTEGRATION_DISABLED,
                    (decision as OfficeEntryDecision.Refused).reason,
                )
            }
    }

    @Test
    fun `enabled build opens writer and calc formats`() {
        listOf(
            DocumentFormat.DOCX,
            DocumentFormat.DOCM,
            DocumentFormat.DOC,
            DocumentFormat.XLSX,
            DocumentFormat.XLSB,
            DocumentFormat.CSV,
            DocumentFormat.PPTX,
            DocumentFormat.PPSX,
        ).forEach { format ->
            val decision = OfficeEntryPolicy.decide(format, enabled = true)
            assertTrue("$format", decision is OfficeEntryDecision.Open)
            assertEquals(format, (decision as OfficeEntryDecision.Open).format)
        }
    }

    @Test
    fun `legacy ppt opens into the text-only surface`() {
        val decision = OfficeEntryPolicy.decide(DocumentFormat.PPT, enabled = true)
        assertEquals(
            DocumentFormat.PPT,
            (decision as OfficeEntryDecision.Open).format,
        )
    }

    @Test
    fun `pdf and images never reach the office engine`() {
        listOf(DocumentFormat.PDF, DocumentFormat.PNG).forEach { format ->
            val decision = OfficeEntryPolicy.decide(format, enabled = true)
            assertEquals(
                OfficeEntryRefusal.NOT_OFFICE,
                (decision as OfficeEntryDecision.Refused).reason,
            )
        }
    }

    @Test
    fun `unknown stored format is refused`() {
        val decision = OfficeEntryPolicy.decide(null, enabled = true)
        assertEquals(
            OfficeEntryRefusal.UNKNOWN_FORMAT,
            (decision as OfficeEntryDecision.Refused).reason,
        )
    }

    @Test
    fun `legacy text exists only when a slide carries authored text`() {
        assertFalse(OfficeLegacyPolicy.hasText(emptyList()))
        assertFalse(OfficeLegacyPolicy.hasText(listOf(slide(title = " ", blocks = emptyList()))))
        assertFalse(
            OfficeLegacyPolicy.hasText(
                listOf(slide(blocks = listOf(OfficeLegacyBlock.Table(listOf(listOf("", " ")))))),
            ),
        )

        assertTrue(OfficeLegacyPolicy.hasText(listOf(slide(title = "Слайд 1"))))
        assertTrue(
            OfficeLegacyPolicy.hasText(
                listOf(slide(title = "", blocks = listOf(OfficeLegacyBlock.Paragraph("Текст")))),
            ),
        )
        assertTrue(
            OfficeLegacyPolicy.hasText(
                listOf(slide(title = "", blocks = emptyList(), notes = listOf("Заметка"))),
            ),
        )
        assertTrue(
            OfficeLegacyPolicy.hasText(
                listOf(
                    slide(
                        title = "",
                        blocks = listOf(
                            OfficeLegacyBlock.Table(listOf(listOf("Ключ", "Значение"))),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun slide(
        title: String = "",
        blocks: List<OfficeLegacyBlock> = emptyList(),
        notes: List<String> = emptyList(),
    ) = OfficeLegacySlide(title = title, hidden = false, blocks = blocks, notes = notes)
}
