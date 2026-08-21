package com.yighy.pantograph

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.yighy.pantograph.data.AppDatabase
import com.yighy.pantograph.data.AppTheme
import com.yighy.pantograph.data.PreferenceManager
import com.yighy.pantograph.data.ProjectRepository
import com.yighy.pantograph.ui.theme.MyApplication3Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = ProjectRepository(database.projectDao())
        val preferenceManager = PreferenceManager(applicationContext)

        setContent {
            val appTheme by preferenceManager.appTheme.collectAsState(initial = AppTheme.SYSTEM)
            val hideStatusBar by preferenceManager.hideStatusBar.collectAsState(initial = true)
            val dynamicColor by preferenceManager.dynamicColor.collectAsState(initial = true)
            
            val darkTheme = when (appTheme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }
            
            LaunchedEffect(hideStatusBar, darkTheme) {
                val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
                
                // Handle Visibility
                if (hideStatusBar) {
                    windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
                    windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
                }
                
                // Handle Icon Colors (Appearance)
                // If it's NOT dark theme, we want dark icons (AppearanceLight = true)
                windowInsetsController.isAppearanceLightStatusBars = !darkTheme
                windowInsetsController.isAppearanceLightNavigationBars = !darkTheme
            }

            MyApplication3Theme(appTheme = appTheme, dynamicColor = dynamicColor) {
                MainNavigation(repository = repository, preferenceManager = preferenceManager)
            }
        }
    }
}
