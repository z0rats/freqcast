package com.freqcast.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drives a horizontal swipe-to-reveal gesture for a list row (here: [com.freqcast.ui.components.StationItem]'s
 * edit/share/delete row). Dragging left past half of [maxOffsetPx] (a negative value - the card
 * moves left to reveal actions sitting behind it) reveals; dragging back, tapping a revealed
 * action, or tapping the card itself closes.
 *
 * Same shape as [DragDropState] - a state holder + `remember`/`Modifier` pair - for the same
 * reason: this used to be drag-threshold math and an `Animatable` inlined directly into
 * [com.freqcast.ui.components.StationItem], alongside a scripted one-shot tutorial animation and
 * the card's own status-text formatting. Pulling the gesture out leaves [StationItem] with only
 * the tutorial script (itself expressed as calls into this state's public [revealAnimated]/
 * [closeAnimated]) and presentation.
 */
class SwipeRevealState internal constructor(
    private val offsetAnim: Animatable<Float, AnimationVector1D>,
    private val scope: CoroutineScope,
    private val maxOffsetPx: Float,
) {
    var isRevealed by mutableStateOf(false)
        private set

    /** Current horizontal offset in px (0 = closed, negative = revealed/dragging open). */
    val offsetPx: Float get() = offsetAnim.value

    /**
     * Raw finger movement for one drag sample - tracks 1:1 via `snapTo` (not animated) so the card
     * follows the finger without lag; only the release/close/tutorial settle is animated. Mirrors
     * [com.freqcast.ui.components.NowPlayingBottomBar]'s station-switch drag for the same reason.
     */
    fun onDrag(dragAmount: Float) {
        val newOffset = (offsetAnim.value + dragAmount).coerceIn(maxOffsetPx, 0f)
        isRevealed = newOffset < maxOffsetPx / 2
        scope.launch { offsetAnim.snapTo(newOffset) }
    }

    /** Settles fully open or fully closed depending on which side of the threshold the drag ended on. */
    fun onDragEnd() = settle(revealed = offsetAnim.value < maxOffsetPx / 2)

    /** Fire-and-forget close - the shared path for a tapped action, or a card tap while revealed. */
    fun close() = settle(revealed = false)

    private fun settle(revealed: Boolean) {
        isRevealed = revealed
        scope.launch { offsetAnim.animateTo(if (revealed) maxOffsetPx else 0f, tween(300)) }
    }

    /** Suspending open/close pair for the one-shot swipe-hint tutorial, which needs to await each step in turn. */
    suspend fun revealAnimated() {
        isRevealed = true
        offsetAnim.animateTo(maxOffsetPx, tween(400))
    }

    suspend fun closeAnimated() {
        offsetAnim.animateTo(0f, tween(400))
        isRevealed = false
    }
}

@Composable
fun rememberSwipeRevealState(maxOffsetPx: Float): SwipeRevealState {
    val offsetAnim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    return remember(maxOffsetPx) { SwipeRevealState(offsetAnim, scope, maxOffsetPx) }
}

/** Attaches the horizontal swipe-to-reveal gesture; apply to the row's draggable foreground card. */
fun Modifier.swipeRevealTarget(swipeRevealState: SwipeRevealState): Modifier =
    pointerInput(swipeRevealState) {
        detectHorizontalDragGestures(
            onDragEnd = { swipeRevealState.onDragEnd() },
        ) { _, dragAmount ->
            swipeRevealState.onDrag(dragAmount)
        }
    }
