package com.alphamessenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp

/**
 * The app's slider: a round thumb on an unbroken track. The library's own
 * since material3 1.3 is a tall bar with gaps around it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlphaSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    /** A track of its own, drawn instead of the plain one. */
    track: (@Composable () -> Unit)? = null,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        thumb = {
            Box(
                Modifier.size(16.dp).shadow(2.dp, CircleShape)
                    .background(if (enabled) colors.thumbColor else colors.disabledThumbColor, CircleShape),
            )
        },
        track = { state ->
            if (track != null) track() else SliderDefaults.Track(
                sliderState = state,
                colors = colors,
                enabled = enabled,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
                modifier = Modifier.height(4.dp),
            )
        },
    )
}
