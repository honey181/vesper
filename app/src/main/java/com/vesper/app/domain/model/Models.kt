package com.vesper.app.domain.model

import kotlinx.serialization.Serializable

enum class ScheduleMode(val label: String) {
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MANUAL_ONLY("Manual only")
}

enum class WallpaperTarget(val label: String, val description: String) {
    LOCK_AND_CALM_HOME(
        "Lock Art + Calm Home Twin",
        "Full 4K art on Lock Screen; softened, icon-friendly variation derived on-device for Home."
    ),
    IDENTICAL_BOTH(
        "Same on Both Screens",
        "Applies the exact same 4K art to both Lock Screen and Home Screen."
    ),
    LOCK_ONLY(
        "Lock Screen Only",
        "Updates only the Lock Screen; leaves your Home Screen untouched."
    ),
    HOME_ONLY(
        "Home Screen Only",
        "Updates only the Home Screen."
    ),
    SEPARATE_GENERATIONS(
        "Two Separate AI Pieces",
        "Generates two distinct 4K pieces per cycle (2x API cost): expressive Lock + serene Home."
    )
}

enum class ModelPolicy(val label: String, val description: String) {
    AUTO_LATEST_STABLE(
        "Auto-Update Latest Stable",
        "Checks Gemini model catalog and automatically uses newer stable Flash & Nano Banana releases with safe fallback."
    ),
    PINNED_SAFE(
        "Pinned (Manual Switch)",
        "Stays on your selected models and notifies you in Settings when newer releases appear."
    )
}

enum class LocationMode(val label: String) {
    AUTO_COARSE("Auto (City / Region)"),
    MANUAL_CITY("Manual City")
}

enum class NewsFocus(val label: String, val guidance: String) {
    EVENING_BRIEF(
        "Morning Brief (World + Local)",
        "Act as a neutral, non-partisan editor serving an ordinary person: select the objectively most important public/world/economic/science developments (as if summarizing a date for a historical chronicle) alongside consequential national/regional news for the user's area and notable weather turns. Skip niche hobby trivia, routine sky metrics, gossip, or PR fluff."
    ),
    LOCAL_PULSE(
        "Hyper-Local & Civic",
        "Focus primarily on city/national holidays, local cultural rhythms, regional weather contrasts, and seasonal shifts."
    ),
    COSMOS_AND_EARTH(
        "Earth, Sky & Science",
        "Focus on astronomical events (meteor showers, moon phase, eclipses, solstices), weather pressure shifts, ecology, and major science milestones."
    )
}

enum class ArtStyleOption(val label: String, val directive: String) {
    AUTO_SURPRISE("Auto-Surprise (Curated)", "Choose a fresh museum-grade medium not used in recent history."),
    NOCTURNE_GOUACHE("Nocturne Ink & Gouache", "Tactile gouache and fine ink wash on archival cotton paper with quiet nocturnal lighting."),
    ARCHITECTURAL_MINIMAL("Brutalist / Bauhaus Light", "Crisp architectural geometry, concrete/stone/metal textures, and dramatic natural sun/shadow geometry."),
    AMOLED_OBSIDIAN("AMOLED Obsidian Cinema", "Deep true-black shadows, subtle obsidian/slate surfaces, and razor-thin luminous metallic or bioluminescent accents."),
    MACRO_MINERAL("Macro Mineral & Glass", "Extreme macro photography of refracted light through frost, water droplets, brushed brass, and smoked glass."),
    RISOGRAPH_PRINT("Modern Editorial Risograph", "Layered muted risograph/woodblock printmaking with subtle grain, halftone atmosphere, and restrained 3-color palette."),
    CUSTOM("Custom Style Directive", "")
}

enum class ThinkingLevelOption(val apiValue: String, val label: String) {
    MINIMAL("MINIMAL", "Minimal (Fastest)"),
    MEDIUM("MEDIUM", "Medium (Balanced)"),
    HIGH("HIGH", "High (Deepest Composition)")
}

@Serializable
data class EncodedClue(
    val visualElement: String,
    val realWorldMeaning: String
)

@Serializable
data class GroundingSource(
    val title: String,
    val url: String
)

