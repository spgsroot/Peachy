package ru.spgsroot.peachy.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import ru.spgsroot.peachy.ui.dialog.VaultDialog
import ru.spgsroot.peachy.data.vault.VaultManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.spgsroot.peachy.R
import ru.spgsroot.peachy.device.buttplug.ButtplugDevice
import ru.spgsroot.peachy.device.buttplug.DeviceState
import ru.spgsroot.peachy.locale.LocaleManager
import ru.spgsroot.peachy.ui.dialog.DeviceScanDialog
import ru.spgsroot.peachy.ui.dialog.PasswordChangeDialog
import ru.spgsroot.peachy.ui.dialog.PasswordSetupDialog
import ru.spgsroot.peachy.ui.dialog.ProviderDialog
import ru.spgsroot.peachy.ui.manga.TranslationViewModel
import ru.spgsroot.peachy.ui.theme.PeachyBrand
import ru.spgsroot.peachy.manga.ReaderSettingsStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDrawer(
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val updates: UpdateViewModel = hiltViewModel(checkNotNull(LocalActivity.current as? ComponentActivity))
    val settings = viewModel.settings.collectAsStateWithLifecycle().value
    val nsfwOn = settings?.nsfwEnabled == true
    val isPasswordSet by viewModel.isPasswordSet.collectAsStateWithLifecycle()
    val deviceState by viewModel.deviceState.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val activeDeviceIndex by viewModel.activeDeviceIndex.collectAsStateWithLifecycle()
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showDeviceDialog by remember { mutableStateOf(false) }
    val translation: TranslationViewModel = hiltViewModel()
    val translationProvider by translation.provider.collectAsStateWithLifecycle()
    var showProviderDialog by remember { mutableStateOf(false) }

    val vaultModel: VaultViewModel = hiltViewModel()
    val vaultState by vaultModel.state.collectAsStateWithLifecycle()
    var vaultMode by rememberSaveable { mutableIntStateOf(VAULT_NONE) }
    // Purely a visibility switch: collapsing the group never disconnects or resets a toy.
    var toysExpanded by rememberSaveable { mutableStateOf(false) }
    var importUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    // Held only until the system file chooser answers; never written to saved state.
    var exportPassword by remember { mutableStateOf<CharArray?>(null) }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val password = exportPassword
        exportPassword = null
        if (uri != null && password != null) vaultModel.export(uri, password)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importUri = uri
            vaultModel.reset()
            vaultMode = VAULT_IMPORT
        }
    }

    LaunchedEffect(nsfwOn) {
        if (!nsfwOn) {
            showProviderDialog = false
            showDeviceDialog = false
            toysExpanded = false
        }
    }

    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    val solWallet = "FB4B37LCd52dgVED5fR7Vj7CDttsa74CQyBc647agQP7"

    // Gesture navigation reports no bottom navigation-bar inset, so clear both that bar and the
    // mandatory gesture strip; windowInsetsPadding subtracts what ModalDrawerSheet already
    // consumed, so three-button navigation does not gain a second inset.
    val bottomInset = WindowInsets.navigationBars
        .union(WindowInsets.mandatorySystemGestures)
        .only(WindowInsetsSides.Bottom)

    ModalDrawerSheet {
        LazyColumn(
            modifier = Modifier.fillMaxHeight().windowInsetsPadding(bottomInset),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item(key = "brand") {
                PeachyBrand(Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
            }

            // Toys stay out of the drawer until the NSFW switch is known to be on.
            if (nsfwOn) {
            item(key = "toys_header") {
                ToysSectionHeader(
                    expanded = toysExpanded,
                    status = deviceStatus(deviceState, devices, activeDeviceIndex),
                    onToggle = { toysExpanded = !toysExpanded }
                )
            }

            if (toysExpanded) {
                item(key = "toys_connection") {
                    Column {
                        Spacer(modifier = Modifier.height(8.dp))

                        DeviceConnectionStatus(
                            deviceState = deviceState,
                            devices = devices,
                            activeDeviceIndex = activeDeviceIndex
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = { showDeviceDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(
                                    if (devices.isNotEmpty()) {
                                        R.string.btn_device_change
                                    } else {
                                        R.string.btn_device_connect
                                    }
                                )
                            )
                        }

                        if (deviceState !is DeviceState.Disconnected || activeDeviceIndex != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = viewModel::disconnectDevice,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.btn_device_disconnect))
                            }
                        }
                    }
                }

                settings?.let { s ->
                    item(key = "toys_dsp") {
                        Spacer(modifier = Modifier.height(16.dp))
                        DspSettings(
                            lowFreq = s.dspConfig.lowFreqHz,
                            highFreq = s.dspConfig.highFreqHz,
                            smoothing = s.dspConfig.smoothingAlpha,
                            threshold = s.dspConfig.threshold,
                            onLowFreqChange = viewModel::updateDspLowFreq,
                            onHighFreqChange = viewModel::updateDspHighFreq,
                            onSmoothingChange = viewModel::updateDspSmoothing,
                            onThresholdChange = viewModel::updateDspThreshold
                        )
                    }

                    item(key = "toys_power_boost") {
                        Spacer(modifier = Modifier.height(16.dp))
                        PowerBoostSlider(
                            powerBoost = s.dspConfig.powerBoost,
                            onPowerBoostChange = viewModel::updateDspPowerBoost
                        )
                    }
                }
            }

            item(key = "toys_divider") {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }
            }

            // ═══════════════════════════════════════════
            // APP SETTINGS — language, playback speed and the media auto-switch timer stay here
            // ═══════════════════════════════════════════
            item(key = "settings_title") {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            settings?.let { s ->
                item(key = "nsfw") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = s.nsfwEnabled,
                                role = Role.Switch,
                                onValueChange = viewModel::setNsfwEnabled
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(stringResource(R.string.settings_nsfw_toggle))
                            Text(
                                stringResource(R.string.settings_nsfw_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = s.nsfwEnabled, onCheckedChange = null)
                    }
                }

                item(key = "timer") {
                    Spacer(modifier = Modifier.height(16.dp))
                    TimerSlider(
                        timerMs = s.timerMs,
                        onTimerChange = viewModel::updateTimer
                    )
                }

                item(key = "playback_speed") {
                    Spacer(modifier = Modifier.height(16.dp))
                    PlaybackSpeedSlider(
                        speed = s.playbackSpeed,
                        onSpeedChange = viewModel::updatePlaybackSpeed
                    )
                }

                item(key = "language") {
                    Spacer(modifier = Modifier.height(16.dp))
                    LanguageSelector(
                        currentLanguage = s.languageCode,
                        onLanguageChange = { language ->
                            viewModel.updateLanguage(language)
                            applyLocale(context, language)
                        }
                    )
                }
            }

            // Divider
            item(key = "app_divider") {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Password button
            item(key = "password") {
                Button(
                    onClick = {
                        if (isPasswordSet) {
                            showChangePasswordDialog = true
                        } else {
                            showPasswordDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            if (isPasswordSet) {
                                R.string.btn_app_password_change
                            } else {
                                R.string.btn_app_password_set
                            }
                        )
                    )
                }
            }

            if (nsfwOn) {
                item(key = "providers") {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(
                        onClick = { showProviderDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_translation_row))
                    }
                }
            }

            item(key = "updates") {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                UpdatesSection(updates)
            }

            // Backup section
            item(key = "backup") {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(stringResource(R.string.vault_section), style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        vaultModel.reset()
                        vaultMode = VAULT_EXPORT
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.vault_export_button))
                }
                OutlinedButton(
                    onClick = { importPicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.vault_import_button))
                }
            }

            // Divider
            item(key = "support_divider") {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Support links
            item(key = "support") {
                Text(stringResource(R.string.support_title), style = MaterialTheme.typography.titleMedium)

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "${stringResource(R.string.support_sol)} $solWallet",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.clickable {
                        clipboardManager.setText(AnnotatedString(solWallet))
                    }
                )
                OutlinedButton(
                    onClick = {
                        context.startActivity(android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/spgsroot/Peachy")
                        ))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.support_github)) }
            }
        }
    }

    // Dialogs remain OUTSIDE LazyColumn, at ModalNavigationDrawer level
    if (showPasswordDialog) {
        PasswordSetupDialog(
            onDismiss = { showPasswordDialog = false },
            onSetPassword = { password ->
                viewModel.setPassword(password)
            }
        )
    }

    if (showChangePasswordDialog) {
        var passwordError by remember { mutableStateOf(false) }
        PasswordChangeDialog(
            onDismiss = {
                showChangePasswordDialog = false
                passwordError = false
            },
            currentPasswordError = passwordError,
            onChangePassword = { currentPassword, newPassword ->
                viewModel.changePassword(
                    currentPassword = currentPassword,
                    newPassword = newPassword,
                    onSuccess = {
                        showChangePasswordDialog = false
                    },
                    onError = {
                        passwordError = true
                    }
                )
            }
        )
    }

    if (vaultMode != VAULT_NONE) {
        VaultDialog(
            export = vaultMode == VAULT_EXPORT,
            state = vaultState,
            onStart = { password ->
                if (vaultMode == VAULT_EXPORT) {
                    exportPassword = password
                    exportPicker.launch(vaultFileName())
                } else {
                    importUri?.let { vaultModel.import(it, password) }
                }
            },
            onCancel = vaultModel::cancel,
            onClose = {
                vaultMode = VAULT_NONE
                importUri = null
                vaultModel.reset()
            }
        )
    }

    if (showProviderDialog) {
        ProviderDialog(
            current = translationProvider,
            onSave = { url, model, key -> translation.saveProvider { it.edited(url, model, key) } },
            onTest = translation::testProvider,
            engine = ReaderSettingsStore.load(context.getSharedPreferences("manga_reader", Context.MODE_PRIVATE)).engine,
            onModels = translation::listModels,
            onMeasure = translation::measureModel,
            onSpeeds = translation::modelSpeeds,
            onDismiss = { showProviderDialog = false }
        )
    }

    if (showDeviceDialog) {
        DeviceScanDialog(
            connectionManager = viewModel.connectionManager,
            activeDeviceIndex = activeDeviceIndex,
            onDisconnect = viewModel::disconnectDevice,
            onDismiss = { showDeviceDialog = false },
            onDeviceSelected = { deviceIndex ->
                viewModel.connectDevice(deviceIndex)
            }
        )
    }
}

