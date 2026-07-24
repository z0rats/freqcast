package com.freqcast.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable

/** Shared look for freqcast's outlined form fields (station name/url/description) - dark glass surface, warm text. */
@Composable
fun freqcastFormFieldColors(): TextFieldColors =
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
    )
