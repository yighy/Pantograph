package com.yighy.pantograph

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yighy.pantograph.data.AppTheme
import com.yighy.pantograph.data.PreferenceManager
import com.yighy.pantograph.data.ReleaseVersion
import com.yighy.pantograph.data.UpdateChecker
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/**
 * How far the button has to be dragged before it moves rather than draws, as three plain choices.
 * The setting is stored in pixels, and a slider in pixels was a number nobody could judge.
 */
private val MoveDistances = listOf("Short" to 50f, "Medium" to 100f, "Long" to 180f)

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
    val undoRestoresCursor by preferenceManager.undoRestoresCursor.collectAsState(initial = true)
    val keepUndoneStrokes by preferenceManager.keepUndoneStrokes.collectAsState(initial = false)
    val satelliteGateSensitivity by preferenceManager.satelliteGateSensitivity.collectAsState(initial = 1f)
    val checkForUpdates by preferenceManager.checkForUpdates.collectAsState(initial = false)
    val lastUpdateCheck by preferenceManager.lastUpdateCheckTime.collectAsState(initial = 0L)
    val updateChecker = remember(preferenceManager) { UpdateChecker(preferenceManager) }
    // Only the result of a check asked for here; the automatic one reports on the home screen.
    var updateResult by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var checking by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
    } ?: BuildConfig.VERSION_NAME
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
                .padding(bottom = 24.dp)
        ) {
            SectionHeader("Appearance")
            ChoiceRow(
                label = "Theme",
                options = listOf(AppTheme.SYSTEM, AppTheme.LIGHT, AppTheme.DARK),
                selected = appTheme,
                optionLabel = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
                onSelect = { scope.launch { preferenceManager.setAppTheme(it) } }
            )
            SwitchRow("Dynamic color", subtitle = "From your wallpaper", checked = dynamicColor) {
                scope.launch { preferenceManager.setDynamicColor(it) }
            }
            SwitchRow("Hide status bar", checked = hideStatusBar) {
                scope.launch { preferenceManager.setHideStatusBar(it) }
            }

            SectionHeader("Drawing")
            StepperRow(
                label = "Undo history",
                value = historyLimit,
                range = 1..20,
                onChange = { scope.launch { preferenceManager.setHistoryLimit(it) } }
            )
            SliderRow(
                label = "Cursor thickness",
                valueLabel = "%.1f".format(cursorThickness),
                value = cursorThickness,
                range = 0.5f..5.0f,
                onChange = { scope.launch { preferenceManager.setCursorThickness(it) } }
            )
            SwitchRow(
                "Offscreen cursor arrow",
                subtitle = "Points to the cursor when it's off screen",
                checked = offscreenCursorArrow
            ) { scope.launch { preferenceManager.setOffscreenCursorArrow(it) } }
            SwitchRow(
                "Undo returns the cursor",
                subtitle = "Back to where the stroke began",
                checked = undoRestoresCursor
            ) { scope.launch { preferenceManager.setUndoRestoresCursor(it) } }
            SwitchRow(
                "Keep undone strokes",
                subtitle = "Leaves them faintly, to redraw over",
                checked = keepUndoneStrokes
            ) { scope.launch { preferenceManager.setKeepUndoneStrokes(it) } }

            SectionHeader("Button")
            SliderRow(
                label = "Size",
                valueLabel = null,
                value = fabSize,
                range = 40f..120f,
                onChange = { scope.launch { preferenceManager.setFabSize(it) } }
            )
            ChoiceRow(
                label = "Move distance",
                subtitle = "How far to drag before the button moves",
                options = MoveDistances.map { it.second },
                // The stored value can sit between the choices, from before they existed: the
                // nearest one is what it shows as.
                selected = MoveDistances.minBy { abs(it.second - fabDragThreshold) }.second,
                optionLabel = { px -> MoveDistances.first { it.second == px }.first },
                onSelect = { scope.launch { preferenceManager.setFabDragThreshold(it) } }
            )
            SliderRow(
                label = "Satellite sensitivity",
                valueLabel = "%.1fx".format(satelliteGateSensitivity),
                value = satelliteGateSensitivity,
                range = 0.25f..2.0f,
                onChange = { scope.launch { preferenceManager.setSatelliteGateSensitivity(it) } }
            )

            SectionHeader("Updates")
            SwitchRow(
                "Check for updates",
                subtitle = "Once a day, on GitHub",
                checked = checkForUpdates
            ) { scope.launch { preferenceManager.setCheckForUpdates(it) } }
            val available = updateResult as? UpdateChecker.Result.Available
            ActionRow(
                label = if (available != null) "View release" else "Check now",
                subtitle = when (val r = updateResult) {
                    null -> when {
                        checking -> "Checking..."
                        lastUpdateCheck <= 0L -> "Never checked"
                        else -> "Last checked " + DateFormat
                            .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(lastUpdateCheck))
                    }
                    is UpdateChecker.Result.Available -> "Version ${r.tag.removePrefix("v")} is available"
                    UpdateChecker.Result.UpToDate -> "You have the latest version"
                    UpdateChecker.Result.Failed -> "Couldn't reach GitHub"
                },
                enabled = !checking
            ) {
                if (available != null) {
                    uriHandler.openUri(ReleaseVersion.pageUrl(available.tag))
                } else {
                    // Asked for by hand, so it goes out even with the automatic check off.
                    checking = true
                    updateResult = null
                    scope.launch {
                        updateResult = updateChecker.check()
                        checking = false
                    }
                }
            }

            SectionHeader("About")
            ValueRow("Version", versionName)
        }
    }
}

// ---- rows ----
// One line each where the setting allows it: the label on the left, what it is set to on the
// right. A subtitle only where the label alone does not say what the setting does.

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp)
    )
}

@Composable
private fun RowLabel(label: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val RowModifier = Modifier
    .fillMaxWidth()
    .heightIn(min = 56.dp)

/** The whole row toggles, not just the switch: it is the bigger target, and says the same. */
@Composable
private fun SwitchRow(label: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = RowModifier
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowLabel(label, subtitle, Modifier.weight(1f).padding(end = 16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A choice among a few, shown as its current value and picked from a menu. */
@Composable
private fun <T> ChoiceRow(
    label: String,
    subtitle: String? = null,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = RowModifier
            .clickable(role = Role.DropdownList) { open = true }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowLabel(label, subtitle, Modifier.weight(1f).padding(end = 16.dp))
        Box {
            Text(optionLabel(selected), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            open = false
                            onSelect(option)
                        },
                        trailingIcon = if (option == selected) {
                            { Icon(Icons.Rounded.Check, contentDescription = "Selected") }
                        } else null
                    )
                }
            }
        }
    }
}

/** A small whole number, stepped one at a time. */
@Composable
private fun StepperRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(
        modifier = RowModifier.padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowLabel(label, null, Modifier.weight(1f))
        IconButton(onClick = { onChange(value - 1) }, enabled = value > range.first) {
            Icon(Icons.Rounded.Remove, contentDescription = "Fewer")
        }
        Text(
            "$value",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.widthIn(min = 24.dp),
            textAlign = TextAlign.Center
        )
        IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last) {
            Icon(Icons.Rounded.Add, contentDescription = "More")
        }
    }
}

/** For settings judged by feel rather than by number. */
@Composable
private fun SliderRow(
    label: String,
    valueLabel: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowLabel(label, null, Modifier.weight(1f))
            if (valueLabel != null) {
                Text(valueLabel, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun ActionRow(label: String, subtitle: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = RowModifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowLabel(label, subtitle, Modifier.weight(1f))
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(
        modifier = RowModifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowLabel(label, null, Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
