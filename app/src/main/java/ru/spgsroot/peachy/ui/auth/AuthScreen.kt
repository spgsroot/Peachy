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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.ui.theme.PeachyBrand

// The card's own measurements, shared with the landscape fit below (see DESIGN.md, Layout).
private val PagePadding = 24.dp
private val CardHorizontalPadding = 20.dp
private val CardVerticalPadding = 28.dp
private val PortraitCardMaxWidth = 420.dp
private val LandscapeCardMaxWidth = 520.dp
private val MinKeySize = 48.dp
private val MaxKeySize = 72.dp

/**
 * Largest key that keeps four rows inside [heightBudget] with the gaps [NumericKeypad] draws.
 * Never drops below the 48 dp touch target: a budget too short for that leaves the card taller than
 * the window, and the screen scrolls instead of clipping a key.
 */
private fun keySizeForHeight(heightBudget: Dp): Dp {
    val wideGap = keypadGap(MaxKeySize)
    val withWideGap = (heightBudget - wideGap * 3) / 4
    // The gap rule only applies while the keys are large enough for it.
    if (keypadGap(withWideGap) == wideGap) return withWideGap.coerceIn(MinKeySize, MaxKeySize)
    val tightGap = keypadGap(MinKeySize)
    return ((heightBudget - tightGap * 3) / 4).coerceIn(MinKeySize, MaxKeySize)
}

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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Landscape is shorter than the portrait card needs, so the copy and the keypad sit side
            // by side and the keys are fitted to the height that is actually left over: every row,
            // including check, 0 and delete, stays inside the card without a scroll.
            val landscape = maxWidth > maxHeight
            val keySize = if (landscape) {
                keySizeForHeight(maxHeight - PagePadding * 2 - CardVerticalPadding * 2)
            } else if (maxHeight < 640.dp || maxWidth < 360.dp) {
                56.dp
            } else {
                72.dp
            }
            // verticalScroll gives unbounded height, so fillMaxSize alone cannot centre the card;
            // the minimum height keeps it in the middle of the visible area.
            Box(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = PagePadding, vertical = PagePadding),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    Modifier
                        .widthIn(
                            max = if (landscape) LandscapeCardMaxWidth else PortraitCardMaxWidth
                        )
                        .fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    val copy: @Composable (Modifier) -> Unit = { modifier ->
                        AuthCopy(
                            password = password,
                            visible = visible,
                            failed = failed,
                            verifying = verifying,
                            statusMessage = statusMessage,
                            onToggleVisible = { visible = !visible },
                            modifier = modifier
                        )
                    }
                    val keypad: @Composable (Modifier) -> Unit = { modifier ->
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
                            modifier = modifier
                        )
                    }
                    if (landscape) {
                        Row(
                            Modifier.padding(
                                horizontal = CardHorizontalPadding,
                                vertical = CardVerticalPadding
                            ),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            copy(Modifier.weight(1f))
                            keypad(Modifier)
                        }
                    } else {
                        Column(
                            Modifier.padding(
                                horizontal = CardHorizontalPadding,
                                vertical = CardVerticalPadding
                            ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            copy(Modifier.fillMaxWidth())
                            keypad(Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Brand, status and password readout: the part of the card that is not the keypad. */
@Composable
private fun AuthCopy(
    password: String,
    visible: Boolean,
    failed: Boolean,
    verifying: Boolean,
    statusMessage: String?,
    onToggleVisible: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PeachyBrand()
        Text(
            statusMessage ?: stringResource(R.string.auth_enter_password),
            style = MaterialTheme.typography.bodyMedium,
            color = if (statusMessage != null) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
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
            IconButton(onClick = onToggleVisible, enabled = password.isNotEmpty()) {
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
        } else if (verifying) {
            Text(
                stringResource(R.string.verifying),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
