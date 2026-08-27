package com.yighy.pantograph

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.navigation3.ViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.yighy.pantograph.data.ProjectRepository
import com.yighy.pantograph.data.PreferenceManager
import com.yighy.pantograph.drawing.DrawingViewModel
import com.yighy.pantograph.drawing.DrawingScreen

import androidx.compose.ui.platform.LocalContext

@Composable
fun MainNavigation(repository: ProjectRepository, preferenceManager: PreferenceManager) {
    val backStack = rememberNavBackStack(Route.Home)
    val viewModelStoreProvider = rememberViewModelStoreProvider()
    val context = LocalContext.current

    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.size - 1) },
        entryDecorators = listOf(ViewModelStoreNavEntryDecorator(viewModelStoreProvider)),
        entryProvider = { key ->
            when (key) {
                is Route.Home -> NavEntry(key) {
                    val homeViewModel: HomeViewModel = viewModel(
                        factory = viewModelFactory {
                            initializer { HomeViewModel(repository, context.filesDir) }
                        }
                    )
                    HomeScreen(
                        viewModel = homeViewModel,
                        preferenceManager = preferenceManager,
                        onNavigateToProject = { id ->
                            backStack.add(Route.Drawing(id))
                        }
                    )
                }
                is Route.Drawing -> NavEntry(key) {
                    val drawingViewModel: DrawingViewModel = viewModel(
                        key = "drawing_${key.projectId}",
                        factory = viewModelFactory {
                            initializer {
                                DrawingViewModel(repository, key.projectId, context.filesDir, preferenceManager, context.applicationContext)
                            }
                        }
                    )
                    DrawingScreen(
                        viewModel = drawingViewModel,
                        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.size - 1) },
                        onNavigateToSettings = { backStack.add(Route.Settings) },
                        preferenceManager = preferenceManager
                    )
                }
                is Route.Settings -> NavEntry(key) {
                    SettingsScreen(
                        preferenceManager = preferenceManager,
                        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.size - 1) }
                    )
                }
                else -> error("Unknown route")
            }
        }
    )
}
