package com.macsia.teatiers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macsia.teatiers.data.photos.PhotoStorageUsage
import com.macsia.teatiers.data.photos.PhotoStore
import com.macsia.teatiers.data.settings.SettingsRepository
import com.macsia.teatiers.domain.model.AppSettings
import com.macsia.teatiers.domain.model.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Exposes the persisted appearance settings (#28/#36) for both the Settings screen and the theme
 * applied at the activity root. Language is handled outside this VM via AppCompat's static locale
 * API, which recreates the activity on change.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val photoStore: PhotoStore,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /**
     * On-disk photo footprint for the Settings storage line (R4-PWR-3). A one-shot snapshot taken
     * when the screen first subscribes — an informational figure, not a live counter, so it doesn't
     * need to re-sum the directory on every photo add/delete.
     */
    val photoUsage: StateFlow<PhotoStorageUsage?> = flow { emit(photoStore.usage()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { repository.setDynamicColor(enabled) }
    }
}
