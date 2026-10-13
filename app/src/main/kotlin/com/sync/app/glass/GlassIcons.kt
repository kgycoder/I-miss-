package com.sync.app.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser

/** Same vector data as the original DOM icons (index.html), so the look of each icon is unchanged. */
internal object GlassIcons {
    private fun p(d: String): Path = PathParser().parsePathString(d).toPath()

    val Home by lazy { listOf(p("M2 8L10 2l8 6V18H13V13H7v5H2V8Z")) }
    val Search by lazy { listOf(p("M3 9a6 6 0 1 0 12 0a6 6 0 1 0 -12 0Z"), p("M13.5 13.5L18 18")) }
    val Fav by lazy { listOf(p("M10 16.5S3 12 3 7.5a3.5 3.5 0 0 1 7-1A3.5 3.5 0 0 1 17 7.5C17 12 10 16.5 10 16.5z")) }
    val Queue by lazy { listOf(p("M3 5L17 5"), p("M3 10L12 10"), p("M3 15L15 15")) }
    val Playlists by lazy {
        listOf(
            p("M3.5 2h4A1.5 1.5 0 0 1 9 3.5v4A1.5 1.5 0 0 1 7.5 9h-4A1.5 1.5 0 0 1 2 7.5v-4A1.5 1.5 0 0 1 3.5 2Z"),
            p("M12.5 2h4A1.5 1.5 0 0 1 18 3.5v4A1.5 1.5 0 0 1 16.5 9h-4A1.5 1.5 0 0 1 11 7.5v-4A1.5 1.5 0 0 1 12.5 2Z"),
            p("M3.5 11h4A1.5 1.5 0 0 1 9 12.5v4A1.5 1.5 0 0 1 7.5 18h-4A1.5 1.5 0 0 1 2 16.5v-4A1.5 1.5 0 0 1 3.5 11Z"),
            p("M11 14.5h7"), p("M14.5 11v7")
        )
    }
    val Nav by lazy { listOf(Home, Search, Fav, Queue, Playlists) }

    // Mini player (viewBox 0 0 512 512 / 15 / 12)
    val PlayFill by lazy { p("M133 440a35.37 35.37 0 0 1-17.5-4.67c-12-6.8-19.46-20-19.46-34.33V111c0-14.37 7.46-27.53 19.46-34.33a35.13 35.13 0 0 1 35.77.45l247.85 148.36a36 36 0 0 1 0 61l-247.89 148.4A35.5 35.5 0 0 1 133 440") }
    val PauseFill by lazy { p("M118 116C118 91 131 80 154 80H202C225 80 238 91 238 116V396C238 421 225 432 202 432H154C131 432 118 421 118 396ZM274 116C274 91 287 80 310 80H358C381 80 394 91 394 116V396C394 421 381 432 358 432H310C287 432 274 421 274 396Z") }
    val NextFill by lazy { p("m30.71 229.47 188.87-113a30.54 30.54 0 0 1 31.09-.39 33.74 33.74 0 0 1 16.76 29.47v79.05l180.72-108.16a30.54 30.54 0 0 1 31.09-.39A33.74 33.74 0 0 1 496 145.52v221A33.73 33.73 0 0 1 479.24 396a30.54 30.54 0 0 1-31.09-.39L267.43 287.4v79.08A33.73 33.73 0 0 1 250.67 396a30.54 30.54 0 0 1-31.09-.39l-188.87-113a31.27 31.27 0 0 1 0-53z") }
    val Heart by lazy { p("M7.5 12.5S2 8.8 2 5.5a3 3 0 0 1 5.5-1.65A3 3 0 0 1 13 5.5C13 8.8 7.5 12.5 7.5 12.5z") }
    val Plus by lazy { listOf(p("M5.5 1L5.5 10"), p("M1 5.5L10 5.5")) }
    val Check by lazy { listOf(p("M2 6L5 9L9 3")) }
    val Close by lazy { listOf(p("M1.5 1.5L10.5 10.5"), p("M10.5 1.5L1.5 10.5")) }
}

internal fun DrawScope.strokeIcon(paths: List<Path>, viewBox: Float, color: Color, width: Float) {
    val k = size.minDimension / viewBox
    scale(k, k, pivot = Offset.Zero) {
        paths.forEach {
            drawPath(it, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

internal fun DrawScope.fillIcon(path: Path, viewBox: Float, color: Color) {
    val k = size.minDimension / viewBox
    scale(k, k, pivot = Offset.Zero) { drawPath(path, color) }
}
