package com.yighy.paintcursor.drawing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.Square
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
fun HSBPickerView(
    initialColor: Color,
    isSliderMode: Boolean,
    isEyeDropperActive: Boolean,
    onModeToggle: (Boolean) -> Unit,
    onEyeDropperClick: () -> Unit,
    onColorChanged: (Color) -> Unit
) {
    // Local state to keep HSB values stable and independent of RGB conversions
    var hue by remember { mutableFloatStateOf(0f) }
    var saturation by remember { mutableFloatStateOf(0f) }
    var brightness by remember { mutableFloatStateOf(0f) }
    
    // Update local HSB only when initialColor changes from OUTSIDE (like History or Layer click)
    LaunchedEffect(initialColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor.toArgb(), hsv)
        // Only update if the difference is significant to avoid slider jumping
        if (Color.hsv(hsv[0], hsv[1], hsv[2]).toArgb() != Color.hsv(hue, saturation, brightness).toArgb()) {
            hue = hsv[0]
            saturation = hsv[1]
            brightness = hsv[2]
        }
    }

    Column(
        modifier = Modifier
            .padding(8.dp)
            .width(220.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!isSliderMode) {
            SaturationValueBox(
                hue = hue,
                saturation = saturation,
                value = brightness,
                onValueChange = { s, v ->
                    saturation = s
                    brightness = v
                    onColorChanged(Color.hsv(hue, s, v))
                }
            )

            HueSlider(
                hue = hue,
                onHueChange = { h ->
                    hue = h
                    onColorChanged(Color.hsv(h, saturation, brightness))
                }
            )
        } else {
            HSBSlidersView(
                hue = hue,
                saturation = saturation,
                value = brightness,
                onValueChange = { h, s, v ->
                    hue = h
                    saturation = s
                    brightness = v
                    onColorChanged(Color.hsv(h, s, v))
                }
            )
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 110.dp, height = 32.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(Color.hsv(hue, saturation, brightness))
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
            )
            
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onEyeDropperClick,
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isEyeDropperActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                    )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Colorize,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (isEyeDropperActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = { onModeToggle(!isSliderMode) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isSliderMode) Icons.Rounded.Square else Icons.Rounded.LinearScale,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun HSBSlidersView(
    hue: Float,
    saturation: Float,
    value: Float,
    onValueChange: (Float, Float, Float) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column {
            Text("Hue: ${hue.toInt()}\u00B0", style = MaterialTheme.typography.labelSmall)
            HueSlider(hue = hue, onHueChange = { onValueChange(it, saturation, value) })
        }
        
        Column {
            Text("Saturation: ${(saturation * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = saturation,
                onValueChange = { onValueChange(hue, it, value) },
                valueRange = 0f..1f
            )
        }
        
        Column {
            Text("Brightness: ${(value * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = value,
                onValueChange = { onValueChange(hue, saturation, it) },
                valueRange = 0f..1f
            )
        }
    }
}

@Composable
fun SaturationValueBox(
    hue: Float,
    saturation: Float,
    value: Float,
    onValueChange: (Float, Float) -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val s = (offset.x / size.width).coerceIn(0f, 1f)
                    val v = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                    onValueChange(s, v)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    val s = (change.position.x / size.width).coerceIn(0f, 1f)
                    val v = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                    onValueChange(s, v)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val saturationBrush = Brush.horizontalGradient(
                colors = listOf(Color.White, Color.hsv(hue, 1f, 1f))
            )
            drawRect(brush = saturationBrush)

            val valueBrush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black)
            )
            drawRect(brush = valueBrush)

            val selectorX = saturation * size.width
            val selectorY = (1f - value) * size.height
            drawCircle(
                color = if (value > 0.5f) Color.Black else Color.White,
                radius = 6.dp.toPx(),
                center = Offset(selectorX, selectorY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx())
            )
        }
    }
}

@Composable
fun HueSlider(hue: Float, onHueChange: (Float) -> Unit) {
    val hueColors = remember {
        List(360) { Color.hsv(it.toFloat(), 1f, 1f) }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(hueColors))
        )
        Slider(
            value = hue,
            onValueChange = onHueChange,
            valueRange = 0f..360f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent
            )
        )
    }
}
