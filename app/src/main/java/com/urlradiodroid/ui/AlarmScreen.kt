package com.urlradiodroid.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.TimePicker
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.urlradiodroid.R
import com.urlradiodroid.data.RadioStation
import com.urlradiodroid.data.RadioStationRepository
import com.urlradiodroid.ui.playback.AlarmStateStore
import com.urlradiodroid.ui.theme.Spacing
import com.urlradiodroid.ui.theme.URLRadioDroidTheme
import com.urlradiodroid.ui.theme.background_gradient_end
import com.urlradiodroid.ui.theme.background_gradient_mid
import com.urlradiodroid.ui.theme.background_gradient_start
import com.urlradiodroid.ui.theme.card_border
import com.urlradiodroid.ui.theme.card_surface
import com.urlradiodroid.ui.theme.glass_accent
import com.urlradiodroid.ui.theme.text_hint
import com.urlradiodroid.ui.theme.text_primary

class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = RadioStationRepository.create(this)
        val viewModelFactory = AlarmViewModel.provideFactory(repository)

        setContent {
            URLRadioDroidTheme {
                val viewModel: AlarmViewModel = viewModel(factory = viewModelFactory)
                LaunchedEffect(Unit) { viewModel.loadStations() }
                AlarmScreen(
                    viewModel = viewModel,
                    onBackClick = { finish() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmScreen(
    viewModel: AlarmViewModel,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val stations by viewModel.stations.collectAsState()
    val stateStore = remember { AlarmStateStore(context) }
    val saved = remember { stateStore.restore() }

    var enabled by remember { mutableStateOf(saved?.enabled ?: false) }
    var hour by remember { mutableStateOf(saved?.hour ?: AlarmStateStore.DEFAULT_HOUR) }
    var minute by remember { mutableStateOf(saved?.minute ?: 0) }
    var selectedStation by remember { mutableStateOf<RadioStation?>(null) }
    var timePickerOpen by remember { mutableStateOf(false) }
    var stationMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(stations) {
        if (selectedStation == null) {
            selectedStation =
                stations.find { it.streamUrl == saved?.streamUrl } ?: stations.firstOrNull()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alarm)) },
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
                    .background(
                        Brush.verticalGradient(
                            listOf(background_gradient_start, background_gradient_mid, background_gradient_end),
                        ),
                    ).padding(paddingValues)
                    .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = card_surface),
                shape = MaterialTheme.shapes.large,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.alarm), color = text_primary)
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        enabled = stations.isNotEmpty(),
                        colors = SwitchDefaults.colors(checkedThumbColor = glass_accent),
                    )
                }
            }

            if (stations.isEmpty()) {
                Text(stringResource(R.string.alarm_no_stations), color = text_hint)
                return@Column
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = card_surface),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.clickable { timePickerOpen = true },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.alarm_time), color = text_primary)
                    Text(String.format("%02d:%02d", hour, minute), color = glass_accent)
                }
            }

            Box {
                Card(
                    colors = CardDefaults.cardColors(containerColor = card_surface),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.clickable { stationMenuOpen = true },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.alarm_station), color = text_primary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                selectedStation?.name ?: stringResource(R.string.alarm_select_station),
                                color = glass_accent,
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = glass_accent)
                        }
                    }
                }
                DropdownMenu(expanded = stationMenuOpen, onDismissRequest = { stationMenuOpen = false }) {
                    stations.forEach { station ->
                        DropdownMenuItem(
                            text = { Text(station.name) },
                            onClick = {
                                selectedStation = station
                                stationMenuOpen = false
                            },
                        )
                    }
                }
            }

            if (timePickerOpen) {
                val timePickerState =
                    rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
                AlertDialog(
                    onDismissRequest = { timePickerOpen = false },
                    containerColor = card_surface,
                    titleContentColor = text_primary,
                    title = { Text(stringResource(R.string.alarm_time)) },
                    text = { TimePicker(state = timePickerState) },
                    confirmButton = {
                        TextButton(onClick = {
                            hour = timePickerState.hour
                            minute = timePickerState.minute
                            timePickerOpen = false
                        }) { Text(stringResource(R.string.save)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { timePickerOpen = false }) { Text(stringResource(R.string.close)) }
                    },
                )
            }

            TextButton(
                onClick = {
                    val station = selectedStation
                    stateStore.save(
                        AlarmStateStore.Alarm(
                            enabled = enabled && station != null,
                            hour = hour,
                            minute = minute,
                            stationName = station?.name,
                            streamUrl = station?.streamUrl,
                        ),
                    )
                    if (enabled && station != null) {
                        val scheduled = AlarmScheduler.schedule(context, hour, minute)
                        if (scheduled) {
                            Toast
                                .makeText(
                                    context,
                                    context.getString(
                                        R.string.alarm_enabled_toast,
                                        String.format("%02d:%02d", hour, minute),
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
                        AlarmScheduler.cancel(context)
                        Toast
                            .makeText(
                                context,
                                context.getString(R.string.alarm_disabled_toast),
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                    onBackClick()
                },
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(R.string.save), color = glass_accent)
            }
        }
    }
}
