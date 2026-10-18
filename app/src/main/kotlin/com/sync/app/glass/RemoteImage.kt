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
/** 개수가 아니라 메모리(바이트) 기준으로 제한 — 큰 썸네일이 들어와도 힙이 불어나 GC 로 프레임이 떨어지지 않게 한다. */
private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** 글래스 컨트롤에 표시되는 크기는 작으므로(수십 dp) 긴 변이 이 값을 넘지 않도록 축소해서 디코딩한다. */
private const val MAX_DECODE_DIM = 512

private fun decodeSampled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / (sample * 2) >= MAX_DECODE_DIM) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

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
        return decodeSampled(bytes)
    }
    if (!src.startsWith("http")) return null
    val c = (URL(src).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 8000
    }
    return try {
        decodeSampled(c.inputStream.use { it.readBytes() })
    } finally { c.disconnect() }
}
