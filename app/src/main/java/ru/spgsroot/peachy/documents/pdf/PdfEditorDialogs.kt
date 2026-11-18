package ru.spgsroot.peachy.documents.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.spgsroot.peachy.R

@Composable
internal fun TextEditDialog(
    editor: PdfTextEditorState,
    onDraft: (String) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    val plan = editor.plan
    val previewWidth = 300f
    val originalWidth = editor.target.bounds.width.takeIf { it > 0.5f } ?: 300f
    val previewSize = ((plan?.sizePt ?: editor.target.fontSize) * (previewWidth / originalWidth))
        .coerceIn(6f, 32f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_edit_title)) },
        text = {
            Column(Modifier.imePadding()) {
                Text(
                    editor.target.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = editor.draft,
                    onValueChange = onDraft,
                    label = { Text(stringResource(R.string.pdf_edit_field)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.pdf_edit_preview),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((previewSize * 2.4f).dp)
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .border(
                            1.dp,
                            if (plan?.overflows == true) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.outlineVariant,
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = editor.draft.ifEmpty { " " },
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        fontFamily = FontFamily.Default,
                        fontSize = previewSize.sp,
                        maxLines = 2,
                    )
                }
                if (plan != null && plan.overflows) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(
                                R.string.pdf_edit_overflow,
                                plan.overflowPt.toInt().coerceAtLeast(1),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                editor.refusal?.let { refusal ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.pdf_edit_refused) + " " + refusalText(refusal),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onApply, enabled = plan != null) {
                Text(stringResource(R.string.pdf_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.pdf_cancel))
            }
        },
    )
}

@Composable
internal fun refusalText(refusal: PdfEditRefusal): String = when (refusal) {
    PdfEditRefusal.ROTATED_PAGE -> stringResource(R.string.pdf_edit_refuse_rotated)
    PdfEditRefusal.OFFSET_PAGE -> stringResource(R.string.pdf_edit_refuse_offset)
    PdfEditRefusal.VERTICAL_TEXT -> stringResource(R.string.pdf_edit_refuse_vertical)
    PdfEditRefusal.EMPTY_TEXT -> stringResource(R.string.pdf_edit_refuse_empty)
    PdfEditRefusal.UNCHANGED_TEXT -> stringResource(R.string.pdf_edit_refuse_unchanged)
    PdfEditRefusal.MULTILINE_TEXT -> stringResource(R.string.pdf_edit_refuse_multiline)
    PdfEditRefusal.TEXT_TOO_LONG -> stringResource(R.string.pdf_edit_refuse_long)
    PdfEditRefusal.MISSING_GLYPHS -> stringResource(R.string.pdf_edit_refuse_glyphs)
    PdfEditRefusal.NO_LINE -> stringResource(R.string.pdf_edit_refuse_line)
}

@Composable
internal fun DestructiveEditDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.pdf_edit_confirm_title)) },
        text = { Text(stringResource(R.string.pdf_edit_confirm_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.pdf_edit_confirm_action), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.pdf_cancel)) }
        },
    )
}

@Composable
internal fun TextInputDialog(
    title: String,
    label: String,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.imePadding()) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(label) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.pdf_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pdf_cancel)) }
        },
    )
}

@Composable
internal fun PasswordDialog(
    title: String,
    message: String,
    rejected: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.imePadding()) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.pdf_password_field)) },
                    singleLine = true,
                    isError = rejected,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (rejected) {
                        { Text(stringResource(R.string.pdf_error_wrong_password)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password) }) {
                Text(stringResource(R.string.pdf_password_unlock))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pdf_cancel)) }
        },
    )
}

@Composable
internal fun P12PasswordDialog(onDismiss: () -> Unit, onSubmit: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_p12_title)) },
        text = {
            Column(Modifier.imePadding()) {
                Text(
                    stringResource(R.string.pdf_p12_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.pdf_p12_field)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password.toCharArray()) }) {
                Text(stringResource(R.string.pdf_p12_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pdf_cancel)) }
        },
    )
}

@Composable
internal fun ImageExportDialog(onDismiss: () -> Unit, onExport: (Int, Boolean) -> Unit) {
    var dpi by remember { mutableStateOf(150) }
    var jpeg by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_export_images_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.pdf_export_images_resolution),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(96, 150, 300).forEach { value ->
                        FilterChip(
                            selected = dpi == value,
                            onClick = { dpi = value },
                            label = { Text("$value dpi") },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.pdf_export_images_format),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !jpeg, onClick = { jpeg = false }, label = { Text("PNG") })
                    FilterChip(selected = jpeg, onClick = { jpeg = true }, label = { Text("JPEG") })
                }
                if (jpeg) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.pdf_export_images_jpeg_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = { onExport(dpi, jpeg) }, modifier = Modifier.height(48.dp)) {
                Text(stringResource(R.string.pdf_export_images_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.pdf_cancel)) }
        },
    )
}

/**
 * Autograph pad. The result is an ink annotation, which is a picture of a signature and not a
 * cryptographic signature; the copy says so and the editor never labels the result "signed".
 */
@Composable
internal fun DrawnSignaturePad(onUse: (List<List<PdfPoint>>) -> Unit) {
    var strokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val label = stringResource(R.string.pdf_drawn_signature_hint)

    Column {
        Text(
            stringResource(R.string.pdf_drawn_signature_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(MaterialTheme.colorScheme.inverseSurface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                .semantics { contentDescription = label }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset -> current = listOf(offset) },
                        onDrag = { change, _ ->
                            change.consume()
                            current = current + change.position
                        },
                        onDragEnd = {
                            if (current.size >= 2) strokes = strokes + listOf(current)
                            current = emptyList()
                        },
                        onDragCancel = { current = emptyList() },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                val all = strokes + listOf(current)
                all.forEach { stroke ->
                    if (stroke.size >= 2) {
                        val path = Path().apply {
                            moveTo(stroke.first().x, stroke.first().y)
                            stroke.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(
                            path,
                            color = Color(0xFF15171B),
                            style = Stroke(width = 4f),
                        )
                    }
                }
                if (strokes.isEmpty() && current.isEmpty()) {
                    drawLine(
                        color = Color(0x33FFFFFF),
                        start = Offset(24f, size.height - 32f),
                        end = Offset(size.width - 24f, size.height - 32f),
                        strokeWidth = 2f,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { strokes = emptyList(); current = emptyList() },
                enabled = strokes.isNotEmpty(),
                modifier = Modifier.height(48.dp),
            ) {
                Text(stringResource(R.string.pdf_drawn_signature_clear))
            }
            FilledTonalButton(
                onClick = {
                    val minX = strokes.minOfOrNull { s -> s.minOf { it.x } } ?: 0f
                    val minY = strokes.minOfOrNull { s -> s.minOf { it.y } } ?: 0f
                    onUse(
                        strokes.map { stroke ->
                            stroke.map { PdfPoint(it.x - minX, it.y - minY) }
                        },
                    )
                },
                enabled = strokes.isNotEmpty(),
                modifier = Modifier.height(48.dp),
            ) {
                Text(stringResource(R.string.pdf_drawn_signature_use))
            }
        }
    }
}
