package com.yighy.pantograph.drawing

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.yighy.pantograph.ui.theme.MotionTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Which of the bar's three buttons is pressed, its panel up above the bar. At most one. */
enum class ToolbarPanel { None, Brush, Color, Settings }

/** What the card above the bottom bar is showing. */
private enum class ToolbarCard { Brush, Color, Settings, Path, Selection }

@Composable
fun DrawingToolbar(
    viewModel: DrawingViewModel,
    onOpenBrushStudio: () -> Unit,
    // Hoisted: which panel is open is decided by whatever the user just tapped, including
    // the tool menu that lives in DrawingScreen. Deriving it from state changes here meant a
    // tool that was re-selected without changing state simply never reopened its panel.
    activePanel: ToolbarPanel,
    onActivePanelChange: (ToolbarPanel) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Strips this back to the panels an armed tool brings with it. Fullscreen sets it: the
     * buttons belong to the chrome it is there to remove, the three panels behind them are
     * reachable from the satellites, and a tool's values are on its chip - but nothing else
     * reaches the path and selection commands, which is how fullscreen came to arm tools it
     * gave you no way to finish using.
     */
    armedToolsOnly: Boolean = false
) {
    val drawingMode by remember(viewModel) { viewModel.uiState.map { it.drawingMode }.distinctUntilChanged() }.collectAsState(DrawingMode.Freehand)
    val canUndo by remember(viewModel) { viewModel.uiState.map { it.canUndo }.distinctUntilChanged() }.collectAsState(false)
    val canRedo by remember(viewModel) { viewModel.uiState.map { it.canRedo }.distinctUntilChanged() }.collectAsState(false)
    // Greyed out mid-stroke rather than tapped and ignored: the ViewModel refuses either way,
    // but a button that looks live and does nothing reads as the app having missed the tap.
    val isPenDown by remember(viewModel) { viewModel.uiState.map { it.isPenDown }.distinctUntilChanged() }.collectAsState(false)
    val selectedColor by remember(viewModel) { viewModel.uiState.map { it.selectedColor }.distinctUntilChanged() }.collectAsState(Color.Black)

    val isSelectionMode = drawingMode.isSelectionTool()
    val isPathMode = drawingMode is DrawingMode.Path
    val isSelectionClosed by remember(viewModel) { viewModel.uiState.map { it.isSelectionClosed }.distinctUntilChanged() }.collectAsState(false)

    // Arming a tool that brings its own panel lets go of whichever of the three buttons was
    // pressed, so the tool's commands are what shows. This one stays state-driven because it
    // only ever *closes* panels - a repeated selection with nothing to change is correctly a
    // no-op.
    LaunchedEffect(drawingMode) {
        if (drawingMode.isSelectionTool() || drawingMode is DrawingMode.Path) {
            onActivePanelChange(ToolbarPanel.None)
        }
    }

    // One card at a time. A pressed button wins over an armed tool, so the brush or colour can
    // be changed mid-selection; letting the button go brings the tool's panel back, since the
    // tool is still armed. Only the command panels count for a tool: its values travel on its
    // chip, which is already the control.
    val toolCard = when {
        isPathMode -> ToolbarCard.Path
        isSelectionMode || isSelectionClosed -> ToolbarCard.Selection
        else -> null
    }
    val card = when {
        armedToolsOnly -> toolCard
        activePanel == ToolbarPanel.Brush -> ToolbarCard.Brush
        activePanel == ToolbarPanel.Color -> ToolbarCard.Color
        activePanel == ToolbarPanel.Settings -> ToolbarCard.Settings
        else -> toolCard
    }
    // The card keeps showing what it last showed while it animates away, rather than going
    // blank for the length of its exit.
    var lastCard by remember { mutableStateOf(card) }
    if (card != null) lastCard = card

    fun toggle(panel: ToolbarPanel) = onActivePanelChange(if (activePanel == panel) ToolbarPanel.None else panel)

    Column(
        modifier = modifier.fillMaxWidth(),
        // Above the buttons that open it, at the end they sit at. On a phone held upright it
        // takes the whole width anyway; in landscape it stays over the thumb that opened it.
        horizontalAlignment = Alignment.End
    ) {
        AnimatedVisibility(
            visible = card != null,
            // Grows up out of the bar, the way the panels used to grow out of it.
            enter = expandVertically(MotionTokens.panelTransition, expandFrom = Alignment.Bottom) +
                fadeIn(MotionTokens.expressiveEnter),
            exit = shrinkVertically(MotionTokens.panelTransition, shrinkTowards = Alignment.Bottom) +
                fadeOut(MotionTokens.expressiveExit)
        ) {
            Surface(
                modifier = Modifier
                    // Caps the width so landscape doesn't stretch sliders across 700dp of
                    // screen with the controls marooned at either end. Must come before the
                    // fill: fillMaxWidth pins min width to the incoming max, and a widthIn
                    // placed after that has nothing left to constrain.
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    // Clear of the buttons below, so the one holding it open stays in sight.
                    .padding(bottom = 8.dp)
                    // Swipe down anywhere on the card to let go of the button that opened it.
                    // Children (sliders, scrollable lists) consume their own gestures first,
                    // so this only sees swipes on non-interactive areas.
                    .pointerInput(Unit) {
                        var totalDrag = 0f
                        detectVerticalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onVerticalDrag = { change, dragAmount ->
                                totalDrag += dragAmount
                                if (totalDrag > 0f) change.consume()
                            },
                            onDragEnd = {
                                if (totalDrag > 80f) onActivePanelChange(ToolbarPanel.None)
                            }
                        )
                    },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 2.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                AnimatedContent(
                    targetState = lastCard,
                    transitionSpec = {
                        (fadeIn(MotionTokens.expressiveEnter) togetherWith fadeOut(MotionTokens.expressiveExit))
                            .using(SizeTransform(clip = true) { _, _ -> MotionTokens.panelTransition })
                    },
                    // Pinned to the bottom, so a change of height moves the top edge - the one
                    // away from the buttons - and the card never slides under them.
                    contentAlignment = Alignment.BottomCenter,
                    label = "toolbar card"
                ) { shown ->
                    Box(modifier = Modifier.padding(12.dp)) {
                        when (shown) {
                            ToolbarCard.Brush -> QuickBrushPanel(viewModel, onOpenStudio = {
                                onActivePanelChange(ToolbarPanel.None)
                                onOpenBrushStudio()
                            })
                            ToolbarCard.Color -> ColorPickerContent(viewModel) { onActivePanelChange(ToolbarPanel.None) }
                            ToolbarCard.Settings -> GlobalSettingsPanel(viewModel)
                            // The tool owns the card while it is armed. Its commands are
                            // frequent and repeated, so they belong under the thumb rather than
                            // in the readout row across the screen; that row keeps only the
                            // state chip.
                            ToolbarCard.Path -> PathPanel(viewModel)
                            ToolbarCard.Selection -> SelectionPanel(viewModel)
                            null -> {}
                        }
                    }
                }
            }
        }

        // The bar itself: history under one thumb, the three panels under the other. Two
        // groups in the corners rather than one row in the middle, so the middle of the bottom
        // edge - where you would otherwise be drawing - stays clear.
        if (!armedToolsOnly) Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ButtonGroup {
                GroupedButton(GroupPosition.First, onClick = { viewModel.undo() }, enabled = canUndo && !isPenDown) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, "Undo")
                }
                GroupedButton(GroupPosition.Last, onClick = { viewModel.redo() }, enabled = canRedo && !isPenDown) {
                    Icon(Icons.AutoMirrored.Rounded.Redo, "Redo")
                }
            }

            // At most one of these is pressed, and it stays pressed for as long as its card is
            // up. Colour at the far end, the one reached for most while drawing.
            ButtonGroup {
                GroupedButton(
                    GroupPosition.First,
                    onClick = { toggle(ToolbarPanel.Settings) },
                    pressed = activePanel == ToolbarPanel.Settings,
                    isToggle = true
                ) {
                    Icon(Icons.Rounded.Tune, "Tool settings")
                }
                GroupedButton(
                    GroupPosition.Middle,
                    onClick = { toggle(ToolbarPanel.Brush) },
                    pressed = activePanel == ToolbarPanel.Brush,
                    isToggle = true
                ) {
                    Icon(Icons.Rounded.Brush, "Brush presets")
                }
                // The whole button is the colour in hand, so it shows at a glance from across the
                // screen, and the palette glyph on it still says what the button does. Opaque:
                // the colour's alpha is a brush setting, not part of what it looks like here.
                GroupedButton(
                    GroupPosition.Last,
                    onClick = { toggle(ToolbarPanel.Color) },
                    pressed = activePanel == ToolbarPanel.Color,
                    isToggle = true,
                    fill = selectedColor.copy(alpha = 1f)
                ) {
                    Icon(Icons.Rounded.Palette, "Colour picker")
                }
            }
        }
    }
}
