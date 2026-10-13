package com.sync.app.glass

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** CSS px == dp inside the Android WebView (viewport width=device-width, scale 1). */
@Immutable
data class CssRect(val x: Float, val y: Float, val w: Float, val h: Float)

@Immutable data class NavState(val rect: CssRect, val active: Int)
@Immutable data class SearchState(
    val rect: CssRect, val pin: Float, val inputId: String, val value: String,
    val placeholder: String, val focused: Boolean
)
@Immutable data class BarState(
    val rect: CssRect, val art: String, val title: String, val channel: String,
    val playing: Boolean, val fav: Boolean, val accent: Long, val progress: Float
)
@Immutable data class CtxState(val rect: CssRect, val title: String, val items: List<String>, val newLabel: String)
@Immutable data class DialogState(
    val rect: CssRect, val title: String, val value: String, val placeholder: String,
    val focused: Boolean, val okLabel: String, val cancelLabel: String
)
@Immutable data class ConfirmState(
    val rect: CssRect, val title: String, val message: String,
    val okLabel: String, val cancelLabel: String
)
@Immutable data class AddRow(
    val art: String, val title: String, val channel: String, val dur: String, val added: Boolean
)
@Immutable data class AddModalState(
    val rect: CssRect, val title: String, val value: String, val placeholder: String,
    val focused: Boolean, val hint: String?, val rows: List<AddRow>
)

@Immutable
data class GlassState(
    val accent: Long = 0xFFFA2D5AL,
    val nav: NavState? = null,
    val search: SearchState? = null,
    val bar: BarState? = null,
    val ctx: CtxState? = null,
    val dialog: DialogState? = null,
    val confirm: ConfirmState? = null,
    val add: AddModalState? = null
) {
    companion object {
        fun parse(json: String): GlassState {
            val o = JSONObject(json)
            return GlassState(
                accent = parseColor(o.optString("acc")),
                nav = o.optJSONObject("nav")?.let { NavState(rect(it), it.optInt("active")) },
                search = o.optJSONObject("search")?.let {
                    SearchState(rect(it), it.optDouble("pin", -10000.0).toFloat(), it.optString("id"), it.optString("value"),
                        it.optString("ph"), it.optBoolean("focused"))
                },
                bar = o.optJSONObject("bar")?.let {
                    BarState(rect(it), it.optString("art"), it.optString("title"), it.optString("ch"),
                        it.optBoolean("playing"), it.optBoolean("fav"), parseColor(it.optString("accent")),
                        it.optDouble("prog", 0.0).toFloat().coerceIn(0f, 100f) / 100f)
                },
                ctx = o.optJSONObject("ctx")?.let {
                    CtxState(rect(it), it.optString("title"), strings(it.optJSONArray("items")), it.optString("new"))
                },
                dialog = o.optJSONObject("dlg")?.let {
                    DialogState(rect(it), it.optString("title"), it.optString("value"), it.optString("ph"),
                        it.optBoolean("focused"), it.optString("ok"), it.optString("cancel"))
                },
                confirm = o.optJSONObject("cfm")?.let {
                    ConfirmState(rect(it), it.optString("title"), it.optString("msg"),
                        it.optString("ok"), it.optString("cancel"))
                },
                add = o.optJSONObject("add")?.let { a ->
                    val rows = a.optJSONArray("rows")
                    AddModalState(rect(a), a.optString("title"), a.optString("value"), a.optString("ph"),
                        a.optBoolean("focused"), if (a.isNull("hint")) null else a.optString("hint"),
                        buildList {
                            if (rows != null) for (i in 0 until rows.length()) {
                                val r = rows.getJSONObject(i)
                                add(AddRow(r.optString("art"), r.optString("t"), r.optString("c"),
                                    r.optString("d"), r.optBoolean("added")))
                            }
                        })
                }
            )
        }

        private fun rect(o: JSONObject): CssRect {
            val r = o.getJSONObject("r")
            return CssRect(r.optDouble("x").toFloat(), r.optDouble("y").toFloat(),
                r.optDouble("w").toFloat(), r.optDouble("h").toFloat())
        }

        private fun strings(a: JSONArray?): List<String> =
            buildList { if (a != null) for (i in 0 until a.length()) add(a.optString(i)) }

        private fun parseColor(s: String): Long = try {
            val c = android.graphics.Color.parseColor(s.ifBlank { "#fa2d5a" })
            c.toLong() and 0xFFFFFFFFL
        } catch (_: Exception) { 0xFFFA2D5AL }
    }
}
