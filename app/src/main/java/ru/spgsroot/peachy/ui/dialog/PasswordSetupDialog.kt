package ru.spgsroot.peachy.ui.dialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.ui.auth.NumericKeypad

@Composable
fun PasswordSetupDialog(
    onDismiss: () -> Unit,
    onSetPassword: (String) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var active by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val errorPasswordMinLength = stringResource(R.string.error_password_min_length)
    val errorPasswordsMismatch = stringResource(R.string.error_passwords_mismatch)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_set_password)) },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.password_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                DigitSlot(
                    stringResource(R.string.label_password),
                    password,
                    active == 0,
                    revealed,
                    false,
                    { active = 0 },
                    { revealed = !revealed }
                )
                DigitSlot(
                    stringResource(R.string.label_confirm_password),
                    confirmPassword,
                    active == 1,
                    revealed,
                    error != null,
                    { active = 1 },
                    { revealed = !revealed }
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                }
                NumericKeypad(
                    enabled = true,
                    onDigit = { digit ->
                        error = null
                        if (active == 0) password += digit else confirmPassword += digit
                    },
                    onDelete = {
                        error = null
                        if (active == 0) password = password.dropLast(1) else confirmPassword = confirmPassword.dropLast(1)
                    },
                    keySize = 52.dp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    password.isEmpty() -> error = errorPasswordMinLength
                    password != confirmPassword -> error = errorPasswordsMismatch
                    else -> {
                        onSetPassword(password)
                        onDismiss()
                    }
                }
            }) { Text(stringResource(R.string.btn_set)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

@Composable
fun PasswordChangeDialog(
    onDismiss: () -> Unit,
    currentPasswordError: Boolean,
    onChangePassword: (currentPassword: String, newPassword: String) -> Unit
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var active by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val errorPasswordMinLength = stringResource(R.string.error_password_min_length)
    val errorPasswordsMismatch = stringResource(R.string.error_passwords_mismatch)
    val errorCurrentPasswordWrong = stringResource(R.string.error_current_password_wrong)

    LaunchedEffect(currentPasswordError) {
        if (currentPasswordError) error = errorCurrentPasswordWrong
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_change_password)) },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DigitSlot(
                    stringResource(R.string.label_current_password),
                    currentPassword,
                    active == 0,
                    revealed,
                    error == errorCurrentPasswordWrong,
                    { active = 0 },
                    { revealed = !revealed }
                )
                DigitSlot(
                    stringResource(R.string.label_password),
                    newPassword,
                    active == 1,
                    revealed,
                    false,
                    { active = 1 },
                    { revealed = !revealed }
                )
                DigitSlot(
                    stringResource(R.string.label_confirm_password),
                    confirmNewPassword,
                    active == 2,
                    revealed,
                    error != null && error != errorCurrentPasswordWrong,
                    { active = 2 },
                    { revealed = !revealed }
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                }
                NumericKeypad(
                    enabled = true,
                    onDigit = { digit ->
                        error = null
                        when (active) {
                            0 -> currentPassword += digit
                            1 -> newPassword += digit
                            else -> confirmNewPassword += digit
                        }
                    },
                    onDelete = {
                        error = null
                        when (active) {
                            0 -> currentPassword = currentPassword.dropLast(1)
                            1 -> newPassword = newPassword.dropLast(1)
                            else -> confirmNewPassword = confirmNewPassword.dropLast(1)
                        }
                    },
                    keySize = 52.dp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    newPassword.isEmpty() -> error = errorPasswordMinLength
                    newPassword != confirmNewPassword -> error = errorPasswordsMismatch
                    else -> onChangePassword(currentPassword, newPassword)
                }
            }) { Text(stringResource(R.string.btn_change)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        }
    )
}

@Composable
private fun DigitSlot(
    label: String,
    value: String,
    selected: Boolean,
    revealed: Boolean,
    isError: Boolean,
    onSelect: () -> Unit,
    onToggleReveal: () -> Unit
) {
    val borderColor = when {
        isError -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .45f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(
                    when {
                        value.isEmpty() -> "—"
                        revealed -> value
                        else -> "•".repeat(value.length.coerceAtMost(24)) + if (value.length > 24) "…" else ""
                    },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onToggleReveal, enabled = value.isNotEmpty()) {
                Icon(
                    if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    stringResource(R.string.auth_toggle_password)
                )
            }
        }
    }
}
