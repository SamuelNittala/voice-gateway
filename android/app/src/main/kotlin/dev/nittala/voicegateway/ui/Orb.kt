package dev.nittala.voicegateway.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.nittala.voicegateway.core.ui.VoiceMode
import dev.nittala.voicegateway.ui.theme.rememberReduceMotion
import kotlin.math.min

/**
 * The voice orb. Its shape and motion, not only its colour, say the state:
 * - Idle: a still, dim disc with a slow breath. Paused: two bars. Stopped: a square. Error: an exclamation mark.
 *   Logged out: a hollow dim ring.
 * - Listening, AwaitingAnswer, Capturing: a disc that grows with the mic [level]; AwaitingAnswer adds a double ring.
 * - Transcribing, Sending, Working: a turning arc around the disc (faster for Sending).
 * - Speaking: ripples moving outward.
 * - AwaitingTap, NeedsYou: a dashed, pulsing ring around a disc with a slashed mic line (mic off, look at the phone).
 * With "Remove animations" on, every motion stops at a fixed frame; the mic level still shows.
 * Purely decorative for TalkBack: the caller gives the semantics.
 */
@Composable
fun Orb(mode: VoiceMode, level: Float, modifier: Modifier = Modifier) {
    val still = rememberReduceMotion()
    val transition = rememberInfiniteTransition(label = "orb")
    val spin by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(if (mode == VoiceMode.Sending) 900 else 1800, easing = LinearEasing)),
        label = "spin",
    )
    val pulse by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "pulse",
    )
    val ripple by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "ripple",
    )
    val shownLevel by animateFloatAsState(level, if (still) tween(0) else tween(90), label = "level")
    val colors = MaterialTheme.colorScheme
    val main = when (mode) {
        VoiceMode.Error -> colors.error
        VoiceMode.AwaitingTap, VoiceMode.NeedsYou -> colors.tertiary
        VoiceMode.Idle, VoiceMode.Paused, VoiceMode.Stopped, VoiceMode.LoggedOut -> colors.outline
        else -> colors.primary
    }
    val onMain = colors.surface
    val t = OrbFrame(
        spin = if (still) 45f else spin,
        pulse = if (still) 0.5f else pulse,
        ripple = if (still) 0.5f else ripple,
        level = shownLevel,
    )
    Canvas(modifier) { drawOrb(mode, t, main, onMain) }
}

private data class OrbFrame(val spin: Float, val pulse: Float, val ripple: Float, val level: Float)

private fun DrawScope.drawOrb(mode: VoiceMode, t: OrbFrame, main: Color, onMain: Color) {
    val r = min(size.width, size.height) / 2f * 0.62f
    val c = center
    val fill = Brush.radialGradient(listOf(main, main.copy(alpha = 0.7f)), c, r * 1.2f)
    when (mode) {
        VoiceMode.Idle -> drawCircle(fill, r * (0.94f + 0.04f * t.pulse), c, alpha = 0.6f)
        VoiceMode.Listening, VoiceMode.Capturing, VoiceMode.AwaitingAnswer -> {
            val grow = 0.85f + 0.35f * t.level
            drawCircle(main.copy(alpha = 0.18f), r * (grow + 0.25f), c)
            drawCircle(fill, r * grow, c)
            if (mode == VoiceMode.AwaitingAnswer) {
                drawCircle(main, r * 1.32f, c, style = Stroke(r * 0.04f))
                drawCircle(main, r * 1.44f, c, style = Stroke(r * 0.04f))
            }
        }
        VoiceMode.Transcribing, VoiceMode.Sending, VoiceMode.Working -> {
            drawCircle(fill, r * 0.9f, c, alpha = 0.85f)
            val arc = r * 1.3f
            drawArc(
                main, t.spin, if (mode == VoiceMode.Working) 260f else 110f, false,
                Offset(c.x - arc, c.y - arc), Size(arc * 2, arc * 2), style = Stroke(r * 0.09f, cap = StrokeCap.Round),
            )
        }
        VoiceMode.Speaking -> {
            for (k in 0..2) {
                val phase = (t.ripple + k / 3f) % 1f
                drawCircle(main, r * (1f + 0.6f * phase), c, alpha = (1f - phase) * 0.5f, style = Stroke(r * 0.06f))
            }
            drawCircle(fill, r, c)
        }
        VoiceMode.AwaitingTap, VoiceMode.NeedsYou -> {
            drawCircle(fill, r * 0.9f, c)
            drawCircle(
                main, r * (1.25f + 0.08f * t.pulse), c,
                style = Stroke(r * 0.07f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * 0.2f, r * 0.12f))),
            )
            // Mic-off mark: a slash across the disc.
            drawLine(onMain, Offset(c.x - r * 0.4f, c.y - r * 0.4f), Offset(c.x + r * 0.4f, c.y + r * 0.4f), r * 0.1f, StrokeCap.Round)
        }
        VoiceMode.Held -> {
            drawCircle(fill, r * 0.9f, c, alpha = 0.8f)
            drawCircle(main, r * 1.2f, c, style = Stroke(r * 0.05f))
            for (k in -1..1) drawCircle(onMain, r * 0.09f, Offset(c.x + k * r * 0.3f, c.y))
        }
        VoiceMode.Paused -> {
            drawCircle(fill, r * 0.9f, c, alpha = 0.6f)
            val w = r * 0.14f
            drawLine(onMain, Offset(c.x - w * 1.5f, c.y - r * 0.35f), Offset(c.x - w * 1.5f, c.y + r * 0.35f), w, StrokeCap.Round)
            drawLine(onMain, Offset(c.x + w * 1.5f, c.y - r * 0.35f), Offset(c.x + w * 1.5f, c.y + r * 0.35f), w, StrokeCap.Round)
        }
        VoiceMode.Stopped -> {
            drawCircle(fill, r * 0.9f, c, alpha = 0.6f)
            val s = r * 0.6f
            drawRect(onMain, Offset(c.x - s / 2, c.y - s / 2), Size(s, s))
        }
        VoiceMode.Error -> {
            drawCircle(fill, r * 0.9f, c)
            drawLine(onMain, Offset(c.x, c.y - r * 0.45f), Offset(c.x, c.y + r * 0.1f), r * 0.14f, StrokeCap.Round)
            drawCircle(onMain, r * 0.08f, Offset(c.x, c.y + r * 0.35f))
        }
        VoiceMode.LoggedOut -> drawCircle(main, r * 0.9f, c, alpha = 0.5f, style = Stroke(r * 0.06f))
        VoiceMode.NewWorktree -> {
            // A plus on the disc; it grows with the voice while the flow's mic is on.
            val grow = 0.85f + 0.35f * t.level
            if (t.level > 0f) drawCircle(main.copy(alpha = 0.18f), r * (grow + 0.25f), c)
            drawCircle(fill, r * grow, c)
            val a = r * 0.45f
            drawLine(onMain, Offset(c.x - a, c.y), Offset(c.x + a, c.y), r * 0.14f, StrokeCap.Round)
            drawLine(onMain, Offset(c.x, c.y - a), Offset(c.x, c.y + a), r * 0.14f, StrokeCap.Round)
        }
    }
}
