package com.urlradiodroid.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import com.urlradiodroid.R
import com.urlradiodroid.ui.theme.Spacing
import com.urlradiodroid.ui.theme.card_surface
import com.urlradiodroid.ui.theme.text_primary
import com.urlradiodroid.util.EmojiGenerator

/**
 * Lets the user set [com.urlradiodroid.data.RadioStation.customIcon] to one of a curated set of
 * emoji, or an image picked via the system photo picker. Saving the picked image to app-private
 * storage is the caller's job ([onImagePicked] only hands back the source [Uri]) since it needs a
 * [android.content.Context] and blocking file IO, neither of which this stateless dialog has.
 */
@Composable
fun StationIconPickerDialog(
    hasCustomIcon: Boolean,
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit,
    onImagePicked: (Uri) -> Unit,
    onRemoveIcon: () -> Unit,
) {
    val pickImage =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) onImagePicked(uri)
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = card_surface,
        titleContentColor = text_primary,
        textContentColor = text_primary,
        title = { Text(stringResource(R.string.station_icon)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = stringResource(R.string.choose_emoji),
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    EmojiGenerator.pickerEmojis.forEach { emoji ->
                        Text(
                            text = emoji,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier =
                                Modifier
                                    .clip(CircleShape)
                                    .clickable { onEmojiSelected(emoji) }
                                    .padding(Spacing.xs),
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = Spacing.xs))

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                pickImage.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            }.padding(vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Icon(imageVector = Icons.Default.Upload, contentDescription = null)
                    Text(stringResource(R.string.upload_image))
                }

                if (hasCustomIcon) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onRemoveIcon() }
                                .padding(vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(stringResource(R.string.remove_icon), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
