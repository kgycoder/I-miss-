package com.sync.app.glass

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Tiny loader for the artwork shown on glass controls (https URL or data: URI coming from the DOM). */
private val cache = object : LruCache<String, Bitmap>(48) {}

@Composable
fun rememberRemoteImage(src: String): ImageBitmap? {
    val state = produceState<ImageBitmap?>(cache.get(src)?.asImageBitmap(), src) {
        if (src.isBlank()) { value = null; return@produceState }
        cache.get(src)?.let { value = it.asImageBitmap(); return@produceState }
        val bmp = withContext(Dispatchers.IO) { runCatching { decode(src) }.getOrNull() }
        if (bmp != null) { cache.put(src, bmp); value = bmp.asImageBitmap() }
    }
    return state.value
}

private fun decode(src: String): Bitmap? {
    if (src.startsWith("data:")) {
        val b64 = src.substringAfter("base64,", "")
        if (b64.isEmpty()) return null
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    if (!src.startsWith("http")) return null
    val c = (URL(src).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 8000
    }
    return try {
        c.inputStream.use { BitmapFactory.decodeStream(it) }
    } finally { c.disconnect() }
}
