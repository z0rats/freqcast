package com.freqcast.ui.theme

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush

/** The app's "retro radio" charcoal-to-amber background gradient, applied by every top-level screen. */
fun Modifier.freqcastGradientBackground(): Modifier =
    background(
        Brush.verticalGradient(listOf(background_gradient_start, background_gradient_mid, background_gradient_end)),
    )
