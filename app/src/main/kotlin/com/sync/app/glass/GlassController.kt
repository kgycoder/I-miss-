package com.sync.app.glass

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * Frontend(WebView) <-> Native(Compose) channel.
 *
 *  JS  -> Kotlin : window.GlassBridge.push(json)   (DOM state mirror, rects in CSS px)
 *  Kotlin -> JS  : window.__glassCmd({...})        (taps / text input forwarded to the existing app.js functions)
 *
 * The WebView + app.js + AndroidBridge keep running exactly as before; they remain the single source of
 * truth for playback / search / playlists. Compose only renders the glass surfaces and their controls.
 */
class GlassController(private val webView: WebView) {

    var state by mutableStateOf(GlassState())
        private set

    /**
     * WebView.getScrollY() (px) at the last onScrollChange. This is only the *invalidation signal*:
     * reading it inside a layout/draw block makes that block run again when the page scrolls.
     * The value that is actually used for positioning is read live from the WebView (see [cssScroll]).
     */
    var nativeScrollPx by mutableIntStateOf(0)
        private set

    /** Bumped once a scroll has settled, so every layout/draw block that reads [cssScroll] runs once more and
     *  re-reads the final offset (a rapid burst of scroll/reflow could otherwise leave the last frame's
     *  invalidation behind, which showed up as a capsule that stayed misaligned until the next scroll). */
    var settleTick by mutableIntStateOf(0)
        private set

    /** CSS px. Non-zero only if the page (JS) reported a settled scroll offset that differs from WebView.getScrollY(). */
    private var settleBias = 0f
    private var lastNativeY = 0
    private val settle = Runnable { settleTick++ }

    fun onNativeScroll(y: Int) {
        if (y != lastNativeY) { lastNativeY = y; settleBias = 0f }   // new scroll → drop any stale correction
        nativeScrollPx = y
        main.removeCallbacks(settle)
        main.postDelayed(settle, 120)
        main.postDelayed(settle, 400)
    }

    /**
     * Called once by glass-bridge.js ~150ms after the last scroll event (one call per scroll gesture, not per event).
     * [y] is window.scrollY (CSS px) = ground truth of what the page is showing. If WebView.getScrollY() disagrees,
     * the glass follows the page, not the stale Java value.
     */
    @JavascriptInterface
    fun settled(y: Float) {
        main.post {
            val d = webView.resources.displayMetrics.density
            val diff = y - webView.scrollY / d
            settleBias = if (kotlin.math.abs(diff) > 1f) diff else 0f
            settleTick++
        }
    }

    /** Kept for backward compatibility with older bridge scripts. Per-scroll-event JS→Java calls are synchronous
     *  IPC that stall the renderer's main thread (= scroll jank), so glass-bridge.js no longer calls this. */
    @JavascriptInterface
    fun scroll(y: Float) { /* no-op */ }

    /**
     * Current document scroll in CSS px (== dp).
     *
     * Subscribes to [nativeScrollPx] / [settleTick] (so callers re-run on scroll and once more after it settles) but
     * returns WebView.getScrollY() *at call time*.
     * WebView syncs its scroll offset inside its own onDraw; a draw block that runs after the WebView was drawn
     * (glass is a later sibling) therefore sees the offset of the frame that is being produced right now,
     * instead of the previous frame's value that a state snapshot would give.
     */
    fun cssScroll(density: Float): Float {
        @Suppress("UNUSED_VARIABLE") val a = nativeScrollPx
        @Suppress("UNUSED_VARIABLE") val b = settleTick
        return webView.scrollY / density + settleBias
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastJson = ""

    @JavascriptInterface
    fun push(json: String) {
        if (json == lastJson) return
        lastJson = json
        // parse on the JS bridge thread; only the already-built immutable state hops to the UI thread
        val parsed = try { GlassState.parse(json) } catch (_: Exception) { return }
        main.post { state = parsed }
    }

    fun cmd(build: JSONObject.() -> Unit) {
        val js = JSONObject().apply(build).toString()
        webView.post { webView.evaluateJavascript("window.__glassCmd&&window.__glassCmd($js)", null) }
    }
}