@Composable
fun PowerBoostSlider(
    powerBoost: Float,
    onPowerBoostChange: (Float) -> Unit
) {
    Column {
        Text(stringResource(R.string.settings_power_boost), style = MaterialTheme.typography.titleSmall)
        Slider(
            value = powerBoost,
            onValueChange = onPowerBoostChange,
            valueRange = 0.5f..3.0f,
            steps = 24,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
        Text(
            "${(powerBoost * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ToysSectionHeader(expanded: Boolean, status: DeviceStatusUi, onToggle: () -> Unit) {
    val stateText = stringResource(
        if (expanded) R.string.behavior_toys_expanded else R.string.behavior_toys_collapsed
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            // One announcement for the whole row: label, and whether the group is open.
            .semantics(mergeDescendants = true) { stateDescription = stateText }
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.behavior_toys_settings),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        // The state stays readable while the group is closed.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(status.icon, contentDescription = null, tint = status.color, modifier = Modifier.size(16.dp))
            Text(
                text = status.text,
                style = MaterialTheme.typography.bodySmall,
                color = status.color,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Connection state shared by the toys header and the group's status surface. */
private data class DeviceStatusUi(val text: String, val icon: ImageVector, val color: Color)

@Composable
private fun deviceStatus(deviceState: DeviceState, devices: List<ButtplugDevice>, activeDeviceIndex: Int?): DeviceStatusUi {
    val activeDevice = activeDeviceIndex?.let { index -> devices.firstOrNull { it.index == index } }
    val firstDevice = devices.firstOrNull()
    return when {
        activeDevice != null -> DeviceStatusUi(
            stringResource(R.string.device_status_active, activeDevice.name),
            Icons.Default.BluetoothConnected,
            MaterialTheme.colorScheme.tertiary
        )
        deviceState is DeviceState.Connected && firstDevice != null -> DeviceStatusUi(
            stringResource(R.string.device_status_connected, firstDevice.name),
            Icons.Default.BluetoothConnected,
            MaterialTheme.colorScheme.secondary
        )
        deviceState is DeviceState.Scanning -> DeviceStatusUi(
            stringResource(R.string.searching_devices),
            Icons.Default.Sync,
            MaterialTheme.colorScheme.primary
        )
        deviceState is DeviceState.Error -> DeviceStatusUi(
            (deviceState.reason).takeIf { it.isNotBlank() } ?: stringResource(R.string.device_status_error),
            Icons.Default.Error,
            MaterialTheme.colorScheme.error
        )
        else -> DeviceStatusUi(
            stringResource(R.string.device_status_disconnected),
            Icons.Default.BluetoothDisabled,
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DeviceConnectionStatus(
    deviceState: DeviceState,
    devices: List<ButtplugDevice>,
    activeDeviceIndex: Int?
) {
    val status = deviceStatus(deviceState, devices, activeDeviceIndex)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(status.icon, contentDescription = null, tint = status.color)
            Text(status.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageSelector(
    currentLanguage: String,
    onLanguageChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(stringResource(R.string.language_label), style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                value = when (currentLanguage) {
                    LocaleManager.LANGUAGE_RUSSIAN -> stringResource(R.string.language_russian)
                    LocaleManager.LANGUAGE_ENGLISH -> stringResource(R.string.language_english)
                    else -> stringResource(R.string.language_system)
                },
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.language_system)) },
                    onClick = {
                        onLanguageChange(LocaleManager.LANGUAGE_SYSTEM)
                        expanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.language_russian)) },
                    onClick = {
                        onLanguageChange(LocaleManager.LANGUAGE_RUSSIAN)
                        expanded = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.language_english)) },
                    onClick = {
                        onLanguageChange(LocaleManager.LANGUAGE_ENGLISH)
                        expanded = false
                    }
                )
            }
        }
    }
}

fun applyLocale(context: Context, languageCode: String) {
    val locale = LocaleManager.getLocale(languageCode)
    LocaleManager.setLocale(context, languageCode)

    // For Android 13+, use the new LocaleManager API
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val localeManager = context.getSystemService(Context.LOCALE_SERVICE) as android.app.LocaleManager
        localeManager.applicationLocales = android.os.LocaleList(locale)
    }
}

private const val VAULT_NONE = 0
private const val VAULT_EXPORT = 1
private const val VAULT_IMPORT = 2

private fun vaultFileName(): String =
    "peachy-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.${VaultManager.FILE_EXTENSION}"
