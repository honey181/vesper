package com.vesper.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vesper.app.AppContainer
import com.vesper.app.data.local.DeviceDisplayHelper
import com.vesper.app.data.local.ScreenSpec
import com.vesper.app.domain.model.AppSettings
import com.vesper.app.domain.model.DiscoveredModelsCatalog
import com.vesper.app.domain.model.GenerationRequestOverrides
import com.vesper.app.domain.model.ModelPolicy
import com.vesper.app.domain.model.WallpaperDossier
import com.vesper.app.domain.model.WallpaperTarget
import com.vesper.app.worker.ScheduleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VesperUiState(
    val settings: AppSettings = AppSettings(),
    val discoveredModels: DiscoveredModelsCatalog = DiscoveredModelsCatalog(),
    val dossiers: List<WallpaperDossier> = emptyList(),
    val deviceScreenSpec: ScreenSpec = ScreenSpec(1280, 2856, 1280f / 2856f),
    val isGenerating: Boolean = false,
    val generationProgress: String = "",
    val isRefreshingModels: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
) {
    val latestDossier: WallpaperDossier?
        get() = dossiers.firstOrNull()
}

class MainViewModel(
    private val appContext: Context,
    private val container: AppContainer
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        VesperUiState(deviceScreenSpec = DeviceDisplayHelper.readRealScreenSpec(appContext))
    )
    val uiState: StateFlow<VesperUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                container.settingsRepository.settingsFlow,
                container.settingsRepository.discoveredModelsFlow,
                container.settingsRepository.dossiersFlow
            ) { settings, catalog, dossiers ->
                Triple(settings, catalog, dossiers)
            }.collect { (settings, catalog, dossiers) ->
                _uiState.update {
                    it.copy(
                        settings = settings,
                        discoveredModels = catalog,
                        dossiers = dossiers
                    )
                }
            }
        }
    }

    fun generateNow(overrides: GenerationRequestOverrides = GenerationRequestOverrides()) {
        if (_uiState.value.isGenerating) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isGenerating = true,
                    generationProgress = "Preparing Vesper pipeline...",
                    errorMessage = null
                )
            }
            try {
                val dossier = container.wallpaperPipeline.generateAndOptionallyApply(
                    overrides = overrides,
                    wasAutoRun = false,
                    applyToDevice = true,
                    onProgress = { msg ->
                        _uiState.update { it.copy(generationProgress = msg) }
                    }
                )
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        generationProgress = "",
                        statusMessage = "Applied \"${dossier.clueTitle}\""
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isGenerating = false,
                        generationProgress = "",
                        errorMessage = e.message ?: "Generation failed."
                    )
                }
            }
        }
    }

    fun applyDossier(dossier: WallpaperDossier, target: WallpaperTarget) {
        viewModelScope.launch {
            runCatching {
                container.wallpaperProcessor.applyWallpapers(
                    lockImagePath = dossier.lockImagePath,
                    homeImagePath = dossier.homeImagePath,
                    target = target
                )
            }.onSuccess {
                _uiState.update { it.copy(statusMessage = "Applied \"${dossier.clueTitle}\" (${target.label})") }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Failed to apply wallpaper.") }
            }
        }
    }

    fun saveDossierToGallery(dossier: WallpaperDossier) {
        viewModelScope.launch {
            val source = dossier.rawMasterPath ?: dossier.lockImagePath
            val ok = container.wallpaperProcessor.exportToGallery(source, dossier.clueTitle)
            _uiState.update {
                if (ok) it.copy(statusMessage = "Saved to Pictures/Vesper")
                else it.copy(errorMessage = "Could not export image to Pictures/Vesper.")
            }
        }
    }

    fun toggleFavorite(dossierId: String) {
        viewModelScope.launch {
            container.settingsRepository.toggleFavorite(dossierId)
        }
    }

    fun deleteDossier(dossierId: String) {
        viewModelScope.launch {
            container.settingsRepository.deleteDossier(dossierId)
            _uiState.update { it.copy(statusMessage = "Removed from archive") }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            container.settingsRepository.updateSettings(transform)
            val updated = container.settingsRepository.getSettingsOnce()
            ScheduleManager.syncSchedule(appContext, updated)
        }
    }

    fun refreshModelsCatalog(testKeyFeedback: Boolean = true) {
        val key = _uiState.value.settings.apiKey.trim()
        if (key.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Enter your Google AI Studio API key first.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingModels = true, errorMessage = null) }
            runCatching {
                val catalog = container.geminiClient.fetchModelsCatalog(key)
                container.settingsRepository.saveDiscoveredModels(catalog)
                val settings = container.settingsRepository.getSettingsOnce()
                if (settings.modelPolicy == ModelPolicy.AUTO_LATEST_STABLE) {
                    container.settingsRepository.updateSettings { current ->
                        current.copy(
                            pinnedTextModel = catalog.latestSuggestedTextModel ?: current.pinnedTextModel,
                            pinnedImageModel = catalog.latestSuggestedImageModel ?: current.pinnedImageModel
                        )
                    }
                }
                catalog
            }.onSuccess { catalog ->
                _uiState.update {
                    it.copy(
                        isRefreshingModels = false,
                        statusMessage = if (testKeyFeedback) {
                            "Key verified · Found ${catalog.textModels.size} Flash/Pro & ${catalog.imageModels.size} Image models"
                        } else null
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isRefreshingModels = false,
                        errorMessage = err.message ?: "Could not reach Gemini models endpoint."
                    )
                }
            }
        }
    }

    fun showStatus(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    fun consumeMessages() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    class Factory(
        private val appContext: Context,
        private val container: AppContainer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(appContext, container) as T
        }
    }
}
