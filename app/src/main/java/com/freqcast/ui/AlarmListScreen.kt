package com.freqcast.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.freqcast.R
import com.freqcast.data.AlarmRepository
import com.freqcast.data.WakeAlarm
import com.freqcast.ui.playback.AlarmStateStore
import com.freqcast.ui.theme.FreqcastTheme
import com.freqcast.ui.theme.Spacing
import com.freqcast.ui.theme.card_border
import com.freqcast.ui.theme.card_surface
import com.freqcast.ui.theme.freqcastGradientBackground
import com.freqcast.ui.theme.glass_accent
import com.freqcast.ui.theme.text_hint
import com.freqcast.ui.theme.text_primary

class AlarmActivity : AppCompatActivity() {
    private val reloadTrigger = mutableIntStateOf(0)

    private val alarmEditLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                reloadTrigger.value++
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = AlarmRepository.create(this)
        val legacyStore = AlarmStateStore(this)
        val viewModelFactory = AlarmListViewModel.provideFactory(repository, legacyStore)

        setContent {
            FreqcastTheme {
                val viewModel: AlarmListViewModel = viewModel(factory = viewModelFactory)
                val reloadCount by reloadTrigger
                LaunchedEffect(reloadCount) { viewModel.loadAlarms() }
                AlarmListScreen(
                    viewModel = viewModel,
                    onBackClick = { finish() },
                    onAddClick = {
                        alarmEditLauncher.launch(Intent(this, AlarmEditActivity::class.java))
                    },
                    onAlarmClick = { alarm ->
                        alarmEditLauncher.launch(
                            Intent(this, AlarmEditActivity::class.java).apply {
                                putExtra(AlarmEditActivity.EXTRA_ALARM_ID, alarm.id)
                            },
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmListScreen(
    viewModel: AlarmListViewModel,
    onBackClick: () -> Unit,
    onAddClick: () -> Unit,
    onAlarmClick: (WakeAlarm) -> Unit,
) {
    val context = LocalContext.current
    val alarms by viewModel.alarms.collectAsState()
    var pendingDelete by remember { mutableStateOf<WakeAlarm?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AlarmListEvent.LegacyAlarmMigrated -> {
                    AlarmScheduler.cancelLegacy(context)
                    val alarm = event.alarm
                    if (alarm.enabled && alarm.streamUrl != null) {
                        AlarmScheduler.schedule(context, alarm.id, alarm.hour, alarm.minute)
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alarms)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddClick,
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.alarm_add))
            }
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .freqcastGradientBackground()
                    .padding(paddingValues)
                    .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (alarms.isEmpty()) {
                Text(stringResource(R.string.alarm_none), color = text_hint)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(alarms, key = { it.id }) { alarm ->
                        AlarmRow(
                            alarm = alarm,
                            onClick = { onAlarmClick(alarm) },
                            onEnabledChange = { checked ->
                                viewModel.setEnabled(alarm, checked)
                                if (checked && alarm.streamUrl != null) {
                                    AlarmScheduler.schedule(context, alarm.id, alarm.hour, alarm.minute)
                                } else {
                                    AlarmScheduler.cancel(context, alarm.id)
                                }
                            },
                            onDeleteClick = { pendingDelete = alarm },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { alarm ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = card_surface,
            titleContentColor = text_primary,
            title = { Text(stringResource(R.string.alarm_delete)) },
            text = { Text(stringResource(R.string.alarm_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAlarm(alarm)
                    AlarmScheduler.cancel(context, alarm.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun AlarmRow(
    alarm: WakeAlarm,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDeleteClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().widthIn(max = 600.dp),
        colors = CardDefaults.cardColors(containerColor = card_surface),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(String.format("%02d:%02d", alarm.hour, alarm.minute), color = text_primary)
                Text(alarm.stationName ?: stringResource(R.string.alarm_select_station), color = text_hint)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = alarm.enabled,
                    onCheckedChange = onEnabledChange,
                    colors =
                        SwitchDefaults.colors(
                            checkedThumbColor = text_primary,
                            checkedTrackColor = glass_accent,
                            uncheckedThumbColor = text_hint,
                            uncheckedTrackColor = card_surface,
                            uncheckedBorderColor = card_border,
                        ),
                )
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.alarm_delete),
                        tint = text_hint,
                    )
                }
            }
        }
    }
}
