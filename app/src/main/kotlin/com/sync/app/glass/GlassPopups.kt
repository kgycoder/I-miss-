package com.sync.app.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle

/** Kyant `DialogContent` glass: colorControls + blur 8dp + lens(24dp, 48dp, depthEffect) + plain highlight. */
private fun Modifier.dialogGlass(backdrop: Backdrop, radius: Int, popIn: Float, lensH: Float, lensA: Float): Modifier =
    this.drawBackdrop(
        backdrop = backdrop,
        shape = { RoundedRectangle(radius.dp) },
        effects = {
            colorControls(brightness = 0f, saturation = 1.5f)
            blur(3f.dp.toPx())
            lens(lensH.dp.toPx(), lensA.dp.toPx(), depthEffect = true)
        },
        highlight = { Highlight.Plain },
        layerBlock = {
            val s = popScale(popIn)
            scaleX = s; scaleY = s; alpha = popIn.coerceIn(0f, 1f)
        },
        onDrawSurface = { drawRect(GlassTheme.Container) }
    )

private val NoTouchThrough: Modifier = Modifier.pointerInput(Unit) {
    // consume taps on the glass body so they don't reach the DOM overlay behind
    detectTapGestures { }
}

@Composable
private fun PillButton(label: String, fill: Color, textColor: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(Capsule())
            .background(fill)
            .tap(onClick = onClick)
            .height(48.dp)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) { BasicText(label, style = TextStyle(textColor, 16.sp, FontWeight.Medium), maxLines = 1) }
}

@Composable
private fun InputCapsule(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(Capsule())
            .background(Color.White.copy(0.10f))
            .height(48.dp)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart
    ) { content() }
}

@Composable
internal fun DialogGlass(d: DialogState, backdrop: Backdrop, c: GlassController) {
    val pop = rememberPopIn()
    Box(Modifier.cssRect(d.rect), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.CenterVertically, unbounded = true)
                .dialogGlass(backdrop, 48, pop.value, 24f, 48f)
                .then(NoTouchThrough)
        ) {
            BasicText(
                d.title, Modifier.padding(28.dp, 24.dp, 28.dp, 12.dp),
                style = TextStyle(GlassTheme.Content, 22.sp, FontWeight.Medium)
            )
            InputCapsule(Modifier.padding(24.dp, 6.dp, 24.dp, 12.dp).fillMaxWidth()) {
                MirroredField(
                    "pl-dialog-input", d.value, d.focused, d.placeholder, c,
                    ImeAction.Done, {}, 16.sp, Modifier.fillMaxWidth()
                )
            }
            Row(
                Modifier.padding(24.dp, 12.dp, 24.dp, 24.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PillButton(d.cancelLabel, Color.White.copy(0.12f), GlassTheme.Content, Modifier.weight(1f)) {
                    c.cmd { put("t", "dlg"); put("a", "cancel") }
                }
                PillButton(d.okLabel, LocalGlassAccent.current, Color.White, Modifier.weight(1f)) {
                    c.cmd { put("t", "dlg"); put("a", "ok") }
                }
            }
        }
    }
}

@Composable
internal fun ConfirmGlass(d: ConfirmState, backdrop: Backdrop, c: GlassController) {
    val pop = rememberPopIn()
    Box(Modifier.cssRect(d.rect), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.CenterVertically, unbounded = true)
                .dialogGlass(backdrop, 48, pop.value, 24f, 48f)
                .then(NoTouchThrough)
        ) {
            BasicText(
                d.title, Modifier.padding(28.dp, 24.dp, 28.dp, 8.dp),
                style = TextStyle(GlassTheme.Content, 22.sp, FontWeight.Medium)
            )
            if (d.message.isNotBlank()) {
                BasicText(
                    d.message,
                    Modifier
                        .graphicsLayer(blendMode = BlendMode.Plus)
                        .padding(28.dp, 4.dp, 28.dp, 8.dp),
                    style = TextStyle(GlassTheme.Content.copy(0.68f), 15.sp), maxLines = 5
                )
            }
            Row(
                Modifier.padding(24.dp, 16.dp, 24.dp, 24.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PillButton(d.cancelLabel, Color.White.copy(0.12f), GlassTheme.Content, Modifier.weight(1f)) {
                    c.cmd { put("t", "cfm"); put("a", "cancel") }
                }
                PillButton(d.okLabel, GlassTheme.Danger, Color.White, Modifier.weight(1f)) {
                    c.cmd { put("t", "cfm"); put("a", "ok") }
                }
            }
        }
    }
}

