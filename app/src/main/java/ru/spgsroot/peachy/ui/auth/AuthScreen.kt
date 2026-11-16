package ru.spgsroot.peachy.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.R

@Composable
fun AuthScreen(
    onVerifyPassword: suspend (String) -> Boolean,
    onAuthenticated: () -> Unit,
    statusMessage: String? = null,
    inputEnabled: Boolean = true
) {
    // Credentials deliberately do not enter saved instance state.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val enabled = inputEnabled && !verifying
    fun submit() {
        if (!enabled || password.isEmpty()) return
        verifying = true
        scope.launch {
            try {
                if (onVerifyPassword(password)) {
                    password = ""
                    onAuthenticated()
                } else {
                    failed = true
                    password = ""
                }
            } finally { verifying = false }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val keySize = if (maxHeight < 640.dp || maxWidth < 360.dp) 56.dp else 72.dp
        // verticalScroll gives unbounded height, so fillMaxSize alone cannot centre the card;
        // the minimum height keeps it in the middle of the visible area.
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            ElevatedCard(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        statusMessage ?: stringResource(R.string.auth_enter_password),
                        textAlign = TextAlign.Center
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Mirrors the visibility button so the digits sit on the card's centre line.
                        Spacer(Modifier.size(48.dp))
                        DigitReadout(
                            value = password,
                            revealed = visible,
                            isError = failed,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { visible = !visible }, enabled = password.isNotEmpty()) {
                            Icon(
                                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                stringResource(R.string.auth_toggle_password)
                            )
                        }
                    }
                    if (failed) {
                        Text(
                            stringResource(R.string.auth_wrong_password),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (verifying) {
                        Text(stringResource(R.string.verifying), style = MaterialTheme.typography.bodySmall)
                    }
                    NumericKeypad(
                        enabled = enabled,
                        onDigit = { digit -> password += digit; failed = false },
                        onDelete = {
                            if (password.isNotEmpty()) password = password.dropLast(1)
                            failed = false
                        },
                        onConfirm = ::submit,
                        confirmEnabled = password.isNotEmpty(),
                        keySize = keySize,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}
