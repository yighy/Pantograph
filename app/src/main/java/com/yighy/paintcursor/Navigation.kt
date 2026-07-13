package com.yighy.paintcursor

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Home : Route
    
    @Serializable
    data class Drawing(val projectId: Long) : Route

    @Serializable
    data object Settings : Route
}
