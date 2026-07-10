package com.urlradiodroid.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.urlradiodroid.R
import com.urlradiodroid.data.RadioStationRepository
import com.urlradiodroid.ui.components.StationIconPickerDialog
import com.urlradiodroid.ui.components.rememberStationIconBitmap
import com.urlradiodroid.ui.theme.Spacing
import com.urlradiodroid.ui.theme.URLRadioDroidTheme
import com.urlradiodroid.ui.theme.background_gradient_end
import com.urlradiodroid.ui.theme.background_gradient_mid
import com.urlradiodroid.ui.theme.background_gradient_start
import com.urlradiodroid.ui.theme.card_border
import com.urlradiodroid.ui.theme.card_surface
import com.urlradiodroid.ui.theme.card_surface_active
import com.urlradiodroid.ui.theme.text_hint
import com.urlradiodroid.ui.theme.text_primary
import com.urlradiodroid.util.EmojiGenerator
import com.urlradiodroid.util.IconStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val coroutineScope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    var iconPickerOpen by remember { mutableStateOf(false) }

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
                        .padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = 520.dp)
                            .border(width = 1.dp, color = card_border, shape = MaterialTheme.shapes.large),
                    colors = CardDefaults.cardColors(containerColor = card_surface),
                    shape = MaterialTheme.shapes.large,
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(Spacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement =
                            androidx.compose.foundation.layout.Arrangement
                                .spacedBy(Spacing.md),
                    ) {
                        Box(
                            modifier = Modifier.size(96.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.TopStart)
                                        .size(88.dp)
                                        .clip(CircleShape)
                                        .background(card_surface_active)
                                        .clickable { iconPickerOpen = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                val iconBitmap = rememberStationIconBitmap(uiState.customIcon)
                                if (iconBitmap != null) {
                                    Image(
                                        bitmap = iconBitmap.asImageBitmap(),
                                        contentDescription = stringResource(R.string.station_icon),
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Text(
                                        text =
                                            uiState.customIcon?.takeUnless(IconStorage::isImagePath)
                                                ?: EmojiGenerator.getEmojiForStation(uiState.name, uiState.url),
                                        style = MaterialTheme.typography.displayMedium,
                                    )
                                }
                            }
                            // Sibling of the clipped circle above (not a child of it) so this badge isn't
                            // itself cut off by the circle's own clip when it overhangs the circle's edge.
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.station_icon),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }

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
                            shape = MaterialTheme.shapes.medium,
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
                            shape = MaterialTheme.shapes.medium,
                        )

                        Button(
                            onClick = viewModel::save,
                            enabled = !uiState.isSaving,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            if (uiState.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text(stringResource(R.string.save))
                            }
                        }
                    }
                }
            }
        }
    }

    if (iconPickerOpen) {
        StationIconPickerDialog(
            hasCustomIcon = uiState.customIcon != null,
            onDismiss = { iconPickerOpen = false },
            onEmojiSelected = { emoji ->
                viewModel.onEmojiIconSelected(emoji)
                iconPickerOpen = false
            },
            onImagePicked = { uri ->
                coroutineScope.launch {
                    val path = withContext(Dispatchers.IO) { IconStorage.saveImage(context, uri) }
                    if (path != null) viewModel.onImageIconSelected(path)
                    iconPickerOpen = false
                }
            },
            onRemoveIcon = {
                viewModel.onRemoveIcon()
                iconPickerOpen = false
            },
        )
    }
}
