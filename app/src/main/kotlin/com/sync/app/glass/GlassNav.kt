package com.sync.app.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.sync.app.glass.kyant.LiquidBottomTab
import com.sync.app.glass.kyant.LiquidBottomTabs

private val NavLabels = listOf("홈", "검색", "즐겨찾기", "대기열", "플레이리스트")
private val NavViews = listOf("home", "search", "fav", "queue", "playlists")

/** Bottom navigation = Kyant `LiquidBottomTabs` (drag-to-select lens indicator, damped spring, press-scale). */
@Composable
internal fun NavGlass(nav: NavState, backdrop: Backdrop, c: GlassController) {
    // Always the last DOM-reported tab (never a fallback 0, which used to bounce the app to Home
    // whenever the nav was temporarily hidden behind a dialog).
    val active = androidx.compose.runtime.rememberUpdatedState(nav.active)
    val selected = remember { { active.value } }
    val onSelected = remember(c) {
        { index: Int ->
            if (index != active.value) {
                c.cmd { put("t", "nav"); put("v", NavViews[index]) }
            }
        }
    }
    Box(Modifier.cssRect(nav.rect), contentAlignment = Alignment.Center) {
        LiquidBottomTabs(
            selectedTabIndex = selected,
            onTabSelected = onSelected,
            backdrop = backdrop,
            tabsCount = NavLabels.size,
            accentColor = LocalGlassAccent.current,
            modifier = Modifier.fillMaxWidth()
        ) {
            repeat(NavLabels.size) { index ->
                LiquidBottomTab({ onSelected(index) }) {
                    Canvas(Modifier.size(24.dp)) {
                        strokeIcon(GlassIcons.Nav[index], 20f, GlassTheme.Content.copy(0.8f), 1.6f)
                    }
                    BasicText(
                        NavLabels[index],
                        style = TextStyle(GlassTheme.Content.copy(0.8f), 10.sp, FontWeight.Medium),
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip
                    )
                }
            }
        }
    }
}
