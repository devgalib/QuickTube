package com.edureminder.quicktube.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.data.LibraryRepository
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.model.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repo: SettingsRepository,
    library: LibraryRepository,
) : ViewModel() {
    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val trashCount = library.trash.map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val vaultCount = library.downloads.map { l -> l.count { it.inVault } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val defaultDir get() = repo.defaultDownloadDir

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { repo.update(transform) }
}
