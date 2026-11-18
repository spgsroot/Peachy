package ru.spgsroot.peachy.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.ui.theme.PeachyBrand

/**
 * The stage with nothing to play: brand mark, what the state is, and the one way out. Drawn on the
 * app background rather than the media black the player uses, and centred inside the Scaffold's
 * padding, so the top bar and the system insets keep their clearance.
 */
@Composable
fun EmptyState(
    modifier: Modifier = Modifier,
    onOpenLibrary: () -> Unit
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PeachyBrand(showWordmark = false)
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.player_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.player_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onOpenLibrary,
            modifier = Modifier.heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Text(stringResource(R.string.player_empty_action))
        }
    }
}
