package com.yighy.pantograph.drawing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The floating reference picture: pick one, restore it with the project, move/scale/rotate it.
 *
 * A failed decode still leaves a [ReferenceImage] in the state, just without pixels - the window
 * appears where the user asked for it instead of the pick silently doing nothing.
 */
class ReferenceImageController(
    private val session: DrawingSession,
    private val persistence: ProjectPersistence,
    private val scope: CoroutineScope
) {
    fun set(context: android.content.Context, uri: String) {
        persistUriAccess(context, uri)
        scope.launch(Dispatchers.IO) {
            try {
                val decoded = decodeImage(context, uri)
                val state = session.value

                val scale = if (decoded.width > 0) (state.canvasWidth * 0.6f) / decoded.width else 1f
                val offsetX = (state.canvasWidth - decoded.width * scale) / 2f
                val offsetY = (state.canvasHeight - decoded.height * scale) / 2f

                session.update { it.copy(
                    referenceImage = ReferenceImage(
                        uri = uri,
                        offset = Offset(offsetX, offsetY),
                        scale = 1.0f,
                        aspectRatio = decoded.aspectRatio,
                        bitmap = decoded.bitmap
                    )
                ) }
                persistence.saveProjectSettings()
            } catch (e: Exception) {
                android.util.Log.e("ReferenceImageController", "Failed to load reference image", e)
                session.update { it.copy(referenceImage = ReferenceImage(uri = uri, offset = Offset(100f, 100f), bitmap = null)) }
            }
        }
    }

    /** Re-decodes the picture the project was saved with, keeping its stored placement. */
    fun restore(context: android.content.Context, uri: String, offset: Offset, scale: Float, rotation: Float) {
        scope.launch(Dispatchers.IO) {
            try {
                val decoded = decodeImage(context, uri)
                session.update { it.copy(
                    referenceImage = ReferenceImage(
                        uri = uri,
                        offset = offset,
                        scale = scale,
                        rotation = rotation,
                        aspectRatio = decoded.aspectRatio,
                        bitmap = decoded.bitmap
                    )
                ) }
            } catch (e: Exception) {
                android.util.Log.e("ReferenceImageController", "Failed to reload saved reference image", e)
            }
        }
    }

    fun remove() {
        session.update { it.copy(referenceImage = null) }
        scope.launch { persistence.saveProjectSettings() }
    }

    fun update(pan: Offset, zoom: Float, rotation: Float) {
        session.update { state ->
            val ref = state.referenceImage ?: return@update state
            state.copy(referenceImage = ref.copy(
                offset = ref.offset + pan,
                scale = (ref.scale * zoom).coerceIn(0.1f, 10f),
                rotation = ref.rotation + rotation
            ))
        }
        // Save debounced or on specific interval might be better, but let's do it simple for now
        scope.launch { persistence.saveProjectSettings() }
    }
}

/** A decoded picture plus the dimensions its callers place it by. */
class DecodedImage(val bitmap: Bitmap?, val width: Float, val height: Float, val aspectRatio: Float)

/** Shared decode step for the reference window and the image import. Throws on failure - callers decide how to degrade. */
fun decodeImage(context: android.content.Context, uri: String): DecodedImage {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = false }
    val stream = context.contentResolver.openInputStream(android.net.Uri.parse(uri))
    val bitmap = BitmapFactory.decodeStream(stream, null, options)
    stream?.close()
    val width = options.outWidth.toFloat()
    val height = options.outHeight.toFloat()
    return DecodedImage(bitmap, width, height, if (height > 0) width / height else 1f)
}
