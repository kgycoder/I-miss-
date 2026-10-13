package com.sync.app.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.shapes.Capsule
import com.sync.app.glass.kyant.InteractiveHighlight

/**
 * A text field that mirrors a hidden DOM <input>. The DOM input stays the source of truth for app.js
 * (oninput → suggestions, Enter → doSearch …); every edit here is forwarded to it.
 */
@Composable
internal fun MirroredField(
    inputId: String,
    domValue: String,
    domFocused: Boolean,
    placeholder: String,
    c: GlassController,
    imeAction: ImeAction,
    onAction: () -> Unit,
    fontSize: TextUnit = 15.sp,
    modifier: Modifier = Modifier
) {
    var text by remember(inputId) { mutableStateOf(domValue) }
    var nativeFocus by remember(inputId) { mutableStateOf(false) }
    val requester = remember(inputId) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val accent = LocalGlassAccent.current

    // DOM -> native (only while the user is not typing here, so keystrokes are never overwritten by stale pushes)
    LaunchedEffect(domValue) { if (!nativeFocus && domValue != text) text = domValue }
    // DOM .focus() (e.g. plDialog / plAddModalOpen) -> raise native focus + keyboard
    LaunchedEffect(domFocused) { if (domFocused && !nativeFocus) runCatching { requester.requestFocus() } }

    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            c.cmd { put("t", "text"); put("id", inputId); put("v", it) }
        },
        modifier = modifier
            .focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused != nativeFocus) {
                    nativeFocus = it.isFocused
                    c.cmd { put("t", "focus"); put("id", inputId); put("on", it.isFocused) }
                }
            },
        singleLine = true,
        textStyle = TextStyle(GlassTheme.Content, fontSize),
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = {
            c.cmd { put("t", "enter"); put("id", inputId) }
            onAction()
            focusManager.clearFocus()
        }),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    BasicText(placeholder, style = TextStyle(GlassTheme.Content.copy(0.45f), fontSize), maxLines = 1)
                }
                inner()
            }
        }
    )
}

/**
 * Wraps a backdrop and shifts what it samples by [delta] (px, evaluated at draw time).
 *
 * The capsule's drawn position = placed position + delta. Kyant derives the sampling offset from the *placed*
 * layout coordinates, so without this the refracted content would trail the glass by that same delta.
 */
private class ScrollCompensatedBackdrop(
    private val inner: Backdrop,
    private val delta: DrawScope.() -> Float
) : Backdrop {

    override val isCoordinatesDependent: Boolean get() = inner.isCoordinatesDependent

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val dy = delta(this)
        withTransform({ translate(0f, -dy) }) {
            with(inner) { drawBackdrop(density, coordinates, layerBlock) }
        }
    }
}

@Composable
internal fun SearchGlass(s: SearchState, backdrop: Backdrop, c: GlassController) {
    val scope = rememberCoroutineScope()
    val highlight = remember(scope) { InteractiveHighlight(scope) }
    val accent = LocalGlassAccent.current
    val placed = remember { SearchPlacement() }
    val liveBackdrop = remember(backdrop, c, s, placed) {
        ScrollCompensatedBackdrop(backdrop) { searchDrawDelta(c, s, placed) }
    }
    Row(
        Modifier
            .searchRect(c, s, placed)
            // Everything below (glass surface, highlight, shadow, field) is drawn at the live scroll position.
            .drawWithContent {
                val dy = searchDrawDelta(c, s, placed)
                translate(top = dy) { this@drawWithContent.drawContent() }
            }
            .drawBackdrop(
                backdrop = liveBackdrop,
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
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(18.dp)) {
            strokeIcon(GlassIcons.Search, 20f, accent, 1.7f)
        }
        Box(Modifier.weight(1f).padding(start = 12.dp)) {
            MirroredField(
                inputId = s.inputId, domValue = s.value, domFocused = s.focused,
                placeholder = s.placeholder, c = c, imeAction = ImeAction.Search, onAction = {},
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
