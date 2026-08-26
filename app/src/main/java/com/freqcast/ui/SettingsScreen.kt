package com.freqcast.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.freqcast.R
import com.freqcast.data.RadioStationRepository
import com.freqcast.ui.playback.SettingsStore
import com.freqcast.ui.playback.TimeshiftBufferSize
import com.freqcast.ui.theme.FreqcastTheme
import com.freqcast.ui.theme.Spacing
import com.freqcast.ui.theme.card_border
import com.freqcast.ui.theme.card_surface
import com.freqcast.ui.theme.freqcastGradientBackground
import com.freqcast.ui.theme.glass_accent
import com.freqcast.ui.theme.text_hint
import com.freqcast.ui.theme.text_primary
import com.freqcast.util.BatteryOptimization
import com.freqcast.util.StationShare
import kotlinx.coroutines.launch

/** Selectable app display languages, keyed by BCP-47 tag; `null` follows the system locale. */
private data class LanguageOption(
    val tag: String?,
    val displayName: String,
)

@Composable
private fun languageOptions(): List<LanguageOption> =
    listOf(
        LanguageOption(null, stringResource(R.string.settings_language_system_default)),
        LanguageOption("en", "English"),
        LanguageOption("es", "Español"),
        LanguageOption("ru", "Русский"),
        LanguageOption("zh-CN", "中文"),
    )

