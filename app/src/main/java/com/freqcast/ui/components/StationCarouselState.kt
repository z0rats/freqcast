package com.freqcast.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.freqcast.data.RadioStation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drives [NowPlayingBottomBar]'s swipe-between-stations carousel - drag physics (resistance when
 * there's no neighbor to switch to in that direction, clamped overscroll, a threshold past which
 * release slides fully into the neighbor and switches instead of bouncing back). Same shape as
 * [DragDropState] - a state holder + remember/[Modifier] pair - pulled out for the same reason:
 * this used to be resistance/overscroll/threshold math inlined directly into the composable,
 * alongside the raw [androidx.compose.ui.layout.SubcomposeLayout] that renders the 3-card row
 * (which stays in [NowPlayingBottomBar] - rendering, not state, same split [DragDropState] draws
 * with `LazyColumn`).
 */
class StationCarouselState internal constructor(
    private val offsetAnim: Animatable<Float, AnimationVector1D>,
    private val scope: CoroutineScope,
    private val prevStation: RadioStation?,
    private val nextStation: RadioStation?,
    cardWidthPx: Int,
    gapPx: Int,
    private val onSwitchStation: (RadioStation) -> Unit,
    private val switchThresholdPx: Float,
    private val maxDragPx: Float,
    private val overscrollMaxPx: Float,
) {
    private val targetForPrevPx = (cardWidthPx + gapPx).toFloat()
    private val targetForNextPx = -(cardWidthPx + gapPx).toFloat()

    /** Current horizontal offset in px, 0 = centered on the current station. */
    val offsetPx: Float get() = offsetAnim.value

    /** Raw finger movement for one drag sample - tracks 1:1 via `snapTo`, resisted/clamped toward a missing neighbor. */
    fun onDrag(dragAmount: Float) {
        val hasPrev = prevStation != null
        val hasNext = nextStation != null
        val effectiveAmount =
            when {
                dragAmount > 0 -> if (hasPrev) dragAmount else dragAmount * RESISTANCE_FACTOR
                else -> if (hasNext) dragAmount else dragAmount * RESISTANCE_FACTOR
            }
        val newOffset = offsetAnim.value + effectiveAmount
        val clamped =
            when {
                hasPrev && hasNext -> newOffset.coerceIn(-maxDragPx, maxDragPx)
                hasPrev -> newOffset.coerceIn(-overscrollMaxPx, maxDragPx)
                hasNext -> newOffset.coerceIn(-maxDragPx, overscrollMaxPx)
                else -> newOffset.coerceIn(-overscrollMaxPx, overscrollMaxPx)
            }
        scope.launch { offsetAnim.snapTo(clamped) }
    }

    /** Past [switchThresholdPx] toward a real neighbor slides fully into it and switches; otherwise bounces back to 0. */
    fun onDragEnd() {
        val current = offsetAnim.value
        when {
            current > switchThresholdPx && prevStation != null -> slideToNeighbor(targetForPrevPx, prevStation)
            current < -switchThresholdPx && nextStation != null -> slideToNeighbor(targetForNextPx, nextStation)
            else -> scope.launch { offsetAnim.animateTo(0f, BOUNCE_SPEC) }
        }
    }

    private fun slideToNeighbor(
        targetPx: Float,
        neighbor: RadioStation,
    ) {
        scope.launch {
            offsetAnim.animateTo(targetPx, SLIDE_SPEC)
            onSwitchStation(neighbor)
            offsetAnim.snapTo(0f)
        }
    }

    companion object {
        private const val RESISTANCE_FACTOR = 0.3f
        private val BOUNCE_SPEC =
            spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        private val SLIDE_SPEC = tween<Float>(durationMillis = 280)
    }
}

@Composable
fun rememberStationCarouselState(
    station: RadioStation,
    prevStation: RadioStation?,
    nextStation: RadioStation?,
    cardWidthPx: Int,
    gapPx: Int,
    onSwitchStation: (RadioStation) -> Unit,
): StationCarouselState {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    return remember(station.id, prevStation?.id, nextStation?.id, cardWidthPx, gapPx) {
        StationCarouselState(
            offsetAnim = Animatable(0f),
            scope = scope,
            prevStation = prevStation,
            nextStation = nextStation,
            cardWidthPx = cardWidthPx,
            gapPx = gapPx,
            onSwitchStation = onSwitchStation,
            switchThresholdPx = with(density) { 72.dp.toPx() },
            maxDragPx = with(density) { 180.dp.toPx() },
            overscrollMaxPx = with(density) { 48.dp.toPx() },
        )
    }
}

/** Attaches the swipe-between-stations drag gesture; apply to the carousel's pointer-input target. */
fun Modifier.stationCarouselTarget(carouselState: StationCarouselState): Modifier =
    pointerInput(carouselState) {
        detectHorizontalDragGestures(
            onDragEnd = { carouselState.onDragEnd() },
        ) { _, dragAmount ->
            carouselState.onDrag(dragAmount)
        }
    }
