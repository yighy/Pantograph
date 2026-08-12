package com.yighy.paintcursor

import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Gesture
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yighy.paintcursor.data.AppTheme
import com.yighy.paintcursor.data.PreferenceManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    preferenceManager: PreferenceManager,
    onBack: () -> Unit
) {
    val appTheme by preferenceManager.appTheme.collectAsState(initial = AppTheme.SYSTEM)
    val historyLimit by preferenceManager.historyLimit.collectAsState(initial = 5)
    val fabDragThreshold by preferenceManager.fabDragThreshold.collectAsState(initial = 100f)
    val cursorThickness by preferenceManager.cursorThickness.collectAsState(initial = 1.0f)
    val fabSize by preferenceManager.fabSize.collectAsState(initial = 56f)
    val hideStatusBar by preferenceManager.hideStatusBar.collectAsState(initial = true)
    val dynamicColor by preferenceManager.dynamicColor.collectAsState(initial = true)
    val offscreenCursorArrow by preferenceManager.offscreenCursorArrow.collectAsState(initial = false)
    val satelliteGateSensitivity by preferenceManager.satelliteGateSensitivity.collectAsState(initial = 1f)
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
    }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { 
                    Text(
                        "Settings", 
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Appearance Section
            SettingsSection(title = "Appearance", icon = Icons.Rounded.Palette) {
                SettingsRow(label = "App Theme") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppTheme.entries.forEach { theme ->
                            FilterChip(
                                selected = appTheme == theme,
                                onClick = { scope.launch { preferenceManager.setAppTheme(theme) } },
                                label = { Text(theme.name.lowercase().replaceFirstChar { it.uppercase() }) },
                                shape = MaterialTheme.shapes.medium
                            )
                        }
                    }
                }
                
                SettingsToggleRow(
                    label = "Hide Status Bar",
                    subtitle = "Full screen immersion",
                    checked = hideStatusBar,
                    onCheckedChange = { scope.launch { preferenceManager.setHideStatusBar(it) } }
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    SettingsToggleRow(
                        label = "Wallpaper Colors",
                        subtitle = "Match theme to your wallpaper (Material You)",
                        checked = dynamicColor,
                        onCheckedChange = { scope.launch { preferenceManager.setDynamicColor(it) } }
                    )
                }
            }

            // Drawing Controls Section
            SettingsSection(title = "Drawing Controls", icon = Icons.Rounded.Gesture) {
                SettingsSliderRow(
                    label = "Undo History Limit",
                    value = historyLimit.toFloat(),
                    valueLabel = "$historyLimit actions",
                    range = 1f..20f,
                    steps = 18,
                    onValueChange = { scope.launch { preferenceManager.setHistoryLimit(it.toInt()) } }
                )

                SettingsSliderRow(
                    label = "Cursor Thickness",
                    value = cursorThickness,
                    valueLabel = String.format("%.1f px", cursorThickness),
                    range = 0.5f..5.0f,
                    onValueChange = { scope.launch { preferenceManager.setCursorThickness(it) } }
                )

                SettingsToggleRow(
                    label = "Offscreen Cursor Arrow",
                    subtitle = "Edge arrow pointing at the cursor when it leaves the screen",
                    checked = offscreenCursorArrow,
                    onCheckedChange = { scope.launch { preferenceManager.setOffscreenCursorArrow(it) } }
                )
            }

            // Interaction Section
            SettingsSection(title = "Interaction", icon = Icons.Rounded.TouchApp) {
                SettingsSliderRow(
                    label = "Button Drag Threshold",
                    value = fabDragThreshold,
                    valueLabel = "${fabDragThreshold.toInt()} px",
                    range = 0f..250f,
                    onValueChange = { scope.launch { preferenceManager.setFabDragThreshold(it) } }
                )

                SettingsSliderRow(
                    label = "Main Button Size",
                    value = fabSize,
                    valueLabel = "${fabSize.toInt()} dp",
                    range = 40f..120f,
                    onValueChange = { scope.launch { preferenceManager.setFabSize(it) } }
                )

                SettingsSliderRow(
                    label = "Satellite Sensitivity",
                    value = satelliteGateSensitivity,
                    valueLabel = String.format("%.1fx", satelliteGateSensitivity),
                    range = 0.25f..2.0f,
                    onValueChange = { scope.launch { preferenceManager.setSatelliteGateSensitivity(it) } }
                )
            }
            
            versionName?.let {
                Text(
                    text = "Paint Cursor $it",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
fun SettingsSection(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
        }
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
fun SettingsRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
fun SettingsToggleRow(label: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SettingsSliderRow(label: String, value: Float, valueLabel: String, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onValueChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}
