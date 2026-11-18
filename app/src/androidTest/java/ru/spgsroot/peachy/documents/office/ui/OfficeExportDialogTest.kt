package ru.spgsroot.peachy.documents.office.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.documents.office.engine.OfficeExportTarget
import ru.spgsroot.peachy.documents.office.engine.OfficeFamily
import ru.spgsroot.peachy.ui.theme.PeachyTheme

@RunWith(AndroidJUnit4::class)
class OfficeExportDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun delimitedFormatsWarnAboutActiveSheetBeforeSelection() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notice = hasText(context.getString(R.string.office_export_active_sheet_only))
        compose.setContent {
            PeachyTheme {
                OfficeExportDialog(
                    targets = OfficeExportTarget.forFamily(OfficeFamily.SPREADSHEET),
                    notices = listOf(OfficeNotice.XLSB_EXPORT),
                    onPick = {},
                    onDismiss = {},
                )
            }
        }

        for (format in listOf("CSV", "TSV")) {
            compose.onNode(hasClickAction() and hasText(format) and notice)
                .performScrollTo()
                .assertIsDisplayed()
        }
        compose.onAllNodes(hasClickAction() and notice).assertCountEquals(2)
    }
}