@Composable
internal fun ContextMenuGlass(m: CtxState, backdrop: Backdrop, c: GlassController) {
    val pop = rememberPopIn()
    Box(Modifier.cssRect(m.rect.copy(h = 10f))) {
        Column(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .dialogGlass(backdrop, 28, pop.value, 16f, 32f)
                .then(NoTouchThrough)
                .padding(vertical = 10.dp)
        ) {
            BasicText(
                m.title, Modifier.padding(20.dp, 8.dp, 20.dp, 6.dp),
                style = TextStyle(GlassTheme.Content.copy(0.6f), 12.sp, FontWeight.Medium)
            )
            m.items.forEachIndexed { i, name ->
                MenuRow(name, GlassTheme.Content) { c.cmd { put("t", "ctx"); put("i", i) } }
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(0.5.dp)
                .background(Color.White.copy(0.18f)))
            MenuRow(m.newLabel, LocalGlassAccent.current) { c.cmd { put("t", "ctx"); put("new", true) } }
        }
    }
}

@Composable
private fun MenuRow(label: String, color: Color, onClick: () -> Unit) {
    BasicText(
        label,
        Modifier.fillMaxWidth().tap(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        style = TextStyle(color, 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis
    )
}

@Composable
internal fun AddModalGlass(a: AddModalState, backdrop: Backdrop, c: GlassController) {
    val pop = rememberPopIn()
    Box(Modifier.cssRect(a.rect), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxSize()
                .dialogGlass(backdrop, 40, pop.value, 24f, 44f)
                .then(NoTouchThrough)
        ) {
            Row(
                Modifier.padding(24.dp, 20.dp, 16.dp, 8.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    a.title, Modifier.weight(1f),
                    style = TextStyle(GlassTheme.Content, 22.sp, FontWeight.Medium)
                )
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(Color.White.copy(0.12f))
                        .tap { c.cmd { put("t", "add"); put("a", "close") } },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(12.dp)) {
                        strokeIcon(GlassIcons.Close, 12f, GlassTheme.Content, 1.8f)
                    }
                }
            }
            InputCapsule(Modifier.padding(20.dp, 4.dp, 20.dp, 10.dp).fillMaxWidth()) {
                MirroredField(
                    "pl-add-modal-inp", a.value, a.focused, a.placeholder, c,
                    ImeAction.Search, {}, 15.sp, Modifier.fillMaxWidth()
                )
            }
            if (a.rows.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BasicText(
                        a.hint ?: "",
                        style = TextStyle(GlassTheme.Content.copy(0.6f), 14.sp, textAlign = TextAlign.Center)
                    )
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 14.dp)) {
                    itemsIndexed(a.rows) { i, r -> AddRowItem(i, r, c) }
                }
            }
        }
    }
}

@Composable
private fun AddRowItem(index: Int, r: AddRow, c: GlassController) {
    val art = rememberRemoteImage(r.art)
    val accent = LocalGlassAccent.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(0.08f))) {
            if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(GlassTheme.Content, 14.sp, FontWeight.Medium))
            BasicText(r.channel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = TextStyle(GlassTheme.Content.copy(0.6f), 12.sp))
        }
        BasicText(r.dur, Modifier.padding(end = 10.dp), style = TextStyle(GlassTheme.Content.copy(0.55f), 12.sp))
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(if (r.added) accent.copy(0.30f) else Color.White.copy(0.14f))
                .tap(enabled = !r.added) { c.cmd { put("t", "add"); put("a", "row"); put("i", index) } },
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(12.dp)) {
                strokeIcon(if (r.added) GlassIcons.Check else GlassIcons.Plus, 11f, accent, 1.9f)
            }
        }
    }
}
