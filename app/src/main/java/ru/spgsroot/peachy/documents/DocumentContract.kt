package ru.spgsroot.peachy.documents

import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.io.File
import ru.spgsroot.peachy.R

/**
 * Document families the app can open.
 *
 * WORD, EXCEL and POWERPOINT sessions can be imported, listed and discarded, but this build has no
 * Office editor: their route shows an explicit "not available in this build yet" state naming the
 * sniffed format instead of opening a blank editor.
 */
enum class DocumentKind { PDF, WORD, EXCEL, POWERPOINT, IMAGE }

/**
 * One imported document. All editors work on [workingFile] and nowhere else: the imported bytes in
 * the session's `source.<ext>` are never modified, so the working copy is the single truth for the
 * session's content and [dirty] survives process death with it.
 */
data class DocumentSession(
    val id: String, // uuid, directory name
    val displayName: String,
    val format: String, // pdf|docx|...|png|jpg|webp|bmp|heic (sniffed from bytes, not from the name)
    val kind: DocumentKind,
    val workingFile: File, // filesDir/documents/<id>/working.<ext>; editors write it atomically (temp + rename)
    val dirty: Boolean,
)

interface DocumentSessions {
    suspend fun get(id: String): DocumentSession?

    /** Call after each COMMITTED edit, after the working file was atomically replaced. */
    suspend fun markDirty(id: String)

    /** Scratch directory for exports before delivery: filesDir/documents/<id>/exports/ */
    fun exportDir(id: String): File

    /**
     * Validates [produced] exists/non-empty, streams it to [target] (a CreateDocument uri), asserts
     * extension/mime, never truncates before the file is complete. [kind] describes the produced
     * file (header check); null skips the header check for formats that have no signature.
     */
    suspend fun deliver(id: String, produced: File, kind: DocumentKind?, target: Uri): Result<Unit>

    /** Writes [files] into the picked tree uri (DocumentsContract.createDocument), for PDF->images. */
    suspend fun deliverMany(id: String, files: List<File>, tree: Uri): Result<Unit>

    suspend fun saveLastPosition(id: String, page: Int, scroll: Int)

    /** Deletes session dir + row under NonCancellable; returns failure if deletion failed. */
    suspend fun discard(id: String): Result<Unit>
}

/**
 * The one interruption a document editor needs: leaving an edited document. "Save a copy" runs
 * [onSaveCopy] and the caller closes only after the copy was written; "Discard" drops the session's
 * edits, "Cancel" stays in the editor.
 */
@Composable
fun UnsavedChangesDialog(
    onSaveCopy: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.documents_unsaved_title)) },
        text = { Text(stringResource(R.string.documents_unsaved_body), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onSaveCopy) { Text(stringResource(R.string.documents_save_copy)) }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.documents_dialog_cancel)) }
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.documents_discard)) }
            }
        }
    )
}
