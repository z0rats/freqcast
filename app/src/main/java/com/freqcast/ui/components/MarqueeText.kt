package com.freqcast.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

/**
 * Single-line text that scrolls horizontally instead of truncating with an ellipsis when it
 * doesn't fit - station names / track titles. Route every such spot through this composable
 * rather than an inline `Modifier.basicMarquee(...)`: that inline pattern was silently dropped
 * across full-composable-rewrite redesigns (this file's history, plus [com.freqcast.ui.PlaybackScreen]'s
 * `CopyableLabel`), since a fresh rewrite doesn't carry a modifier chain forward the way it
 * would carry forward a call to a shared named composable.
 */
@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Text(
        text = text,
        style = style,
        color = color,
        textAlign = textAlign,
        maxLines = 1,
        modifier = modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
    )
}
