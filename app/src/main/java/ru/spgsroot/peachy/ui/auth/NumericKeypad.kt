package ru.spgsroot.peachy.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.spgsroot.peachy.R

private val DigitRows = listOf(
    listOf('1', '2', '3'),
    listOf('4', '5', '6'),
    listOf('7', '8', '9')
)

/** On-screen digits. [onConfirm] is omitted in dialogs that already have their own action. */
@Composable
fun NumericKeypad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = false,
    keySize: Dp = 64.dp
) {
    val gap = if (keySize < 60.dp) 10.dp else 14.dp
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        DigitRows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { digit ->
                    RoundKey(
                        label = digit.toString(),
                        enabled = enabled,
                        size = keySize,
                        container = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        onClick = { onDigit(digit) }
                    ) {
                        Text(digit.toString(), style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            if (onConfirm != null) {
                RoundKey(
                    label = stringResource(R.string.btn_login),
                    enabled = enabled && confirmEnabled,
                    size = keySize,
                    container = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    onClick = onConfirm
                ) {
                    Icon(Icons.Default.Check, null, Modifier.size(keySize * .42f))
                }
            } else {
                Spacer(Modifier.size(keySize))
            }
            RoundKey(
                label = "0",
                enabled = enabled,
                size = keySize,
                container = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                onClick = { onDigit('0') }
            ) {
                Text("0", style = MaterialTheme.typography.headlineMedium)
            }
            RoundKey(
                label = stringResource(R.string.auth_delete_digit),
                enabled = enabled,
                size = keySize,
                container = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                onClick = onDelete
            ) {
                Icon(Icons.AutoMirrored.Filled.Backspace, null, Modifier.size(keySize * .38f))
            }
        }
    }
}

@Composable
fun DigitReadout(
    value: String,
    revealed: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    val color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
        when {
            value.isEmpty() -> Unit
            revealed -> Text(
                value,
                color = color,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            else -> Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(value.length) {
                    Box(Modifier.size(10.dp).background(color, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun RoundKey(
    label: String,
    enabled: Boolean,
    size: Dp,
    container: Color,
    contentColor: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size).semantics { contentDescription = label },
        shape = CircleShape,
        color = container,
        contentColor = contentColor,
        shadowElevation = 4.dp
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}
