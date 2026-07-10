package com.urlradiodroid.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.urlradiodroid.ui.theme.glass_accent

/** Small animated "now playing" indicator: bars pulse at their own tempo like a classic radio equalizer. */
@Composable
fun EqualizerBars(
    modifier: Modifier = Modifier,
    color: Color = glass_accent,
    barWidth: Dp = 3.dp,
    height: Dp = 12.dp,
) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val barDurationsMs = listOf(480, 620, 380)

    Row(
        modifier = modifier.height(height),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        barDurationsMs.forEachIndexed { index, durationMs ->
            val fraction by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMs, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "equalizerBar$index",
            )
            Box(
                modifier =
                    Modifier
                        .width(barWidth)
                        .fillMaxHeight(fraction)
                        .clip(RoundedCornerShape(50))
                        .background(color),
            )
        }
    }
}
