package com.vesper.app.domain.usecase

import android.content.Context
import com.vesper.app.data.context.LocationWeatherProvider
import com.vesper.app.data.local.DeviceDisplayHelper
import com.vesper.app.data.local.SettingsRepository
import com.vesper.app.data.remote.GeminiClient
import com.vesper.app.data.remote.PromptDirector
import com.vesper.app.data.wallpaper.WallpaperProcessor
import com.vesper.app.domain.model.AppSettings
import com.vesper.app.domain.model.EncodedClue
import com.vesper.app.domain.model.GenerationRequestOverrides
import com.vesper.app.domain.model.ModelPolicy
import com.vesper.app.domain.model.WallpaperDossier
import com.vesper.app.domain.model.WallpaperTarget
import java.util.UUID

class WallpaperPipelineUseCase(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val contextProvider: LocationWeatherProvider,
    private val geminiClient: GeminiClient,
    private val wallpaperProcessor: WallpaperProcessor
) {
    suspend fun generateAndOptionallyApply(
        overrides: GenerationRequestOverrides = GenerationRequestOverrides(),
        wasAutoRun: Boolean = false,
        applyToDevice: Boolean = true,
        onProgress: (String) -> Unit = {}
    ): WallpaperDossier {
        val settings = settingsRepo.getSettingsOnce()
        require(settings.apiKey.isNotBlank()) {
            "Add your Google AI Studio API key in Settings first."
        }

        // 1. Resolve target models (auto-refresh catalog once every 24h when AUTO_LATEST_STABLE is enabled)
        onProgress("Checking active Gemini models...")
        val (textModel, fallbackText, imageModel, fallbackImage) = resolveModels(settings)

        // 2. Collect real-world & device context
        onProgress("Reading local atmosphere, moon phase & city...")
        val snapshot = contextProvider.collectSnapshot(settings)
        val recentHistory = settingsRepo.getRecentDossiers(limit = 12)

        // 3. Determine exact target dimensions for this device (e.g., 1280x2856)
        val detectedSpec = DeviceDisplayHelper.readRealScreenSpec(context)
        val targetWidth = if (settings.autoDetectDimensions) detectedSpec.widthPx else settings.customWidthPx
        val targetHeight = if (settings.autoDetectDimensions) detectedSpec.heightPx else settings.customHeightPx
        val apiAspectRatio = DeviceDisplayHelper.bestSupportedAspectRatio(
            widthPx = targetWidth,
            heightPx = targetHeight,
            override = settings.aspectRatioOverride
        )
        val imageSize = overrides.imageSizeOverride ?: settings.imageSizeTier
        val effectiveTarget = overrides.targetOverride ?: settings.wallpaperTarget

        // 4. Stage 1: Creative Director + Live Google Search Grounding
        onProgress(
            if (settings.enableSearchGrounding) "Scanning this week's real-world brief..."
            else "Composing today's concept..."
        )
        val systemInstruction = PromptDirector.buildSystemInstruction(settings, overrides)
        val userPrompt = PromptDirector.buildUserPrompt(snapshot, settings, overrides, recentHistory)
        val directorResult = geminiClient.generateDirectorBrief(
            apiKey = settings.apiKey,
            preferredModelId = textModel,
            fallbackModelId = fallbackText,
            systemInstruction = systemInstruction,
            userPrompt = userPrompt,
            enableSearchGrounding = settings.enableSearchGrounding
        )

        // 5. Stage 2: Nano Banana 2.1 4K Generation
        onProgress("Rendering $imageSize artwork via $imageModel...")
        val lockGen = geminiClient.generateWallpaperImage(
            apiKey = settings.apiKey,
            preferredModelId = imageModel,
            fallbackModelId = fallbackImage,
            prompt = directorResult.output.imagePrompt,
            aspectRatio = apiAspectRatio,
            imageSize = imageSize,
            thinkingLevel = settings.thinkingLevel.apiValue
        )

        val separateHomeBytes = if (effectiveTarget == WallpaperTarget.SEPARATE_GENERATIONS) {
            onProgress("Rendering calm companion piece for Home Screen...")
            val homePrompt = PromptDirector.buildSeparateHomePrompt(
                lockBriefingTitle = directorResult.output.clueTitle,
                lockMedium = directorResult.output.artMedium,
                lockPalette = directorResult.output.paletteSummary,
                lockConcept = directorResult.output.conceptSummary
            )
            geminiClient.generateWallpaperImage(
                apiKey = settings.apiKey,
                preferredModelId = imageModel,
                fallbackModelId = fallbackImage,
                prompt = homePrompt,
                aspectRatio = apiAspectRatio,
                imageSize = imageSize,
                thinkingLevel = settings.thinkingLevel.apiValue
            ).bytes
        } else null

        // 6. On-device pixel-exact crop/fit + calm Home Screen twin
        onProgress("Fitting to ${targetWidth}x${targetHeight} display...")
        val dossierId = UUID.randomUUID().toString()
        val needsCalmTwin = effectiveTarget == WallpaperTarget.LOCK_AND_CALM_HOME ||
            effectiveTarget == WallpaperTarget.HOME_ONLY

        val paths = wallpaperProcessor.processAndSave(
            id = dossierId,
            rawBytes = lockGen.bytes,
            mimeType = lockGen.mimeType,
            targetWidthPx = targetWidth,
            targetHeightPx = targetHeight,
            exactPixelFit = settings.exactPixelFit,
            createCalmHomeTwin = needsCalmTwin,
            homeBlurRadius = settings.homeCalmBlurRadius,
            homeDimAlpha = settings.homeCalmDimAlpha,
            secondScreenRawBytes = separateHomeBytes
        )

        if (applyToDevice) {
            onProgress("Applying wallpaper to screen...")
            wallpaperProcessor.applyWallpapers(
                lockImagePath = paths.lockImagePath,
                homeImagePath = paths.homeImagePath,
                target = effectiveTarget
            )
        }

        val dossier = WallpaperDossier(
            id = dossierId,
            createdAtEpochMs = System.currentTimeMillis(),
            clueTitle = directorResult.output.clueTitle,
            realWorldBriefing = directorResult.output.realWorldBriefing,
            encodedClues = directorResult.output.encodedClues.map {
                EncodedClue(it.visualElement, it.realWorldMeaning)
            },
            groundingSources = directorResult.groundingSources,
            artMedium = directorResult.output.artMedium,
            paletteSummary = directorResult.output.paletteSummary,
            conceptSummary = directorResult.output.conceptSummary,
            imagePromptUsed = directorResult.output.imagePrompt,
            textModelUsed = directorResult.modelUsed,
            imageModelUsed = lockGen.modelUsed,
            lockImagePath = paths.lockImagePath,
            homeImagePath = paths.homeImagePath,
            rawMasterPath = paths.rawMasterPath,
            targetWidthPx = targetWidth,
            targetHeightPx = targetHeight,
            aspectRatioUsed = apiAspectRatio,
            imageSizeUsed = imageSize,
            locationLabel = "${snapshot.cityRegion}, ${snapshot.country}",
            weatherLabel = snapshot.weatherSummary,
            isFavorite = false,
            wasAutoRun = wasAutoRun,
            appliedTarget = effectiveTarget.name
        )

        settingsRepo.addDossier(dossier)
        return dossier
    }

    private data class ResolvedModels(
        val textModel: String,
        val fallbackText: String,
        val imageModel: String,
        val fallbackImage: String
    )

    private suspend fun resolveModels(settings: AppSettings): ResolvedModels {
        val pinnedText = settings.pinnedTextModel
        val pinnedImage = settings.pinnedImageModel
        if (settings.modelPolicy != ModelPolicy.AUTO_LATEST_STABLE) {
            return ResolvedModels(pinnedText, AppSettings.DEFAULT_TEXT_MODEL, pinnedImage, AppSettings.DEFAULT_IMAGE_MODEL)
        }

        var catalog = settingsRepo.getDiscoveredModelsOnce()
        val staleMs = 24L * 60L * 60L * 1000L
        if (System.currentTimeMillis() - catalog.lastCheckedEpochMs > staleMs) {
            runCatching {
                val fresh = geminiClient.fetchModelsCatalog(settings.apiKey)
                settingsRepo.saveDiscoveredModels(fresh)
                catalog = fresh
            }
        }

        val autoText = catalog.latestSuggestedTextModel ?: pinnedText
        val autoImage = catalog.latestSuggestedImageModel ?: pinnedImage
        return ResolvedModels(
            textModel = autoText,
            fallbackText = pinnedText,
            imageModel = autoImage,
            fallbackImage = pinnedImage
        )
    }
}
