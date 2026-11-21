package com.soundsync.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.soundsync.app.ui.theme.CyanAccent
import com.soundsync.app.ui.theme.PurpleAccent
import kotlin.math.sin

/**
 * Animated audio waveform visualizer that reflects real-time audio amplitude.
 */
@Composable
fun AudioVisualizer(
    amplitude: Float,
    isStreaming: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth().height(80.dp)
) {
    val phaseAnim = remember { Animatable(0f) }

    LaunchedEffect(isStreaming) {
        if (isStreaming) {
            phaseAnim.animateTo(
                targetValue = (2 * Math.PI).toFloat(),
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        } else {
            phaseAnim.snapTo(0f)
        }
    }

    val barCount = 28
    val gradientBrush = remember {
        Brush.linearGradient(
            colors = listOf(CyanAccent, PurpleAccent)
        )
    }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barWidth = (width / barCount) * 0.65f
        val spacing = (width / barCount) * 0.35f
        val centerY = height / 2f

        for (i in 0 until barCount) {
            val normX = i.toFloat() / barCount
            val wave = if (isStreaming) {
                sin((normX * 10.0 + phaseAnim.value.toDouble())).toFloat()
            } else {
                0f
            }

            val dynamicAmp = if (isStreaming) {
                val base = maxOf(0.08f, amplitude)
                (base + (wave * 0.15f * amplitude)).coerceIn(0.05f, 1.0f)
            } else {
                0.05f
            }

            val barHeight = height * dynamicAmp
            val x = i * (barWidth + spacing)
            val top = centerY - (barHeight / 2f)

            drawRoundRect(
                brush = gradientBrush,
                topLeft = Offset(x, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}
