package com.yighy.pantograph

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yighy.pantograph.data.PreferenceManager
import com.yighy.pantograph.data.ProjectEntity
import androidx.compose.ui.res.stringResource
import com.yighy.pantograph.ui.theme.MotionTokens
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    preferenceManager: PreferenceManager,
    onNavigateToProject: (Long) -> Unit
) {
    val projects by viewModel.projects.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var projectToRename by remember { mutableStateOf<ProjectEntity?>(null) }
    var projectToDelete by remember { mutableStateOf<ProjectEntity?>(null) }

    // A large title that collapses as the grid scrolls, which also settles what used to be two
    // competing headings: the bar said the app's name in headlineLarge and a second line under
    // it said "Projects". The grid is the only thing on this screen, so it does not need
    // announcing - the bar now carries the name and gives the space back on scroll.
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        floatingActionButton = {
            // Extended while the list is worth scrolling and shrunk to the icon once you are
            // into it: the label earns its width on an empty or short screen, and stops
            // earning it once the grid is the thing you are looking at.
            ExtendedFloatingActionButton(
                onClick = { showCreateDialog = true },
                expanded = scrollBehavior.state.collapsedFraction < 0.5f,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New project") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
            if (projects.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).widthIn(max = 280.dp).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Palette,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "No projects yet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Start your first sketch and it will show up here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    Button(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("New Project")
                    }
                }
            } else {
                // Staggered rather than a uniform grid: the cards are now as tall as their
                // canvas is, so a fixed row height would either crop the wide ones or pad the
                // tall ones back into the sameness the proportions were meant to break.
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(minSize = 160.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalItemSpacing = 16.dp
                ) {
                    items(projects, key = { it.id }) { project ->
                        val visibleState = remember { MutableTransitionState(false).apply { targetState = true } }
                        androidx.compose.animation.AnimatedVisibility(
                            visibleState = visibleState,
                            enter = fadeIn(MotionTokens.expressiveEnter) + scaleIn(MotionTokens.expressiveEnter, initialScale = 0.9f),
                            modifier = Modifier.animateItem()
                        ) {
                            ProjectCard(
                                project = project,
                                onClick = { onNavigateToProject(project.id) },
                                onDelete = { projectToDelete = project },
                                onRename = { projectToRename = project }
                            )
                        }
                    }
                }
            }
            }
        }

        if (showCreateDialog) {
            CreateProjectDialog(
                onDismiss = { showCreateDialog = false },
                onCreate = { name, width, height ->
                    viewModel.createProject(name, width, height) { id ->
                        onNavigateToProject(id)
                    }
                    showCreateDialog = false
                }
            )
        }

        projectToRename?.let { project ->
            var newName by remember { mutableStateOf(project.name) }
            AlertDialog(
                onDismissRequest = { projectToRename = null },
                title = { Text("Rename Project") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.renameProject(project, newName)
                        projectToRename = null
                    }) {
                        Text("Rename")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { projectToRename = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        projectToDelete?.let { project ->
            AlertDialog(
                onDismissRequest = { projectToDelete = null },
                title = { Text("Delete Project") },
                text = { Text("Are you sure you want to delete '${project.name}'? This action cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.deleteProject(project)
                        projectToDelete = null
                    }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { projectToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun ProjectCard(
    project: ProjectEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val lastModified = remember(project.updatedAt) { dateFormatter.format(Date(project.updatedAt)) }
    var showMenu by remember { mutableStateOf(false) }

    // Shape and scale answer the finger, which is the expressive part: the card softens and
    // settles rather than simply tinting. Both run on the app's own springs.
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (pressed) 28.dp else 20.dp,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 450f),
        label = "cardCorner"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = MotionTokens.pulse,
        label = "cardScale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clickable(interactionSource = interactions, indication = null) { onClick() },
        shape = RoundedCornerShape(corner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column {
            // The preview takes the canvas's own proportion, so the grid shows the shape of the
            // work before it is opened. Fitted rather than cropped: a canvas too extreme for
            // ProjectPreviewRatio to match exactly is letterboxed inside the card it was given,
            // which keeps the thumbnail honest even where the card cannot be.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ProjectPreviewRatio.forCanvas(project.width, project.height))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                AsyncImage(
                    model = project.thumbnailPath,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            
            // Info Area
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1
                    )
                    Text(
                        text = "${project.width} × ${project.height}  ·  $lastModified",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Actions",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        shape = MaterialTheme.shapes.large,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { 
                                showMenu = false
                                onRename() 
                            },
                            // Untinted like every other non-destructive menu icon; only the
                            // Delete below it earns a colour.
                            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            onClick = { 
                                showMenu = false
                                onDelete() 
                            },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, Int, Int) -> Unit
) {
    val context = LocalContext.current
    val dm = context.resources.displayMetrics
    val screenWidthPx = dm.widthPixels
    val screenHeightPx = dm.heightPixels

    var name by remember { mutableStateOf("Untitled") }
    var width by remember { mutableStateOf(screenWidthPx.toString()) }
    var height by remember { mutableStateOf(screenHeightPx.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Text("Dimensions", style = MaterialTheme.typography.labelMedium)
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { if (it.all { c -> c.isDigit() }) width = it },
                        label = { Text("Width") },
                        modifier = Modifier.weight(1f),
                        suffix = { Text("px") }
                    )
                    OutlinedTextField(
                        value = height,
                        onValueChange = { if (it.all { c -> c.isDigit() }) height = it },
                        label = { Text("Height") },
                        modifier = Modifier.weight(1f),
                        suffix = { Text("px") }
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = { 
                            width = screenWidthPx.toString()
                            height = screenHeightPx.toString()
                        },
                        label = { Text("Screen") }
                    )
                    AssistChip(
                        onClick = { 
                            width = "2048"
                            height = "2048"
                        },
                        label = { Text("Square") }
                    )
                    AssistChip(
                        onClick = { 
                            width = "2480"
                            height = "3508"
                        },
                        label = { Text("A4") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val w = width.toIntOrNull() ?: screenWidthPx
                    val h = height.toIntOrNull() ?: screenHeightPx
                    onCreate(name, w, h)
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
