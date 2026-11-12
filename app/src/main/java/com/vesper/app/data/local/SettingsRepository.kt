package com.vesper.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vesper.app.domain.model.AppSettings
import com.vesper.app.domain.model.ArtStyleOption
import com.vesper.app.domain.model.DiscoveredModelsCatalog
import com.vesper.app.domain.model.LocationMode
import com.vesper.app.domain.model.ModelPolicy
import com.vesper.app.domain.model.NewsFocus
import com.vesper.app.domain.model.ScheduleMode
import com.vesper.app.domain.model.ThinkingLevelOption
import com.vesper.app.domain.model.WallpaperDossier
import com.vesper.app.domain.model.WallpaperTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vesper_settings")

class SettingsRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val dossierMutex = Mutex()
    private val dossierFile = File(context.filesDir, "vesper_dossiers.json")
    private val _dossiers = MutableStateFlow<List<WallpaperDossier>>(emptyList())
    val dossiersFlow: Flow<List<WallpaperDossier>> = _dossiers.asStateFlow()

    init {
        loadDossiersFromDiskSync()
    }

    private object Keys {
        val API_KEY_ENC = stringPreferencesKey("api_key_enc")
        val SCHEDULE_MODE = stringPreferencesKey("schedule_mode")
        val SCHEDULE_HOUR = intPreferencesKey("schedule_hour")
        val SCHEDULE_MINUTE = intPreferencesKey("schedule_minute")
        val REQUIRE_WIFI = booleanPreferencesKey("require_wifi")
        val REQUIRE_CHARGING = booleanPreferencesKey("require_charging")
        val NOTIFY_ON_AUTO = booleanPreferencesKey("notify_on_auto")
        val WALLPAPER_TARGET = stringPreferencesKey("wallpaper_target")
        val HOME_CALM_BLUR = floatPreferencesKey("home_calm_blur")
        val HOME_CALM_DIM = floatPreferencesKey("home_calm_dim")
        val AUTO_DETECT_DIMS = booleanPreferencesKey("auto_detect_dims")
        val CUSTOM_WIDTH = intPreferencesKey("custom_width")
        val CUSTOM_HEIGHT = intPreferencesKey("custom_height")
        val EXACT_PIXEL_FIT = booleanPreferencesKey("exact_pixel_fit")
        val ASPECT_OVERRIDE = stringPreferencesKey("aspect_override")
        val IMAGE_SIZE_TIER = stringPreferencesKey("image_size_tier")
        val THINKING_LEVEL = stringPreferencesKey("thinking_level")
        val MODEL_POLICY = stringPreferencesKey("model_policy")
        val PINNED_TEXT_MODEL = stringPreferencesKey("pinned_text_model")
        val PINNED_IMAGE_MODEL = stringPreferencesKey("pinned_image_model")
        val LOCATION_MODE = stringPreferencesKey("location_mode")
        val MANUAL_CITY = stringPreferencesKey("manual_city")
        val ENABLE_GROUNDING = booleanPreferencesKey("enable_grounding")
        val ENABLE_WEATHER = booleanPreferencesKey("enable_weather")
        val ENABLE_CALENDAR = booleanPreferencesKey("enable_calendar")
        val SUBTLETY_LEVEL = intPreferencesKey("subtlety_level")
        val ART_STYLE = stringPreferencesKey("art_style")
        val CUSTOM_STYLE_TEXT = stringPreferencesKey("custom_style_text")
        val NEWS_FOCUS = stringPreferencesKey("news_focus")
        val CUSTOM_SEED_NOTE = stringPreferencesKey("custom_seed_note")
        val DISCOVERED_MODELS_JSON = stringPreferencesKey("discovered_models_json")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        val screenSpec = DeviceDisplayHelper.readRealScreenSpec(context)
        AppSettings(
            apiKey = CryptoKeyStore.decrypt(prefs[Keys.API_KEY_ENC].orEmpty()),
            scheduleMode = enumOrDefault(prefs[Keys.SCHEDULE_MODE], ScheduleMode.DAILY),
            scheduleHour = prefs[Keys.SCHEDULE_HOUR] ?: 6,
            scheduleMinute = prefs[Keys.SCHEDULE_MINUTE] ?: 30,
            requireWifi = prefs[Keys.REQUIRE_WIFI] ?: true,
            requireCharging = prefs[Keys.REQUIRE_CHARGING] ?: false,
            notifyOnAutoUpdate = prefs[Keys.NOTIFY_ON_AUTO] ?: true,
            wallpaperTarget = enumOrDefault(prefs[Keys.WALLPAPER_TARGET], WallpaperTarget.LOCK_AND_CALM_HOME),
            homeCalmBlurRadius = prefs[Keys.HOME_CALM_BLUR] ?: 18f,
            homeCalmDimAlpha = prefs[Keys.HOME_CALM_DIM] ?: 0.32f,
            autoDetectDimensions = prefs[Keys.AUTO_DETECT_DIMS] ?: true,
            customWidthPx = prefs[Keys.CUSTOM_WIDTH] ?: screenSpec.widthPx,
            customHeightPx = prefs[Keys.CUSTOM_HEIGHT] ?: screenSpec.heightPx,
            exactPixelFit = prefs[Keys.EXACT_PIXEL_FIT] ?: true,
            aspectRatioOverride = prefs[Keys.ASPECT_OVERRIDE] ?: "AUTO",
            imageSizeTier = prefs[Keys.IMAGE_SIZE_TIER] ?: "4K",
            thinkingLevel = enumOrDefault(prefs[Keys.THINKING_LEVEL], ThinkingLevelOption.MEDIUM),
            modelPolicy = enumOrDefault(prefs[Keys.MODEL_POLICY], ModelPolicy.AUTO_LATEST_STABLE),
            pinnedTextModel = prefs[Keys.PINNED_TEXT_MODEL] ?: AppSettings.DEFAULT_TEXT_MODEL,
            pinnedImageModel = prefs[Keys.PINNED_IMAGE_MODEL] ?: AppSettings.DEFAULT_IMAGE_MODEL,
            locationMode = enumOrDefault(prefs[Keys.LOCATION_MODE], LocationMode.AUTO_COARSE),
            manualCity = prefs[Keys.MANUAL_CITY] ?: "",
            enableSearchGrounding = prefs[Keys.ENABLE_GROUNDING] ?: true,
            enableWeatherContext = prefs[Keys.ENABLE_WEATHER] ?: true,
            enableCalendarDensity = prefs[Keys.ENABLE_CALENDAR] ?: false,
            subtletyLevel = prefs[Keys.SUBTLETY_LEVEL] ?: 75,
            artStyleOption = enumOrDefault(prefs[Keys.ART_STYLE], ArtStyleOption.AUTO_SURPRISE),
            customStyleDirective = prefs[Keys.CUSTOM_STYLE_TEXT] ?: "",
            newsFocus = enumOrDefault(prefs[Keys.NEWS_FOCUS], NewsFocus.EVENING_BRIEF),
            customSeedNote = prefs[Keys.CUSTOM_SEED_NOTE] ?: ""
        )
    }

    val discoveredModelsFlow: Flow<DiscoveredModelsCatalog> = context.dataStore.data.map { prefs ->
        val raw = prefs[Keys.DISCOVERED_MODELS_JSON]
        if (raw.isNullOrBlank()) DiscoveredModelsCatalog()
        else runCatching { json.decodeFromString<DiscoveredModelsCatalog>(raw) }
            .getOrDefault(DiscoveredModelsCatalog())
    }

    suspend fun getSettingsOnce(): AppSettings = settingsFlow.first()

    suspend fun getDiscoveredModelsOnce(): DiscoveredModelsCatalog = discoveredModelsFlow.first()

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val current = getSettingsOnce()
        val updated = transform(current)
        context.dataStore.edit { prefs ->
            prefs[Keys.API_KEY_ENC] = CryptoKeyStore.encrypt(updated.apiKey.trim())
            prefs[Keys.SCHEDULE_MODE] = updated.scheduleMode.name
            prefs[Keys.SCHEDULE_HOUR] = updated.scheduleHour.coerceIn(0, 23)
            prefs[Keys.SCHEDULE_MINUTE] = updated.scheduleMinute.coerceIn(0, 59)
            prefs[Keys.REQUIRE_WIFI] = updated.requireWifi
            prefs[Keys.REQUIRE_CHARGING] = updated.requireCharging
            prefs[Keys.NOTIFY_ON_AUTO] = updated.notifyOnAutoUpdate
            prefs[Keys.WALLPAPER_TARGET] = updated.wallpaperTarget.name
            prefs[Keys.HOME_CALM_BLUR] = updated.homeCalmBlurRadius.coerceIn(0f, 32f)
            prefs[Keys.HOME_CALM_DIM] = updated.homeCalmDimAlpha.coerceIn(0f, 0.75f)
            prefs[Keys.AUTO_DETECT_DIMS] = updated.autoDetectDimensions
            prefs[Keys.CUSTOM_WIDTH] = updated.customWidthPx.coerceIn(320, 8192)
            prefs[Keys.CUSTOM_HEIGHT] = updated.customHeightPx.coerceIn(320, 8192)
            prefs[Keys.EXACT_PIXEL_FIT] = updated.exactPixelFit
            prefs[Keys.ASPECT_OVERRIDE] = updated.aspectRatioOverride
            prefs[Keys.IMAGE_SIZE_TIER] = updated.imageSizeTier
            prefs[Keys.THINKING_LEVEL] = updated.thinkingLevel.name
            prefs[Keys.MODEL_POLICY] = updated.modelPolicy.name
            prefs[Keys.PINNED_TEXT_MODEL] = updated.pinnedTextModel.trim().ifBlank { AppSettings.DEFAULT_TEXT_MODEL }
            prefs[Keys.PINNED_IMAGE_MODEL] = updated.pinnedImageModel.trim().ifBlank { AppSettings.DEFAULT_IMAGE_MODEL }
            prefs[Keys.LOCATION_MODE] = updated.locationMode.name
            prefs[Keys.MANUAL_CITY] = updated.manualCity.trim()
            prefs[Keys.ENABLE_GROUNDING] = updated.enableSearchGrounding
            prefs[Keys.ENABLE_WEATHER] = updated.enableWeatherContext
            prefs[Keys.ENABLE_CALENDAR] = updated.enableCalendarDensity
            prefs[Keys.SUBTLETY_LEVEL] = updated.subtletyLevel.coerceIn(0, 100)
            prefs[Keys.ART_STYLE] = updated.artStyleOption.name
            prefs[Keys.CUSTOM_STYLE_TEXT] = updated.customStyleDirective.trim()
            prefs[Keys.NEWS_FOCUS] = updated.newsFocus.name
            prefs[Keys.CUSTOM_SEED_NOTE] = updated.customSeedNote.trim()
        }
    }

    suspend fun saveDiscoveredModels(catalog: DiscoveredModelsCatalog) {
        context.dataStore.edit { prefs ->
            prefs[Keys.DISCOVERED_MODELS_JSON] = json.encodeToString(catalog)
        }
    }

    suspend fun addDossier(dossier: WallpaperDossier) {
        dossierMutex.withLock {
            val updated = listOf(dossier) + _dossiers.value.filterNot { it.id == dossier.id }
            _dossiers.value = updated
            persistDossiers(updated)
        }
    }

    suspend fun toggleFavorite(dossierId: String) {
        dossierMutex.withLock {
            val updated = _dossiers.value.map {
                if (it.id == dossierId) it.copy(isFavorite = !it.isFavorite) else it
            }
            _dossiers.value = updated
            persistDossiers(updated)
        }
    }

    suspend fun deleteDossier(dossierId: String) {
        dossierMutex.withLock {
            val target = _dossiers.value.find { it.id == dossierId }
            val updated = _dossiers.value.filterNot { it.id == dossierId }
            _dossiers.value = updated
            persistDossiers(updated)
            target?.let {
                runCatching {
                    File(it.lockImagePath).delete()
                    it.homeImagePath?.let { path -> File(path).delete() }
                    it.rawMasterPath?.let { path -> File(path).delete() }
                }
            }
        }
    }

    suspend fun getRecentDossiers(limit: Int = 14): List<WallpaperDossier> =
        dossierMutex.withLock { _dossiers.value.take(limit) }

    private fun loadDossiersFromDiskSync() {
        if (!dossierFile.exists()) return
        runCatching {
            val list = json.decodeFromString<List<WallpaperDossier>>(dossierFile.readText())
            _dossiers.value = list.sortedByDescending { it.createdAtEpochMs }
        }
    }

    private suspend fun persistDossiers(list: List<WallpaperDossier>) = withContext(Dispatchers.IO) {
        val tempFile = File(context.filesDir, "vesper_dossiers.json.tmp")
        tempFile.writeText(json.encodeToString(list))
        if (!tempFile.renameTo(dossierFile)) {
            dossierFile.writeText(tempFile.readText())
            tempFile.delete()
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, fallback: T): T {
        if (raw.isNullOrBlank()) return fallback
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)
    }
}