@Serializable
data class WallpaperDossier(
    val id: String,
    val createdAtEpochMs: Long,
    val clueTitle: String,
    val realWorldBriefing: String,
    val encodedClues: List<EncodedClue> = emptyList(),
    val groundingSources: List<GroundingSource> = emptyList(),
    val artMedium: String,
    val paletteSummary: String,
    val conceptSummary: String,
    val imagePromptUsed: String,
    val textModelUsed: String,
    val imageModelUsed: String,
    val lockImagePath: String,
    val homeImagePath: String? = null,
    val rawMasterPath: String? = null,
    val targetWidthPx: Int,
    val targetHeightPx: Int,
    val aspectRatioUsed: String,
    val imageSizeUsed: String,
    val locationLabel: String,
    val weatherLabel: String,
    val isFavorite: Boolean = false,
    val wasAutoRun: Boolean = false,
    val appliedTarget: String = WallpaperTarget.LOCK_AND_CALM_HOME.name
)

@Serializable
data class DiscoveredModelsCatalog(
    val textModels: List<String> = emptyList(),
    val imageModels: List<String> = emptyList(),
    val latestSuggestedTextModel: String? = null,
    val latestSuggestedImageModel: String? = null,
    val lastCheckedEpochMs: Long = 0L
)

data class AppSettings(
    val apiKey: String = "",
    val scheduleMode: ScheduleMode = ScheduleMode.DAILY,
    val scheduleHour: Int = 6,
    val scheduleMinute: Int = 30,
    val requireWifi: Boolean = true,
    val requireCharging: Boolean = false,
    val notifyOnAutoUpdate: Boolean = true,
    val wallpaperTarget: WallpaperTarget = WallpaperTarget.LOCK_AND_CALM_HOME,
    val homeCalmBlurRadius: Float = 18f,
    val homeCalmDimAlpha: Float = 0.32f,
    val autoDetectDimensions: Boolean = true,
    val customWidthPx: Int = 1280,
    val customHeightPx: Int = 2856,
    val exactPixelFit: Boolean = true,
    val aspectRatioOverride: String = "AUTO",
    val imageSizeTier: String = "4K",
    val thinkingLevel: ThinkingLevelOption = ThinkingLevelOption.MEDIUM,
    val modelPolicy: ModelPolicy = ModelPolicy.AUTO_LATEST_STABLE,
    val pinnedTextModel: String = DEFAULT_TEXT_MODEL,
    val pinnedImageModel: String = DEFAULT_IMAGE_MODEL,
    val locationMode: LocationMode = LocationMode.AUTO_COARSE,
    val manualCity: String = "",
    val enableSearchGrounding: Boolean = true,
    val enableWeatherContext: Boolean = true,
    val enableCalendarDensity: Boolean = false,
    val subtletyLevel: Int = 75,
    val artStyleOption: ArtStyleOption = ArtStyleOption.AUTO_SURPRISE,
    val customStyleDirective: String = "",
    val newsFocus: NewsFocus = NewsFocus.EVENING_BRIEF,
    val customSeedNote: String = ""
) {
    fun withDefaultsForModels(): AppSettings = AppSettings().let { d ->
        copy(
            modelPolicy = d.modelPolicy,
            pinnedTextModel = d.pinnedTextModel,
            pinnedImageModel = d.pinnedImageModel,
            thinkingLevel = d.thinkingLevel
        )
    }

    fun withDefaultsForSchedule(): AppSettings = AppSettings().let { d ->
        copy(
            scheduleMode = d.scheduleMode,
            scheduleHour = d.scheduleHour,
            scheduleMinute = d.scheduleMinute,
            requireWifi = d.requireWifi,
            requireCharging = d.requireCharging,
            notifyOnAutoUpdate = d.notifyOnAutoUpdate
        )
    }

    fun withDefaultsForDisplay(detectedWidthPx: Int = 1280, detectedHeightPx: Int = 2856): AppSettings =
        AppSettings().let { d ->
            copy(
                autoDetectDimensions = d.autoDetectDimensions,
                customWidthPx = detectedWidthPx,
                customHeightPx = detectedHeightPx,
                exactPixelFit = d.exactPixelFit,
                imageSizeTier = d.imageSizeTier,
                aspectRatioOverride = d.aspectRatioOverride,
                wallpaperTarget = d.wallpaperTarget,
                homeCalmBlurRadius = d.homeCalmBlurRadius,
                homeCalmDimAlpha = d.homeCalmDimAlpha
            )
        }

    fun withDefaultsForIntelligence(): AppSettings = AppSettings().let { d ->
        copy(
            newsFocus = d.newsFocus,
            subtletyLevel = d.subtletyLevel,
            locationMode = d.locationMode,
            manualCity = d.manualCity,
            enableSearchGrounding = d.enableSearchGrounding,
            enableWeatherContext = d.enableWeatherContext,
            enableCalendarDensity = d.enableCalendarDensity,
            artStyleOption = d.artStyleOption,
            customStyleDirective = d.customStyleDirective,
            customSeedNote = d.customSeedNote
        )
    }

    fun withAllPreferencesReset(detectedWidthPx: Int = 1280, detectedHeightPx: Int = 2856): AppSettings =
        AppSettings(
            apiKey = apiKey,
            customWidthPx = detectedWidthPx,
            customHeightPx = detectedHeightPx
        )

    fun hasCustomModels(): Boolean = AppSettings().let { d ->
        modelPolicy != d.modelPolicy ||
            pinnedTextModel != d.pinnedTextModel ||
            pinnedImageModel != d.pinnedImageModel ||
            thinkingLevel != d.thinkingLevel
    }

    fun hasCustomSchedule(): Boolean = AppSettings().let { d ->
        scheduleMode != d.scheduleMode ||
            scheduleHour != d.scheduleHour ||
            scheduleMinute != d.scheduleMinute ||
            requireWifi != d.requireWifi ||
            requireCharging != d.requireCharging ||
            notifyOnAutoUpdate != d.notifyOnAutoUpdate
    }

    fun hasCustomDisplay(detectedWidthPx: Int = 1280, detectedHeightPx: Int = 2856): Boolean =
        AppSettings().let { d ->
            autoDetectDimensions != d.autoDetectDimensions ||
                customWidthPx != detectedWidthPx ||
                customHeightPx != detectedHeightPx ||
                exactPixelFit != d.exactPixelFit ||
                imageSizeTier != d.imageSizeTier ||
                aspectRatioOverride != d.aspectRatioOverride ||
                wallpaperTarget != d.wallpaperTarget ||
                kotlin.math.abs(homeCalmBlurRadius - d.homeCalmBlurRadius) > 0.05f ||
                kotlin.math.abs(homeCalmDimAlpha - d.homeCalmDimAlpha) > 0.005f
        }

    fun hasCustomIntelligence(): Boolean = AppSettings().let { d ->
        newsFocus != d.newsFocus ||
            subtletyLevel != d.subtletyLevel ||
            locationMode != d.locationMode ||
            manualCity != d.manualCity ||
            enableSearchGrounding != d.enableSearchGrounding ||
            enableWeatherContext != d.enableWeatherContext ||
            enableCalendarDensity != d.enableCalendarDensity ||
            artStyleOption != d.artStyleOption ||
            customStyleDirective != d.customStyleDirective ||
            customSeedNote != d.customSeedNote
    }

    fun hasAnyCustomPreferences(detectedWidthPx: Int = 1280, detectedHeightPx: Int = 2856): Boolean =
        hasCustomModels() ||
            hasCustomSchedule() ||
            hasCustomDisplay(detectedWidthPx, detectedHeightPx) ||
            hasCustomIntelligence()

    companion object {
        const val DEFAULT_TEXT_MODEL = "gemini-3.8-flash"
        const val DEFAULT_IMAGE_MODEL = "gemini-nano-banana-2.1"
    }
}

data class ContextSnapshot(
    val isoDate: String,
    val dayOfWeek: String,
    val weekLabel: String,
    val seasonLabel: String,
    val timeOfDayLabel: String,
    val cityRegion: String,
    val country: String,
    val latitude: Double?,
    val longitude: Double?,
    val weatherSummary: String,
    val tempDeltaSummary: String,
    val moonPhase: String,
    val calendarDensitySummary: String?,
    val weatherOutlookSummary: String = ""
)

data class GenerationRequestOverrides(
    val customSeedOverride: String? = null,
    val styleOverride: ArtStyleOption? = null,
    val customStyleTextOverride: String? = null,
    val subtletyOverride: Int? = null,
    val targetOverride: WallpaperTarget? = null,
    val imageSizeOverride: String? = null
)
