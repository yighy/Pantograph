package com.yighy.pantograph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yighy.pantograph.data.ProjectEntity
import com.yighy.pantograph.data.ProjectRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val repository: ProjectRepository) : ViewModel() {
    val projects: StateFlow<List<ProjectEntity>> = repository.allProjects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createProject(name: String, width: Int, height: Int, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repository.createProject(name, width, height)
            onCreated(id)
        }
    }

    fun deleteProject(project: ProjectEntity) {
        viewModelScope.launch {
            repository.deleteProject(project)
        }
    }

    fun renameProject(project: ProjectEntity, newName: String) {
        viewModelScope.launch {
            repository.updateProjectName(project.id, newName)
        }
    }
}
