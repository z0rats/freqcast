package com.urlradiodroid.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.urlradiodroid.R
import com.urlradiodroid.data.RadioStationRepository
import com.urlradiodroid.ui.theme.URLRadioDroidTheme
import com.urlradiodroid.ui.theme.background_gradient_end
import com.urlradiodroid.ui.theme.background_gradient_mid
import com.urlradiodroid.ui.theme.background_gradient_start
import com.urlradiodroid.ui.theme.card_border
import com.urlradiodroid.ui.theme.card_surface
import com.urlradiodroid.ui.theme.card_surface_active
import com.urlradiodroid.ui.theme.text_hint
import com.urlradiodroid.ui.theme.text_primary
import java.net.URL

class AddStationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = RadioStationRepository.create(this)
        val editingStationId = intent.getLongExtra(EXTRA_STATION_ID, -1L).takeIf { it != -1L }
        val viewModelFactory = AddStationViewModel.provideFactory(repository, editingStationId)

        setContent {
            URLRadioDroidTheme {
                val viewModel: AddStationViewModel = viewModel(factory = viewModelFactory)
                AddStationScreen(
                    viewModel = viewModel,
                    onSaveSuccess = {
                        setResult(RESULT_OK)
                        finish()
                    },
                    onBackClick = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_STATION_ID = "station_id"

        @JvmStatic
        internal fun isValidUrl(urlString: String): Boolean {
            if (urlString.isBlank()) return false
            if (urlString.any { it.isWhitespace() }) return false
            return try {
                val url = URL(urlString)
                if (url.host.isNullOrBlank()) return false
                urlString.startsWith("http://") || urlString.startsWith("https://")
            } catch (e: Exception) {
                false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStationScreen(
    viewModel: AddStationViewModel,
    onSaveSuccess: () -> Unit,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AddStationEvent.SaveSucceeded -> {
                    val messageRes = if (event.wasEditing) R.string.station_updated else R.string.station_saved
                    Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
                    onSaveSuccess()
                }

                is AddStationEvent.SaveFailed -> {
                    Toast
                        .makeText(
                            context,
                            context.getString(R.string.save_error, event.message ?: ""),
                            Toast.LENGTH_SHORT,
                        ).show()
                }
            }
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    background_gradient_start,
                                    background_gradient_mid,
                                    background_gradient_end,
                                ),
                        ),
                ),
    ) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text =
                                if (uiState.isEditing) {
                                    stringResource(R.string.edit_station)
                                } else {
                                    stringResource(R.string.add_station)
                                },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    colors =
                        androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                            containerColor = card_surface,
                            titleContentColor = text_primary,
                            navigationIconContentColor = text_primary,
                        ),
                )
            },
        ) { paddingValues ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = 520.dp)
                            .border(width = 1.dp, color = card_border, shape = RoundedCornerShape(24.dp)),
                    colors = CardDefaults.cardColors(containerColor = card_surface),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement =
                            androidx.compose.foundation.layout.Arrangement
                                .spacedBy(16.dp),
                    ) {
                        OutlinedTextField(
                            value = uiState.name,
                            onValueChange = viewModel::onNameChange,
                            label = { Text(stringResource(R.string.station_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            isError = uiState.nameErrorRes != null,
                            supportingText =
                                uiState.nameErrorRes?.let {
                                    { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                                },
                            colors =
                                TextFieldDefaults.colors(
                                    focusedContainerColor = card_surface_active,
                                    unfocusedContainerColor = card_surface_active,
                                    focusedTextColor = text_primary,
                                    unfocusedTextColor = text_primary,
                                    focusedLabelColor = text_hint,
                                    unfocusedLabelColor = text_hint,
                                    cursorColor = text_primary,
                                    focusedIndicatorColor = text_hint,
                                    unfocusedIndicatorColor = text_hint.copy(alpha = 0.5f),
                                    errorIndicatorColor = MaterialTheme.colorScheme.error,
                                ),
                            shape = RoundedCornerShape(16.dp),
                        )

                        OutlinedTextField(
                            value = uiState.url,
                            onValueChange = viewModel::onUrlChange,
                            label = { Text(stringResource(R.string.stream_url)) },
                            modifier = Modifier.fillMaxWidth(),
                            isError = uiState.urlErrorRes != null,
                            supportingText =
                                uiState.urlErrorRes?.let {
                                    { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                                },
                            colors =
                                TextFieldDefaults.colors(
                                    focusedContainerColor = card_surface_active,
                                    unfocusedContainerColor = card_surface_active,
                                    focusedTextColor = text_primary,
                                    unfocusedTextColor = text_primary,
                                    focusedLabelColor = text_hint,
                                    unfocusedLabelColor = text_hint,
                                    cursorColor = text_primary,
                                    focusedIndicatorColor = text_hint,
                                    unfocusedIndicatorColor = text_hint.copy(alpha = 0.5f),
                                    errorIndicatorColor = MaterialTheme.colorScheme.error,
                                ),
                            shape = RoundedCornerShape(16.dp),
                        )

                        Button(
                            onClick = viewModel::save,
                            enabled = !uiState.isSaving,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }
}
