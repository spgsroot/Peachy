package ru.spgsroot.peachy.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.text.DecimalFormat
import java.util.Date
import kotlinx.coroutines.launch
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.translate.*

@Composable
internal fun ProviderModelsPicker(
    draft: ProviderSettings,
    engine: TranslateEngine,
    onModels: suspend (ProviderSettings) -> List<ProviderModel>,
    onMeasure: suspend (ProviderSettings) -> ModelSpeed,
    onSpeeds: suspend (String, List<ProviderModel>) -> Map<String, ModelSpeed>,
    onPick: (String) -> Unit
) {
    var models by remember { mutableStateOf<List<ProviderModel>?>(null) }
    var speeds by remember { mutableStateOf<Map<String, ModelSpeed>>(emptyMap()) }
    var expanded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var measuring by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ProviderModel?>(null) }
    var error by remember { mutableStateOf<TranslationError?>(null) }
    var imagesOnly by remember(engine) { mutableStateOf(engine == TranslateEngine.Model) }
    var sort by remember { mutableStateOf(ModelSort.Price) }
    val scope = rememberCoroutineScope()
    val canList = ProviderUrl.isAllowed(draft.baseUrl) &&
        (draft.apiKey.isEmpty() || ProviderKey.isValid(draft.apiKey))
    val visible = remember(models, imagesOnly, sort, speeds) {
        ProviderModels.visible(models.orEmpty(), imagesOnly, sort, speeds)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(sort, imagesOnly, models, speeds) { listState.scrollToItem(0) }
    fun fetch() {
        loading = true
        error = null
        scope.launch {
            try {
                val result = onModels(draft)
                speeds = onSpeeds(draft.baseUrl, result)
                models = result
                expanded = true
            } catch (e: TranslationException) {
                error = e.error
            } finally { loading = false }
        }
    }
    Column {
        Box {
            TextButton(enabled = canList && !loading, onClick = {
                if (models == null) fetch() else expanded = true
            }) { Text(stringResource(if (loading) R.string.models_loading else R.string.models_action)) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                Column(Modifier.width(280.dp).padding(horizontal = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = imagesOnly, onCheckedChange = { imagesOnly = it })
                        Text(stringResource(R.string.models_images_only), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ModelSort.entries.forEach { option ->
                            FilterChip(selected = sort == option, onClick = { sort = option }, label = {
                                Text(stringResource(when (option) {
                                    ModelSort.Price -> R.string.models_sort_price
                                    ModelSort.Speed -> R.string.models_sort_speed
                                    ModelSort.Name -> R.string.models_sort_name
                                }))
                            })
                        }
                    }
                    Text(stringResource(R.string.models_price_ranking), style = MaterialTheme.typography.labelSmall)
                    Text(stringResource(R.string.models_estimate), style = MaterialTheme.typography.labelSmall)
                    TextButton(enabled = !loading, onClick = { fetch() }) { Text(stringResource(R.string.models_refresh)) }
                    if (visible.isEmpty()) Text(stringResource(R.string.models_empty))
                    LazyColumn(Modifier.width(280.dp).height(280.dp), state = listState) {
                        items(visible, key = { it.id }) { item ->
                            Column(Modifier.fillMaxWidth().clickable { onPick(item.id); expanded = false }.padding(vertical = 8.dp)) {
                                Text(item.id, style = MaterialTheme.typography.titleSmall)
                                if (item.name != item.id) Text(item.name, style = MaterialTheme.typography.bodySmall)
                                Text(stringResource(when (item.vision) {
                                    ModelVision.Yes -> R.string.models_vision
                                    ModelVision.No -> R.string.models_text_only
                                    ModelVision.Unknown -> R.string.models_vision_unknown
                                }), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                val format = remember { DecimalFormat("0.######") }
                                Text(stringResource(R.string.models_price,
                                    item.inputPrice?.let(format::format) ?: stringResource(R.string.models_unknown),
                                    item.outputPrice?.let(format::format) ?: stringResource(R.string.models_unknown)),
                                    style = MaterialTheme.typography.bodySmall)
                                if (item.hasPeakPricing) Text(stringResource(R.string.models_peak, format.format(item.multiplier)),
                                    style = MaterialTheme.typography.labelSmall)
                                val speed = speeds[item.id]
                                if (speed == null) Text(stringResource(R.string.models_unmeasured), style = MaterialTheme.typography.bodySmall)
                                else {
                                    Text(stringResource(R.string.models_speed, speed.firstTokenMs, speed.totalMs), style = MaterialTheme.typography.bodySmall)
                                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(speed.measuredAt)),
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(enabled = draft.copy(model = item.id).isConfigured && item.vision != ModelVision.No && measuring == null,
                                    onClick = { pending = item; expanded = false }) {
                                    Text(stringResource(R.string.models_measure))
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
        measuring?.let { Text(stringResource(R.string.models_measuring, it), style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(errorName(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    pending?.let { item ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.models_measure)) },
            text = { Text(stringResource(R.string.models_measure_confirm, item.id)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    measuring = item.id
                    error = null
                    scope.launch {
                        try {
                            val speed = onMeasure(draft.copy(model = item.id))
                            speeds = speeds + (item.id to speed)
                            expanded = true
                        } catch (e: TranslationException) { error = e.error
                        } finally { measuring = null }
                    }
                }) { Text(stringResource(R.string.models_measure)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.btn_cancel)) } }
        )
    }
}
