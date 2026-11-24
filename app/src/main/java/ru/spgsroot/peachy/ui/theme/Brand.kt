package ru.spgsroot.peachy.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.spgsroot.peachy.R

private val MarkHeight = 32.dp
private val MarkGap = 10.dp
private const val Wordmark = "peachy."

@Composable
private fun wordmarkStyle(): TextStyle = MaterialTheme.typography.headlineMedium.copy(
    fontWeight = FontWeight.SemiBold,
    letterSpacing = (-0.5).sp
)

@Composable
private fun markAspect(): Float {
    val size = painterResource(R.drawable.ic_peachy_mark).intrinsicSize
    return if (size.width > 0f && size.height > 0f) size.width / size.height else 1f / 1.3f
}

/**
 * Width the full lockup needs at the current font scale and face. Callers that gate the
 * wordmark on a narrow slot compare against this instead of a guessed pixel value.
 */
@Composable
fun peachyBrandWidth(): Dp {
    val textWidth = rememberTextMeasurer()
        .measure(Wordmark, style = wordmarkStyle(), maxLines = 1, softWrap = false)
        .size.width
    return MarkHeight * markAspect() + MarkGap + with(LocalDensity.current) { textWidth.toDp() }
}

/** Width of the sculpted mark alone, the fallback the lockup uses when the wordmark does not fit. */
@Composable
fun peachyMarkWidth(): Dp = MarkHeight * markAspect()

/**
 * Peachy lockup: the supplied sculpted P and, unless a screen already names the product, the
 * "peachy." wordmark with its accent period. The mark is decorative next to the wordmark.
 */
@Composable
fun PeachyBrand(
    modifier: Modifier = Modifier,
    showWordmark: Boolean = true
) {
    val mark = painterResource(R.drawable.ic_peachy_mark)
    val markWidth = MarkHeight * markAspect()
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = mark,
            contentDescription = if (showWordmark) null else stringResource(R.string.app_name),
            modifier = Modifier.size(width = markWidth, height = MarkHeight)
        )
        if (showWordmark) {
            Spacer(Modifier.width(MarkGap))
            val accent = MaterialTheme.colorScheme.primary
            Text(
                text = buildAnnotatedString {
                    append("peachy")
                    withStyle(SpanStyle(color = accent)) { append('.') }
                },
                style = wordmarkStyle(),
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
