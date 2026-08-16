package com.yighy.paintcursor.drawing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yighy.paintcursor.data.CustomBrushEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Decodes the custom tips and paper textures brushes point at, and keeps the ones saved presets
 * need alive.
 *
 * [DrawingState] only ever holds the *active* brush's assets, so without the preset caches a
 * thumbnail had to fall back to the default round stamp and misrepresented every custom-tip
 * brush in the list. Preset assets are preloaded off the brush list and read synchronously while
 * rendering a thumbnail.
 */
class BrushAssetLoader(
    private val session: DrawingSession,
    private val scope: CoroutineScope
) {
    private val presetTipCache = mutableMapOf<String, Bitmap>()
    private val presetMaskCache = mutableMapOf<String, Bitmap>()

    /** Decoded tip for a preset's uri, if it has landed in the cache. */
    fun cachedTip(uri: String): Bitmap? = presetTipCache[uri]

    /** Decoded texture *mask* for a preset's uri, if it has landed in the cache. */
    fun cachedMask(uri: String): Bitmap? = presetMaskCache[uri]

    fun setBrushTip(context: android.content.Context, uri: String?) =
        loadBrushBitmap(context, uri, "brush tip") { u, b ->
            persistUriAccess(context, u)
            session.update { it.copy(brushTipUri = u, brushTipBitmap = b) }
        }

    fun setBrushTexture(context: android.content.Context, uri: String?) =
        loadBrushBitmap(context, uri, "brush texture") { u, b ->
            persistUriAccess(context, u)
            val mask = b?.let { buildLuminanceMask(it) }
            session.update { it.copy(brushTextureUri = u, brushTextureBitmap = b, brushTextureMask = mask) }
        }

    /**
     * Decodes any tip or texture a saved preset refers to but the live brush doesn't hold.
     *
     * Bumps [DrawingState.brushAssetsVersion] once done rather than per asset: thumbnails key
     * their cached render on it, so a single bump re-renders the affected rows once instead of
     * once per file that lands.
     */
    fun preloadPresetAssets(context: android.content.Context, brushes: List<CustomBrushEntity>) {
        val tips = brushes.mapNotNull { it.tipUri }.toSet() - presetTipCache.keys
        val textures = brushes.mapNotNull { it.textureUri }.toSet() - presetMaskCache.keys
        if (tips.isEmpty() && textures.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            var loaded = false
            tips.forEach { uri ->
                decodeUri(context, uri)?.let { presetTipCache[uri] = it; loaded = true }
            }
            textures.forEach { uri ->
                decodeUri(context, uri)?.let { presetMaskCache[uri] = buildLuminanceMask(it); loaded = true }
            }
            if (loaded) {
                withContext(Dispatchers.Main) {
                    session.update { it.copy(brushAssetsVersion = it.brushAssetsVersion + 1) }
                }
            }
        }
    }

    /** Full-size decodes, one per distinct preset asset - they would otherwise sit here for as long as the process lives. */
    fun release() {
        (presetTipCache.values + presetMaskCache.values).forEach { it.recycle() }
        presetTipCache.clear()
        presetMaskCache.clear()
    }

    private fun decodeUri(context: android.content.Context, uri: String): Bitmap? = try {
        context.contentResolver.openInputStream(android.net.Uri.parse(uri)).use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        android.util.Log.e("BrushAssetLoader", "Failed to load preset asset $uri", e)
        null
    }

    private fun loadBrushBitmap(
        context: android.content.Context,
        uri: String?,
        label: String,
        onLoaded: (uri: String?, bitmap: Bitmap?) -> Unit
    ) {
        if (uri == null) {
            onLoaded(null, null)
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(android.net.Uri.parse(uri))
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) {
                    onLoaded(uri, bitmap)
                }
            } catch (e: Exception) {
                android.util.Log.e("BrushAssetLoader", "Failed to load $label", e)
            }
        }
    }
}

/**
 * Asks to keep reading [uri] after the process dies.
 *
 * The picker's own grant lasts only as long as the task, but these uris are written into the
 * project and read again on the next launch - without this the tip, texture and reference image
 * simply stopped loading once the app had been closed, and the failure was swallowed by the
 * decode's catch. Only documents opened through OpenDocument can grant this, so a uri that came
 * from somewhere else is left as it is rather than treated as an error.
 */
fun persistUriAccess(context: android.content.Context, uri: String?) {
    if (uri == null) return
    try {
        context.contentResolver.takePersistableUriPermission(
            android.net.Uri.parse(uri),
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    } catch (e: SecurityException) {
        android.util.Log.w("BrushAssetLoader", "No persistable grant for $uri", e)
    }
}

/** White areas of the texture keep paint, dark areas cut it out (luminance -> alpha). */
fun buildLuminanceMask(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    for (i in pixels.indices) {
        val c = pixels[i]
        val lum = (0.299f * android.graphics.Color.red(c) +
                   0.587f * android.graphics.Color.green(c) +
                   0.114f * android.graphics.Color.blue(c)).toInt()
        val alpha = lum * android.graphics.Color.alpha(c) / 255
        pixels[i] = alpha shl 24
    }
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    out.setPixels(pixels, 0, w, 0, 0, w, h)
    return out
}
