package ru.spgsroot.peachy.ui.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.ui.dialog.PasswordSetupDialog
import ru.spgsroot.peachy.ui.theme.PeachyBrand
import ru.spgsroot.peachy.ui.theme.PeachyTheme

@Preview(showBackground = true)
@Composable
private fun OnboardingScreenPrev() {
    PeachyTheme {
        OnboardingScreen(
            onComplete = { },
            onPasswordSet = {}
        )
    }
}

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onPasswordSet: (String) -> Unit
) {
    var showPasswordDialog by remember { mutableStateOf(false) }

    if (showPasswordDialog) {
        PasswordSetupDialog(
            onDismiss = { showPasswordDialog = false },
            onSetPassword = { password ->
                showPasswordDialog = false
                onPasswordSet(password)
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            // Scrolls instead of clipping: compact phones, landscape and a 1.3 font scale all reach the buttons.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PeachyBrand(showWordmark = false)

                Spacer(Modifier.height(28.dp))

                Text(
                    text = stringResource(R.string.welcome_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 480.dp)
                )

                Spacer(Modifier.height(32.dp))

                Column(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FeatureItem(
                        text = stringResource(R.string.onboarding_step_1),
                        icon = Icons.Default.PlayArrow
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FeatureItem(
                        text = stringResource(R.string.onboarding_step_2),
                        icon = Icons.Default.Settings
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    FeatureItem(
                        text = stringResource(R.string.onboarding_step_3),
                        icon = Icons.Default.CheckCircle
                    )
                }

                Spacer(Modifier.height(40.dp))

                Button(
                    onClick = { showPasswordDialog = true },
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.btn_set_password),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = onComplete,
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Text(
                        text = stringResource(R.string.btn_start),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureItem(
    text: String,
    icon: ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
