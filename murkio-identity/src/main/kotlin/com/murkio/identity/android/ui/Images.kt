package com.murkio.identity.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.murkio.identity.android.internal.base64Decode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_BYTES = 4 * 1024 * 1024
private const val MAX_SIDE = 2048

private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** Reads at most [MAX_BYTES] of an https image, without cookies, credentials or redirects to another scheme. */
private fun download(url: String): ByteArray? {
    val connection = URL(url).openConnection() as HttpURLConnection
    return try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        if (connection.responseCode != 200) return null
        connection.inputStream.use { stream ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val read = stream.read(chunk)
                if (read < 0) break
                buffer.write(chunk, 0, read)
                if (buffer.size() > MAX_BYTES) return null
            }
            buffer.toByteArray()
        }
    } finally {
        connection.disconnect()
    }
}

private fun decode(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Loads the image of a canvas element (`https:` or `data:`). Null when it cannot be shown: the box stays empty. */
internal suspend fun loadCanvasImage(src: String): ImageBitmap? = withContext(Dispatchers.IO) {
    cache.get(src)?.let { return@withContext it.asImageBitmap() }
    runCatching {
        val bytes = if (src.startsWith("data:")) base64Decode(src.substringAfter(',')) else download(src)
        bytes?.let(::decode)?.also { cache.put(src, it) }?.asImageBitmap()
    }.getOrNull()
}

@Composable
internal fun CanvasImage(src: String, description: String?, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, src) { value = loadCanvasImage(src) }
    bitmap?.let { Image(it, contentDescription = description, modifier = modifier, contentScale = ContentScale.Fit) }
}
