package com.sync.app.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.scale
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.sync.app.glass.kyant.InteractiveHighlight

/**
 * Mini player: one Kyant glass capsule. Content = the original SYNC bar:
 * artwork · title/channel · play (white disc) · NEXT (mirrored skip icon). No fav / visualizer / progress bar.
 */
@Composable
internal fun BarGlass(bar: BarState, backdrop: Backdrop, c: GlassController) {
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) }
    val art = rememberRemoteImage(bar.art)

    Box(
        Modifier
            .cssRect(bar.rect)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = {
                    vibrancy()
                    blur(2f.dp.toPx())
                    lens(24f.dp.toPx(), 24f.dp.toPx())
                },
                onDrawSurface = { drawRect(GlassTheme.Container) }
            )
            .then(highlight.modifier)
            .then(highlight.gestureModifier)
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(0.08f))
                    .tap { c.cmd { put("t", "bar"); put("a", "open") } }
            ) {
                if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .tap { c.cmd { put("t", "bar"); put("a", "open") } },
                verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically)
            ) {
                BasicText(
                    bar.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(Color.White.copy(0.96f), 13.5f.sp, FontWeight.SemiBold)
                )
                BasicText(
                    bar.channel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(Color.White.copy(0.55f), 11.sp)
                )
            }
            // play / pause — white disc with dark glyph (as in the original bar)
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(0.92f))
                    .tap { c.cmd { put("t", "bar"); put("a", "play") } },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(16.dp)) {
                    fillIcon(
                        if (bar.playing) GlassIcons.PauseFill else GlassIcons.PlayFill,
                        512f, Color(0xFF08080D)
                    )
                }
            }
            // NEXT track (the source path is a "previous" glyph, mirrored exactly like the original CSS scaleX(-1))
            Box(
                Modifier.size(width = 42.dp, height = 40.dp).tap { c.cmd { put("t", "bar"); put("a", "next") } },
                contentAlignment = Alignment.Center
            ) {
                Canvas(Modifier.size(16.dp)) {
                    scale(-1f, 1f, pivot = center) {
                        fillIcon(GlassIcons.NextFill, 512f, Color.White.copy(0.7f))
                    }
                }
            }
        }
    }
}
