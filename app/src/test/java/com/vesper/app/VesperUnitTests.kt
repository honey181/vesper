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
import com.vesper.app.worker.ScheduleCoordinator
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

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

        assertTrue(system.contains("ULTRA-SUBTLE SPY CIPHER"))
        assertTrue(system.contains("FULL-SPECTRUM THEME COLOR"))
        assertTrue(user.contains("Austin, United States"))
        assertTrue(user.contains("6°C colder drop vs yesterday"))
        assertTrue(user.contains("what_happened"))
        assertTrue(user.contains("happening_today"))
        assertTrue(user.contains("coming_up"))

        val directSystem = PromptDirector.buildSystemInstruction(
            settings.copy(subtletyLevel = 0),
            GenerationRequestOverrides()
        )
        assertTrue(directSystem.contains("DIRECT FIGURATIVE EDITORIAL"))
        assertTrue(directSystem.contains("Do NOT invent cryptic abstract puzzles"))
        assertFalse(directSystem.contains("observant spy leaving an oblique calling card"))
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
        assertTrue(user.contains("RIGID 80/100 IMPORTANCE THRESHOLD"))
        assertTrue(user.contains("CALM-DAY EMPTY RULE"))
        assertTrue(user.contains("Nocturnal ink wash"))
        // News text must NOT leak into the visual anti-repetition block (prevents dodging valid continuing news).
        assertFalse(user.contains("Global climate summit talks continued"))
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
    fun directorImportanceFilterEnforcesRigidThresholdAndEmptyCalmFallback() {
        val mixed = listOf(
            com.vesper.app.data.remote.DirectorEvaluatedEvent("Routine speech", "NATIONAL", 64),
            com.vesper.app.data.remote.DirectorEvaluatedEvent("UN Climate Leaders Summit", "WORLD", 92),
            com.vesper.app.data.remote.DirectorEvaluatedEvent("Historic Presidential Nomination", "NATIONAL", 84),
            com.vesper.app.data.remote.DirectorEvaluatedEvent("First 0C freeze shift", "LOCAL_WEATHER", 80),
            com.vesper.app.data.remote.DirectorEvaluatedEvent("Minor astronomy trivia", "WORLD", 35),
            com.vesper.app.data.remote.DirectorEvaluatedEvent("un climate leaders summit", "WORLD", 88)
        )
        val kept = PromptDirector.filterQualifiedEvents(mixed)
        assertEquals(3, kept.size)
        assertEquals(92, kept[0].importanceScore)
        assertEquals(84, kept[1].importanceScore)
        assertEquals(80, kept[2].importanceScore)

        val allSubThreshold = listOf(
            com.vesper.app.data.remote.DirectorEvaluatedEvent("Mild drizzle", "LOCAL_WEATHER", 60),
            com.vesper.app.data.remote.DirectorEvaluatedEvent("Local press briefing", "NATIONAL", 72)
        )
        assertTrue(PromptDirector.filterQualifiedEvents(allSubThreshold).isEmpty())

        val partialTimeframes = DirectorJsonOutput(
            evaluatedEvents = listOf(
                com.vesper.app.data.remote.DirectorEvaluatedEvent("COP31 summit", "WORLD", 90)
            ),
            whatHappened = emptyList(),
            happeningToday = listOf("COP31 climate talks entered their Leaders' Summit phase today."),
            comingUp = emptyList(),
            clueTitle = "Coastal Assembly",
            artMedium = "Egg tempera",
            paletteSummary = "Terracotta, saffron, teal",
            conceptSummary = "Sunlit coastal amphitheater",
            imagePrompt = "Vertical coastal illustration..."
        )
        val formatted = PromptDirector.formatCategorizedBriefing(partialTimeframes, allowEventBullets = true)
        assertTrue(formatted.contains("What Happened (Recent):"))
        assertTrue(formatted.contains("Nothing above the importance threshold in the recent window."))
        assertTrue(formatted.contains("Happening Today:\n• COP31 climate talks"))
        assertTrue(formatted.contains("Coming Up:\n• Nothing above the importance threshold scheduled ahead."))

        val allCalm = PromptDirector.formatCategorizedBriefing(partialTimeframes, allowEventBullets = false)
        assertTrue(allCalm.contains("• Nothing above the importance threshold unfolding today."))

        val fakeClient = com.vesper.app.data.remote.GeminiClient(okhttp3.OkHttpClient())
        val searchPrompt = fakeClient.buildGroundedSearchPrompt(
            PromptDirector.buildUserPrompt(
                ContextSnapshot(
                    isoDate = "2026-11-12",
                    dayOfWeek = "Thursday",
                    weekLabel = "Week 46 of 2026",
                    seasonLabel = "Autumn",
                    timeOfDayLabel = "dawn",
                    cityRegion = "Berlin",
                    country = "Germany",
                    latitude = null,
                    longitude = null,
                    weatherSummary = "clear, 5°C",
                    tempDeltaSummary = "steady",
                    moonPhase = "Waxing",
                    calendarDensitySummary = null
                ),
                AppSettings(),
                GenerationRequestOverrides(),
                emptyList()
            )
        )
        assertTrue(searchPrompt.contains("2026"))
        assertTrue(searchPrompt.contains("STRICT CURRENT-YEAR ONLY"))
        assertTrue(searchPrompt.contains("2024 or 2025"))

        val searchCfg = fakeClient.buildSearchGenerationConfig(useHighThinking = true).toString()
        assertTrue(searchCfg.contains("\"thinkingLevel\":\"HIGH\""))
        assertFalse(searchCfg.contains("maxOutputTokens"))

        val dirCfg = fakeClient.buildDirectorGenerationConfig(useSchema = true, useHighThinking = true).toString()
        assertTrue(dirCfg.contains("\"thinkingLevel\":\"HIGH\""))
        assertFalse(dirCfg.contains("maxOutputTokens"))
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

        assertTrue(user.contains("Next 7 days (Week 46 of 2026"))
        assertTrue(user.contains("RIGID 80/100 IMPORTANCE THRESHOLD"))
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

    @Test
    fun scheduleCoordinatorAnchorsDailyAndWeeklyTriggersWithoutDrift() {
        val zone = ZoneId.of("UTC")
        // Before 06:30 today -> stays today at 06:30:00 (fixes WorkManager flex +22h shift bug)
        val earlyMorning = ZonedDateTime.of(2026, 11, 12, 5, 54, 33, 0, zone)
        assertEquals(
            ZonedDateTime.of(2026, 11, 12, 6, 30, 0, 0, zone),
            ScheduleCoordinator.computeNextTriggerTime(earlyMorning, ScheduleMode.DAILY, 6, 30)
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 12, 6, 30, 0, 0, zone),
            ScheduleCoordinator.computeNextTriggerTime(earlyMorning, ScheduleMode.WEEKLY, 6, 30)
        )

        // Past 06:30 today -> advances cleanly to tomorrow (DAILY) or +7 days (WEEKLY)
        val afterTarget = ZonedDateTime.of(2026, 11, 12, 6, 34, 0, 0, zone)
        assertEquals(
            ZonedDateTime.of(2026, 11, 13, 6, 30, 0, 0, zone),
            ScheduleCoordinator.computeNextTriggerTime(afterTarget, ScheduleMode.DAILY, 6, 30)
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 19, 6, 30, 0, 0, zone),
            ScheduleCoordinator.computeNextTriggerTime(afterTarget, ScheduleMode.WEEKLY, 6, 30)
        )

        // Alarm firing 1s early (06:29:59) OR delayed 20m late (06:50:00) must advance to next cycle, never double-fire
        val slightlyEarlyAlarm = ZonedDateTime.of(2026, 11, 12, 6, 29, 59, 0, zone)
        assertEquals(
            ZonedDateTime.of(2026, 11, 13, 6, 30, 0, 0, zone),
            ScheduleCoordinator.computeNextTriggerTime(
                now = slightlyEarlyAlarm,
                mode = ScheduleMode.DAILY,
                hour = 6,
                minute = 30,
                advancePastCurrentOccurrence = true
            )
        )
        val idleDelayedAlarm = ZonedDateTime.of(2026, 11, 12, 6, 50, 0, 0, zone)
        assertEquals(
            ZonedDateTime.of(2026, 11, 13, 6, 30, 0, 0, zone),
            ScheduleCoordinator.computeNextTriggerTime(
                now = idleDelayedAlarm,
                mode = ScheduleMode.DAILY,
                hour = 6,
                minute = 30,
                advancePastCurrentOccurrence = true
            )
        )
        assertEquals(
            ZonedDateTime.of(2026, 11, 19, 6, 30, 0, 0, zone),
            ScheduleCoordinator.computeNextTriggerTime(
                now = idleDelayedAlarm,
                mode = ScheduleMode.WEEKLY,
                hour = 6,
                minute = 30,
                advancePastCurrentOccurrence = true
            )
        )
    }
}