/** The installed app's own version name (e.g. "3.4.3"), shown in the Settings footer. */
private fun currentVersionName(context: android.content.Context): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            .versionName
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.orEmpty()

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = RadioStationRepository.create(this)
        val settingsStore = SettingsStore(this)
        val viewModelFactory = SettingsViewModel.provideFactory(repository, settingsStore, currentVersionName(this))

        setContent {
            FreqcastTheme {
                val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
                SettingsScreen(
                    viewModel = viewModel,
                    onBackClick = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    var bufferSizeMenuOpen by remember { mutableStateOf(false) }
    val languageOptions = languageOptions()
    val currentLanguageTag =
        AppCompatDelegate
            .getApplicationLocales()
            .takeIf { !it.isEmpty }
            ?.get(0)
            ?.toLanguageTag()
    var selectedLanguage by
        remember {
            mutableStateOf(languageOptions.find { it.tag == currentLanguageTag } ?: languageOptions.first())
        }
    var languageMenuOpen by remember { mutableStateOf(false) }
    var batteryOptimizationIgnored by
        remember { mutableStateOf(BatteryOptimization.isIgnoringBatteryOptimizations(context)) }
    val batteryOptimizationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // The system dialog reports no reliable result code either way — just re-check state.
            batteryOptimizationIgnored = BatteryOptimization.isIgnoringBatteryOptimizations(context)
        }

    val exportChooserTitle = stringResource(R.string.export_stations)
    val onExportClick: () -> Unit = {
        coroutineScope.launch {
            try {
                val json = viewModel.exportStationsJson()
                if (json == null) {
                    Toast
                        .makeText(context, context.getString(R.string.export_stations_empty), Toast.LENGTH_SHORT)
                        .show()
                } else {
                    StationShare.share(context, json, exportChooserTitle, "freqcast-stations")
                }
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.export_error), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val onRestoreCuratedClick: () -> Unit = {
        coroutineScope.launch {
            val restored = viewModel.restoreCuratedStations(context)
            Toast
                .makeText(context, context.getString(R.string.restore_curated_result, restored), Toast.LENGTH_SHORT)
                .show()
        }
    }

    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                try {
                    val content =
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                            ?: throw java.io.IOException("Cannot open file")
                    val result = viewModel.importStations(context, content)
                    Toast
                        .makeText(
                            context,
                            context.getString(R.string.import_result, result.imported, result.skipped),
                            Toast.LENGTH_LONG,
                        ).show()
                } catch (e: Exception) {
                    Toast.makeText(context, context.getString(R.string.import_error), Toast.LENGTH_SHORT).show()
                }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .freqcastGradientBackground()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp)) {
                Card(
                    onClick = { languageMenuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = card_surface),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_language), color = text_primary)
                            Text(
                                stringResource(R.string.settings_language_description),
                                color = text_hint,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(selectedLanguage.displayName, color = glass_accent)
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = glass_accent)
                        }
                    }
                }
                DropdownMenu(expanded = languageMenuOpen, onDismissRequest = { languageMenuOpen = false }) {
                    languageOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName) },
                            onClick = {
                                selectedLanguage = option
                                languageMenuOpen = false
                                AppCompatDelegate.setApplicationLocales(
                                    if (option.tag == null) {
                                        LocaleListCompat.getEmptyLocaleList()
                                    } else {
                                        LocaleListCompat.forLanguageTags(option.tag)
                                    },
                                )
                            },
                        )
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
                colors = CardDefaults.cardColors(containerColor = card_surface),
                shape = MaterialTheme.shapes.large,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_metered_warning), color = text_primary)
                        Text(
                            stringResource(R.string.settings_metered_warning_description),
                            color = text_hint,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = uiState.warnOnMeteredConnection,
                        onCheckedChange = { viewModel.setWarnOnMeteredConnection(it) },
                        colors =
                            SwitchDefaults.colors(
                                checkedThumbColor = text_primary,
                                checkedTrackColor = glass_accent,
                                uncheckedThumbColor = text_hint,
                                uncheckedTrackColor = card_surface,
                                uncheckedBorderColor = card_border,
                            ),
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp)) {
                Card(
                    onClick = { bufferSizeMenuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = card_surface),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_timeshift_buffer_size), color = text_primary)
                            Text(
                                stringResource(R.string.settings_timeshift_buffer_size_description),
                                color = text_hint,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val selected =
                                TimeshiftBufferSize.entries.find { it.mb == uiState.timeshiftBufferSizeMb }
                                    ?: TimeshiftBufferSize.DEFAULT
                            Text(
                                stringResource(
                                    R.string.settings_timeshift_buffer_size_value,
                                    selected.mb,
                                    selected.approxMinutes,
                                ),
                                color = glass_accent,
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = glass_accent)
                        }
                    }
                }
                DropdownMenu(expanded = bufferSizeMenuOpen, onDismissRequest = { bufferSizeMenuOpen = false }) {
                    TimeshiftBufferSize.entries.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        R.string.settings_timeshift_buffer_size_value,
                                        option.mb,
                                        option.approxMinutes,
                                    ),
                                )
                            },
                            onClick = {
                                viewModel.setTimeshiftBufferSizeMb(option.mb)
                                bufferSizeMenuOpen = false
                            },
                        )
                    }
                }
            }

            Card(
                onClick = onExportClick,
                modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
                colors = CardDefaults.cardColors(containerColor = card_surface),
                shape = MaterialTheme.shapes.large,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.export_stations), color = text_primary)
                }
            }

            Card(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
                colors = CardDefaults.cardColors(containerColor = card_surface),
                shape = MaterialTheme.shapes.large,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.import_stations), color = text_primary)
                }
            }

            Card(
                onClick = onRestoreCuratedClick,
                modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
                colors = CardDefaults.cardColors(containerColor = card_surface),
                shape = MaterialTheme.shapes.large,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_restore_curated), color = text_primary)
                }
            }

            if (!batteryOptimizationIgnored) {
                Card(
                    onClick = {
                        batteryOptimizationLauncher.launch(BatteryOptimization.requestExemptionIntent(context))
                    },
                    modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
                    colors = CardDefaults.cardColors(containerColor = card_surface),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(stringResource(R.string.settings_battery_optimization), color = text_primary)
                            Text(
                                stringResource(R.string.settings_battery_optimization_description),
                                color = text_hint,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            AppVersionFooter(uiState = uiState)
        }
    }
}

@Composable
private fun AppVersionFooter(uiState: SettingsUiState) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp).padding(top = Spacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.settings_version, uiState.currentVersion),
            color = text_hint,
            style = MaterialTheme.typography.bodySmall,
        )
        when (uiState.updateStatus) {
            UpdateStatus.AVAILABLE -> {
                Text(
                    stringResource(R.string.settings_update_available),
                    color = glass_accent,
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier.clickable {
                            uiState.updateUrl?.let { url ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        },
                )
            }

            UpdateStatus.UP_TO_DATE -> {
                Text(
                    stringResource(R.string.settings_up_to_date),
                    color = text_hint,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            UpdateStatus.UNKNOWN -> {}
        }
    }
}
