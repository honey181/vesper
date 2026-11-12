package com.vesper.app

import com.vesper.app.data.context.LocationWeatherProvider
import com.vesper.app.data.local.DeviceDisplayHelper
import com.vesper.app.data.local.MoonPhaseCalculator
import com.vesper.app.data.remote.DirectorJsonOutput
import com.vesper.app.data.remote.PromptDirector
import com.vesper.app.domain.model.AppSettings
import com.vesper.app.domain.model.ContextSnapshot
import com.vesper.app.domain.model.GenerationRequestOverrides
import com.vesper.app.domain.model.NewsFocus
import com.vesper.app.domain.model.ScheduleMode
import com.vesper.app.domain.model.WallpaperDossier
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class VesperUnitTests {

    private val lenientJson = Json { ignoreUnknownKeys = true }

    @Test
    fun tallFlagshipDisplayMapsToSupported9By16Ratio() {
        val ratio = DeviceDisplayHelper.bestSupportedAspectRatio(
            widthPx = 1280,
            heightPx = 2856,
            override = "AUTO"
        )
        assertEquals("9:16", ratio)
    }

    @Test
    fun manualAspectRatioOverrideIsHonoredWhenSupported() {
        val ratio = DeviceDisplayHelper.bestSupportedAspectRatio(
            widthPx = 1280,
            heightPx = 2856,
            override = "2:3"
        )
        assertEquals("2:3", ratio)
    }

    @Test
    fun moonPhaseFormatsReadableIllumination() {
        val desc = MoonPhaseCalculator.describeMoonPhase(Instant.parse("2026-11-12T03:00:00Z"))
        assertTrue(desc.contains("illuminated"))
    }

    @Test
    fun directorPromptIncludesMemoryAndSubtletyRules() {
        val settings = AppSettings(subtletyLevel = 80, manualCity = "Austin")
        val snapshot = ContextSnapshot(
            isoDate = "2026-11-12",
            dayOfWeek = "Thursday",
            weekLabel = "Week 46 of 2026",
            seasonLabel = "Autumn",
            timeOfDayLabel = "dawn",
            cityRegion = "Austin",
            country = "United States",
            latitude = 30.26,
            longitude = -97.74,
            weatherSummary = "clear sky, 14°C",
            tempDeltaSummary = "6°C colder drop vs yesterday",
            moonPhase = "Waxing Crescent (12% illuminated)",
            calendarDensitySummary = null
        )
        val system = PromptDirector.buildSystemInstruction(settings, GenerationRequestOverrides())
        val user = PromptDirector.buildUserPrompt(snapshot, settings, GenerationRequestOverrides(), emptyList())

        assertTrue(system.contains("SUBTLETY OVER SPECTACLE"))
        assertTrue(user.contains("Austin, United States"))
        assertTrue(user.contains("6°C colder drop vs yesterday"))
    }

    @Test
    fun directorJsonDeserializesStrictSchemaPayload() {
        val raw = """
            {
              "clue_title": "Copper & First Frost",
              "real_world_briefing": "A sharp cold front arrived overnight alongside a major orbital launch window.",
              "encoded_clues": [
                {"visual_element": "Hairline silver arc", "real_world_meaning": "Tonight's launch trajectory"}
              ],
              "art_medium": "Matte nocturnal gouache",
              "palette_summary": "Obsidian, oxidized copper, mist silver",
              "concept_summary": "Quiet dark glass crossed by one silver arc",
              "image_prompt": "Vertical fine art composition with calm dark upper third..."
            }
        """.trimIndent()
        val parsed = lenientJson.decodeFromString<DirectorJsonOutput>(raw)
        assertEquals("Copper & First Frost", parsed.clueTitle)
        assertEquals(1, parsed.encodedClues.size)
    }

    @Test
    fun dailyPromptUses24hActiveWindowIncludesShockExceptionAndPriorBrief() {
        val settings = AppSettings(scheduleMode = ScheduleMode.DAILY, subtletyLevel = 75)
        val snapshot = ContextSnapshot(
            isoDate = "2026-11-12",
            dayOfWeek = "Thursday",
            weekLabel = "Week 46 of 2026",
            seasonLabel = "Autumn",
            timeOfDayLabel = "dawn",
            cityRegion = "Geneva",
            country = "Switzerland",
            latitude = 46.20,
            longitude = 6.14,
            weatherSummary = "overcast grey sky, 6°C · day 1°–8°C",
            tempDeltaSummary = "5°C colder drop vs yesterday",
            moonPhase = "Waxing Crescent (6% illuminated)",
            calendarDensitySummary = null,
            weatherOutlookSummary = "Forecast for today: overcast grey sky (day 1°–8°C) · Key weather cues: frost / freeze risk (0°C low)"
        )
        val prior = WallpaperDossier(
            id = "d1",
            createdAtEpochMs = 1L,
            clueTitle = "Nocturne Glass",
            realWorldBriefing = "Global climate summit talks continued alongside regional rail upgrades.",
            artMedium = "Nocturnal ink wash",
            paletteSummary = "Indigo, amber, ash",
            conceptSummary = "Distant ember arcs in mist",
            imagePromptUsed = "Minimalist vertical ink artwork...",
            textModelUsed = AppSettings.DEFAULT_TEXT_MODEL,
            imageModelUsed = AppSettings.DEFAULT_IMAGE_MODEL,
            lockImagePath = "/tmp/lock.png",
            targetWidthPx = 1280,
            targetHeightPx = 2856,
            aspectRatioUsed = "9:16",
            imageSizeUsed = "4K",
            locationLabel = "Geneva, Switzerland",
            weatherLabel = "rain, 6°C"
        )

        val user = PromptDirector.buildUserPrompt(snapshot, settings, GenerationRequestOverrides(), listOf(prior))

        assertTrue(user.contains("Morning Edition for 2026-11-12"))
        assertTrue(user.contains("CHRONICLE LITMUS TEST"))
        assertTrue(user.contains("SIGNIFICANCE BEATS NOVELTY"))
        assertTrue(user.contains("ZERO FILLER RULE"))
        assertTrue(user.contains("Prior Brief: Global climate summit talks continued"))
        assertTrue(user.contains("frost / freeze risk (0°C low)"))
        assertFalse(user.contains("Week 46 of 2026"))
        assertFalse(user.contains("Waxing Crescent (6% illuminated)"))

        val cosmosUser = PromptDirector.buildUserPrompt(
            snapshot,
            settings.copy(newsFocus = NewsFocus.COSMOS_AND_EARTH),
            GenerationRequestOverrides(),
            emptyList()
        )
        assertTrue(cosmosUser.contains("Notable Lunar Marker: Waxing Crescent (6% illuminated)"))
    }

    @Test
    fun weeklyPromptUses7DayActiveWindow() {
        val settings = AppSettings(scheduleMode = ScheduleMode.WEEKLY)
        val snapshot = ContextSnapshot(
            isoDate = "2026-11-12",
            dayOfWeek = "Thursday",
            weekLabel = "Week 46 of 2026",
            seasonLabel = "Autumn",
            timeOfDayLabel = "dawn",
            cityRegion = "Geneva",
            country = "Switzerland",
            latitude = 46.20,
            longitude = 6.14,
            weatherSummary = "clear sky, 7°C",
            tempDeltaSummary = "steady temperature vs yesterday",
            moonPhase = "Waxing Crescent (6% illuminated)",
            calendarDensitySummary = null
        )

        val user = PromptDirector.buildUserPrompt(snapshot, settings, GenerationRequestOverrides(), emptyList())

        assertTrue(user.contains("WEEKLY EDITION"))
        assertTrue(user.contains("Week 46 of 2026"))
    }

    @Test
    fun openMeteoParserExtractsDailyRangeFrostGustsAndTomorrowShift() {
        val sampleJson = """
            {
              "current": {
                "temperature_2m": 4.2,
                "weather_code": 80
              },
              "daily": {
                "weather_code": [3, 81, 0],
                "temperature_2m_max": [14.0, 9.0, 4.0],
                "temperature_2m_min": [6.0, 1.0, -2.0],
                "precipitation_sum": [1.2, 12.4, 0.0],
                "precipitation_probability_max": [30, 85, 10],
                "wind_gusts_10m_max": [24.0, 58.3, 18.0]
              }
            }
        """.trimIndent()

        val result = LocationWeatherProvider.parseOpenMeteoForecast(sampleJson)

        assertTrue(result.weatherSummary.contains("4°C"))
        assertTrue(result.weatherSummary.contains("day 1°–9°C"))
        assertEquals("5°C colder drop vs yesterday", result.tempDeltaSummary)
        assertTrue(result.outlookSummary.contains("frost / freeze risk (-2°C low)"))
        assertTrue(result.outlookSummary.contains("significant rain (12mm, 85%)"))
        assertTrue(result.outlookSummary.contains("strong wind gusts up to 58 km/h"))
        assertTrue(result.outlookSummary.contains("5°C colder shift tomorrow"))
    }

    @Test
    fun settingsSectionAndGlobalResetsRestoreDefaultsWhilePreservingApiKey() {
        val sampleKey = "sample-api-key-placeholder"
        val customized = AppSettings(
            apiKey = sampleKey,
            scheduleMode = ScheduleMode.WEEKLY,
            scheduleHour = 22,
            requireWifi = false,
            requireCharging = true,
            notifyOnAutoUpdate = false,
            imageSizeTier = "2K",
            aspectRatioOverride = "1:1",
            autoDetectDimensions = false,
            customWidthPx = 1080,
            customHeightPx = 1920,
            homeCalmBlurRadius = 5f,
            homeCalmDimAlpha = 0.1f,
            pinnedTextModel = "gemini-custom-text",
            pinnedImageModel = "gemini-custom-image",
            subtletyLevel = 25,
            manualCity = "Tokyo",
            enableSearchGrounding = false,
            customSeedNote = "rainy neon"
        )

        assertTrue(customized.hasCustomModels())
        assertTrue(customized.hasCustomSchedule())
        assertTrue(customized.hasCustomDisplay(1280, 2856))
        assertTrue(customized.hasCustomIntelligence())
        assertTrue(customized.hasAnyCustomPreferences(1280, 2856))

        val modelsReset = customized.withDefaultsForModels()
        assertFalse(modelsReset.hasCustomModels())
        assertTrue(modelsReset.hasCustomSchedule())
        assertEquals(sampleKey, modelsReset.apiKey)

        val scheduleReset = modelsReset.withDefaultsForSchedule()
        assertFalse(scheduleReset.hasCustomSchedule())
        assertTrue(scheduleReset.hasCustomDisplay(1280, 2856))

        val displayReset = scheduleReset.withDefaultsForDisplay(1280, 2856)
        assertFalse(displayReset.hasCustomDisplay(1280, 2856))
        assertTrue(displayReset.hasCustomIntelligence())

        val intelReset = displayReset.withDefaultsForIntelligence()
        assertFalse(intelReset.hasCustomIntelligence())
        assertFalse(intelReset.hasAnyCustomPreferences(1280, 2856))
        assertEquals(sampleKey, intelReset.apiKey)

        val globalReset = customized.withAllPreferencesReset(1280, 2856)
        assertFalse(globalReset.hasAnyCustomPreferences(1280, 2856))
        assertEquals(sampleKey, globalReset.apiKey)
        assertEquals(AppSettings.DEFAULT_TEXT_MODEL, globalReset.pinnedTextModel)
        assertEquals(ScheduleMode.DAILY, globalReset.scheduleMode)
    }
}
