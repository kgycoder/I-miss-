package com.sync.app.glass

import android.webkit.WebView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.roundToInt

/** SYNC is an always-dark UI → the dark-theme values of Kyant's catalog are used verbatim. */
val LocalGlassAccent = androidx.compose.runtime.staticCompositionLocalOf { Color(0xFFFA2D5A) }

internal object GlassTheme {
    val Accent = Color(0xFF0091FF)
    val Container = Color(0xFF121212).copy(0.4f)
    val Content = Color.White
    val Danger = Color(0xFFFF453A)
}

/**
 * Layer 0 : the WebView (frontend UI, unchanged) recorded by `layerBackdrop`
 * Layer 1 : Kyant `drawBackdrop` glass surfaces + native controls, placed at the rects the DOM reports.
 */
@Composable
fun GlassRoot(webView: WebView, c: GlassController) {
    // Lazy backdrop: the WebView is re-recorded for the glass ONLY inside the (padded) regions where glass is
    // actually shown, and not at all while no glass is visible (Now Playing, etc.).
    val recorder = remember(c) { RegionRecorder(c) }
    val backdrop = rememberLayerBackdrop(onDraw = recorder.onDraw)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }

        val s = c.state
        CompositionLocalProvider(LocalGlassAccent provides Color(s.accent)) {
        val dim = s.dialog != null || s.confirm != null || s.add != null
        val chromeAlpha by animateFloatAsState(if (dim) 0.45f else 1f, label = "chromeDim")

        Box(if (chromeAlpha < 0.999f) Modifier.fillMaxSize().graphicsLayer { alpha = chromeAlpha } else Modifier.fillMaxSize()) {
            s.nav?.let { NavGlass(it, backdrop, c) }
            s.bar?.let { BarGlass(it, backdrop, c) }
            s.search?.let { SearchGlass(it, backdrop, c) }
        }
        s.ctx?.let { ContextMenuGlass(it, backdrop, c) }
        s.dialog?.let { DialogGlass(it, backdrop, c) }
        s.confirm?.let { ConfirmGlass(it, backdrop, c) }
        s.add?.let { AddModalGlass(it, backdrop, c) }
        }
    }
}

/** Search capsule = CSS `position: sticky`, evaluated natively from the WebView's own scrollY (no JS round-trip). */
internal fun searchTop(c: GlassController, s: SearchState, d: Float): Float =
    maxOf(s.pin, s.rect.y - c.cssScroll(d))

/**
 * Where layout last placed the search capsule (px). Plain field on purpose: written during placement, read during
 * draw, no snapshot traffic.
 *
 * Layout (and therefore hit-testing / IME anchoring) can only know the scroll offset of the *previous* frame,
 * because the WebView syncs its offset while it is drawn, which is after layout. The visible glass is therefore
 * moved in the draw phase by the difference between the live offset and this placed position (see [searchDrawDelta]),
 * so it is drawn at exactly the position of the page content of the same frame.
 */
internal class SearchPlacement { @JvmField var topPx: Int = 0 }

internal fun Modifier.searchRect(c: GlassController, s: SearchState, placed: SearchPlacement): Modifier = this
    .offset {
        val top = (searchTop(c, s, density) * density).roundToInt()
        placed.topPx = top
        IntOffset((s.rect.x * density).roundToInt(), top)
    }
    .size(s.rect.w.dp, s.rect.h.dp)

/** Draw-phase correction (px) between the live sticky position and the position layout placed the capsule at. */
internal fun DrawScope.searchDrawDelta(c: GlassController, s: SearchState, placed: SearchPlacement): Float =
    searchTop(c, s, density) * density - placed.topPx

internal fun Modifier.cssRect(r: CssRect): Modifier = this
    .offset { IntOffset((r.x * density).roundToInt(), (r.y * density).roundToInt()) }
    .size(r.w.dp, r.h.dp)

/** Pop-in spring used by dialogs / menus (scale + alpha through the backdrop's own layerBlock). */
@Composable
internal fun rememberPopIn(): Animatable<Float, *> {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, spring(0.72f, 420f, 0.001f)) }
    return a
}

internal fun popScale(p: Float) = lerp(0.90f, 1f, p)

/** clickable without the default (rectangular debug) indication — that was the "box frame" flash. */
internal fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick)

/**
 * Records the WebView into the Kyant backdrop layer only where it can be sampled by a glass surface.
 * Each glass rect is inflated by [PAD_DP] so blur radius, lens refraction and press-scale never sample outside
 * the recorded area → pixel-identical result, but the second WebView draw covers a fraction of the screen.
 */
internal class RegionRecorder(private val c: GlassController) {
    private var cachedFor: GlassState? = null
    private var cachedDensity = 0f
    private var cachedScroll = Float.NaN
    private var cachedPath: Path? = null

    val onDraw: ContentDrawScope.() -> Unit = {
        val st = c.state // snapshot read → redraw when the set of glass surfaces / rects changes
        val sc = if (st.search != null) c.cssScroll(density) else 0f
        if (cachedFor !== st || cachedDensity != density || cachedScroll != sc) {
            cachedPath = buildPath(st, density, sc)
            cachedFor = st; cachedDensity = density; cachedScroll = sc
        }
        val path = cachedPath
        val outer = this
        if (path != null) clipPath(path) { outer.drawContent() }
    }

    private fun buildPath(s: GlassState, d: Float, scroll: Float): Path? {
        val pad = PAD_DP * d
        val rects = listOfNotNull(
            s.nav?.rect, s.bar?.rect, s.search?.let { it.rect.copy(y = maxOf(it.pin, it.rect.y - scroll)) }, s.ctx?.rect, s.dialog?.rect, s.confirm?.rect, s.add?.rect
        )
        if (rects.isEmpty()) return null
        return Path().apply {
            rects.forEach { r ->
                addRect(Rect(r.x * d - pad, r.y * d - pad, (r.x + r.w) * d + pad, (r.y + r.h) * d + pad))
            }
        }
    }

    private companion object { const val PAD_DP = 96f }
}
