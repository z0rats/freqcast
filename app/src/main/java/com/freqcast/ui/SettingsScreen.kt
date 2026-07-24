package com.freqcast.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import com.freqcast.util.StationShare
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = RadioStationRepository.create(this)
        val viewModelFactory = SettingsViewModel.provideFactory(repository)

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
    val settingsStore = remember { SettingsStore(context) }
    var warnOnMeteredConnection by remember { mutableStateOf(settingsStore.warnOnMeteredConnection) }
    var timeshiftBufferSizeMb by remember { mutableStateOf(settingsStore.timeshiftBufferSizeMb) }
    var bufferSizeMenuOpen by remember { mutableStateOf(false) }

    val exportChooserTitle = stringResource(R.string.export_stations)
    val onExportClick: () -> Unit = {
        coroutineScope.launch {
            try {
                val json = viewModel.exportStationsJson()
                StationShare.share(context, json, exportChooserTitle, "freqcast-stations")
            } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.export_error), Toast.LENGTH_SHORT).show()
            }
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
                    val result = viewModel.importStations(content)
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
                    .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
                        checked = warnOnMeteredConnection,
                        onCheckedChange = {
                            warnOnMeteredConnection = it
                            settingsStore.warnOnMeteredConnection = it
                        },
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
                                TimeshiftBufferSize.entries.find { it.mb == timeshiftBufferSizeMb }
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
                                timeshiftBufferSizeMb = option.mb
                                settingsStore.timeshiftBufferSizeMb = option.mb
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
        }
    }
}
