package com.freqcast.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.freqcast.R
import com.freqcast.data.AlarmRepository
import com.freqcast.data.RadioStationRepository
import com.freqcast.ui.theme.FreqcastTheme
import com.freqcast.ui.theme.Spacing
import com.freqcast.ui.theme.card_border
import com.freqcast.ui.theme.card_surface
import com.freqcast.ui.theme.freqcastGradientBackground
import com.freqcast.ui.theme.glass_accent
import com.freqcast.ui.theme.text_hint
import com.freqcast.ui.theme.text_primary

class AlarmEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val alarmRepository = AlarmRepository.create(this)
        val stationRepository = RadioStationRepository.create(this)
        val editingAlarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L).takeIf { it != -1L }
        val viewModelFactory = AlarmEditViewModel.provideFactory(alarmRepository, stationRepository, editingAlarmId)

        setContent {
            FreqcastTheme {
                val viewModel: AlarmEditViewModel = viewModel(factory = viewModelFactory)
                AlarmEditScreen(
                    viewModel = viewModel,
                    onDone = {
                        setResult(RESULT_OK)
                        finish()
                    },
                    onBackClick = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditScreen(
    viewModel: AlarmEditViewModel,
    onDone: () -> Unit,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var timePickerOpen by remember { mutableStateOf(false) }
    var stationMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AlarmEditEvent.Saved -> {
                    val alarm = event.alarm
                    if (alarm.enabled && alarm.streamUrl != null) {
                        val scheduled = AlarmScheduler.schedule(context, alarm.id, alarm.hour, alarm.minute)
                        if (scheduled) {
                            Toast
                                .makeText(
                                    context,
                                    context.getString(
                                        R.string.alarm_enabled_toast,
                                        String.format("%02d:%02d", alarm.hour, alarm.minute),
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                        } else {
                            Toast
                                .makeText(
                                    context,
                                    context.getString(R.string.alarm_permission_needed),
                                    Toast.LENGTH_LONG,
                                ).show()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        }
                    } else {
                        AlarmScheduler.cancel(context, alarm.id)
                        Toast
                            .makeText(
                                context,
                                context.getString(R.string.alarm_disabled_toast),
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                    onDone()
                }

                is AlarmEditEvent.Deleted -> {
                    AlarmScheduler.cancel(context, event.alarm.id)
                    onDone()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (uiState.isEditing) R.string.alarm_edit_title else R.string.alarm_new_title,
                        ),
                    )
                },
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
                    Text(stringResource(R.string.alarm_enabled), color = text_primary)
                    Switch(
                        checked = uiState.enabled,
                        onCheckedChange = viewModel::onEnabledChange,
                        enabled = uiState.stations.isNotEmpty(),
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

            if (uiState.stations.isEmpty()) {
                Text(stringResource(R.string.alarm_no_stations), color = text_hint)
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = card_surface),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp).clickable { timePickerOpen = true },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.alarm_time), color = text_primary)
                        Text(String.format("%02d:%02d", uiState.hour, uiState.minute), color = glass_accent)
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp)) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = card_surface),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().clickable { stationMenuOpen = true },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.alarm_station), color = text_primary)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    uiState.selectedStation?.name ?: stringResource(R.string.alarm_select_station),
                                    color = glass_accent,
                                )
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = glass_accent)
                            }
                        }
                    }
                    DropdownMenu(expanded = stationMenuOpen, onDismissRequest = { stationMenuOpen = false }) {
                        uiState.stations.forEach { station ->
                            DropdownMenuItem(
                                text = { Text(station.name) },
                                onClick = {
                                    viewModel.onStationSelected(station)
                                    stationMenuOpen = false
                                },
                            )
                        }
                    }
                }

                if (timePickerOpen) {
                    val timePickerState =
                        rememberTimePickerState(
                            initialHour = uiState.hour,
                            initialMinute = uiState.minute,
                            is24Hour = true,
                        )
                    // The clock-dial TimePicker is taller than a typical landscape phone screen
                    // and isn't scrollable inside this AlertDialog, so it gets clipped there -
                    // fall back to the compact TimeInput text fields below the height threshold
                    // Material3's own TimePickerDialog uses for the same decision.
                    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
                    AlertDialog(
                        onDismissRequest = { timePickerOpen = false },
                        containerColor = card_surface,
                        titleContentColor = text_primary,
                        title = { Text(stringResource(R.string.alarm_time)) },
                        text = {
                            if (screenHeightDp > TimePickerDialogDefaults.MinHeightForTimePicker) {
                                TimePicker(state = timePickerState)
                            } else {
                                TimeInput(state = timePickerState)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.onTimeChange(timePickerState.hour, timePickerState.minute)
                                timePickerOpen = false
                            }) { Text(stringResource(R.string.save)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { timePickerOpen = false }) { Text(stringResource(R.string.close)) }
                        },
                    )
                }

                TextButton(
                    onClick = { viewModel.save() },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(stringResource(R.string.save), color = glass_accent)
                }
            }

            if (uiState.isEditing) {
                TextButton(
                    onClick = { viewModel.delete() },
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
