package com.vesper.app.data.remote

import com.vesper.app.domain.model.AppSettings
import com.vesper.app.domain.model.ArtStyleOption
import com.vesper.app.domain.model.ContextSnapshot
import com.vesper.app.domain.model.GenerationRequestOverrides
import com.vesper.app.domain.model.NewsFocus
import com.vesper.app.domain.model.ScheduleMode
import com.vesper.app.domain.model.WallpaperDossier

object PromptDirector {

    const val IMPORTANCE_THRESHOLD = 80

    fun filterQualifiedEvents(
        events: List<DirectorEvaluatedEvent>,
        threshold: Int = IMPORTANCE_THRESHOLD
    ): List<DirectorEvaluatedEvent> {
        val seen = HashSet<String>()
        return events
            .asSequence()
            .filter { it.topic.isNotBlank() && it.importanceScore >= threshold }
            .sortedByDescending { it.importanceScore }
            .filter { seen.add(it.topic.trim().lowercase()) }
            .toList()
    }

    fun formatCategorizedBriefing(
        output: DirectorJsonOutput,
        allowEventBullets: Boolean = true
    ): String {
        val past = if (allowEventBullets) cleanBulletItems(output.whatHappened) else emptyList()
        val today = if (allowEventBullets) cleanBulletItems(output.happeningToday) else emptyList()
        val upcoming = if (allowEventBullets) cleanBulletItems(output.comingUp) else emptyList()

        // Preserve legacy unstructured briefs only if no categorized fields or scored events exist.
        val hasStructuredData = output.whatHappened.isNotEmpty() ||
            output.happeningToday.isNotEmpty() ||
            output.comingUp.isNotEmpty() ||
            output.evaluatedEvents.isNotEmpty()

        if (!hasStructuredData && output.realWorldBriefing.isNotBlank()) {
            return output.realWorldBriefing.trim()
        }

        return buildString {
            appendSection("What Happened (Recent)", past, "Nothing above the importance threshold in the recent window.")
            appendLine()
            appendSection("Happening Today", today, "Nothing above the importance threshold unfolding today.")
            appendLine()
            appendSection("Coming Up", upcoming, "Nothing above the importance threshold scheduled ahead.")
        }.trim()
    }

    private fun cleanBulletItems(raw: List<String>): List<String> =
        raw.map { it.trim().removePrefix("•").removePrefix("-").trim() }
            .filter { it.isNotBlank() }

    private fun StringBuilder.appendSection(title: String, items: List<String>, emptyNote: String) {
        appendLine("$title:")
        if (items.isEmpty()) {
            appendLine("• $emptyNote")
        } else {
            items.forEach { appendLine("• $it") }
        }
    }

    fun buildSystemInstruction(settings: AppSettings, overrides: GenerationRequestOverrides): String {
        val subtlety = overrides.subtletyOverride ?: settings.subtletyLevel
        val styleOption = overrides.styleOverride ?: settings.artStyleOption
        val customStyleText = overrides.customStyleTextOverride ?: settings.customStyleDirective

        val visualPhilosophyBlock = when {
            subtlety >= 70 -> """
VISUAL PHILOSOPHY ("ULTRA-SUBTLE SPY CIPHER", $subtlety/100):
1. Encode real-world awareness like an observant spy leaving an oblique calling card: use subtle material cues, light temperature, sky azimuth, geometric counts, tension between elements, and atmospheric hints. Never depict literal holidays, flags, text, numbers, UI chrome, or news scenes.
2. Contrastive Examples (follow the GOOD pattern):
   - BAD: US Independence Day -> Fireworks bursting over the Statue of Liberty.
   - GOOD: US Independence Day -> Warm linen and deep carmine lacquer wash with 13 tiny burnished gold-leaf ember arcs drifting over sunlit sandstone and verdigris bronze.
   - BAD: Major election + freeze -> A ballot box covered in snow.
   - GOOD: Major election + freeze -> Sharp geometric frost crystal dividing warm saffron ochre plaster and deep forest emerald glass with a hairline brass inlaid seam.
   - BAD: UN climate summit -> World map with flags.
   - GOOD: UN climate summit -> Velvet burgundy and burnt sienna horizon crossed by a crisp copper-gold atmospheric band under calm twilight plum space.
""".trimIndent()
            subtlety >= 35 -> """
VISUAL PHILOSOPHY ("POETIC EDITORIAL METAPHOR", $subtlety/100):
1. Blend recognizable real-world objects, architecture, landscapes, or weather with elevated editorial symbolism so a viewer sees thoughtful visual storytelling rather than a dry riddle.
2. Never include readable text, letters, numbers, logos, or tacky cliché icons.
3. Contrastive Examples (follow the GOOD pattern):
   - BAD: Climate summit + cold turn -> Cartoon globe wearing a scarf.
   - GOOD: Climate summit + cold turn -> Coastal Mediterranean terrace stone etched with sunlit thermal contours beside frost-rimmed botanicals glowing against warm amber sky.
   - BAD: European leaders summit -> Suitcases and microphones.
   - GOOD: European leaders summit -> Warm sunlit colonnade of varied regional stones meeting around a tranquil emerald fountain courtyard at dawn.
""".trimIndent()
            else -> """
VISUAL PHILOSOPHY ("DIRECT FIGURATIVE EDITORIAL", $subtlety/100):
1. IMPORTANT: Subtlety is set LOW ($subtlety/100). Do NOT invent cryptic abstract puzzles, stacked geometric slabs, counting lintels, dots, or "400 IQ" riddles.
2. Paint CLEAR, IMMEDIATELY RECOGNIZABLE, gallery-grade figurative scenes, real landscapes, iconic regional architecture, or unmistakable concrete visual storytelling matching the events and place — so someone glancing at the wallpaper + reading the briefing immediately sees what it depicts.
3. Keep high artistic taste: still zero text, zero letters/numbers, zero news banners or tacky clip-art, while keeping subjects clear and concrete rather than obscure geometry.
4. Contrastive Examples (follow the GOOD pattern):
   - BAD (too cryptic for low subtlety): Coastal UN climate summit + European summit -> Abstract copper disc resting on 9 terracotta lintels.
   - GOOD (direct & unmistakable): Coastal UN climate summit + European summit -> Sunlit coastal glass amphitheater & cypress cliffs overlooking a warm turquoise Antalya bay linked by a grand classical European limestone arched bridge under rich saffron-amber dawn clouds.
   - BAD (too cryptic): Overnight 0°C freeze + autumn evening -> Pale zinc slab with micro-dots.
   - GOOD (direct & clear): Overnight 0°C freeze -> Crisp translucent ice rime delicately coating warm terracotta autumn maple leaves and a brass weather vane at glowing sunrise.
""".trimIndent()
        }

        val styleGuidance = when (styleOption) {
            ArtStyleOption.AUTO_SURPRISE ->
                "Medium Selection: Autonomously choose an elevated museum-grade medium (e.g., warm terracotta & ochre fresco, botanical moss & gilt lacquer, deep burgundy velvet oil, Japanese saffron/vermilion woodblock, macro amber & forest glass refraction, architectural plein-air oil/photography) that has NOT appeared in recent history."
            ArtStyleOption.CUSTOM ->
                "Medium Selection: Strictly render in this aesthetic: ${customStyleText.ifBlank { "Minimalist museum-grade fine art" }}."
            else ->
                "Medium Selection: Strictly render in ${styleOption.label} — ${styleOption.directive}"
        }

        return """
You are Vesper, a cultural observer and high-taste art director designing bespoke mobile wallpapers.
Every cycle you observe real-world news & weather and translate them into museum-grade visual art tailored strictly to the user's chosen subtlety level ($subtlety/100).

$visualPhilosophyBlock

CHROMATIC PRESENCE & FULL-SPECTRUM THEME COLOR:
- Android extracts the phone's Material You UI theme color directly from the wallpaper's dominant surface pigments. Avoid washed-out monochrome greys, chalk, ash, pewter, or lazy slate-blue skies by default.
- Separate **mood** from **hue cliché**: express the gravity or tension of serious news through **form, contrast, lighting, and geometry** — NOT by always painting politics/summits/crises in slate grey, charcoal, or navy blue!
- Rotate confidently across the **entire color wheel** so the phone feels alive rather than locked to grey/blue: pick rich gallery pigments (carmine/burgundy, terracotta/burnt sienna, saffron/amber gold, forest emerald/moss/sage, twilight plum/aubergine, jade/mineral teal, coral/rosewood, cobalt) fitting the piece's character, while never repeating the dominant color family from recent `Visuals Used` palettes.

INTELLIGENCE FOCUS:
${settings.newsFocus.guidance}

SUBTLETY DIAL: $subtlety/100 (honored strictly above)

$styleGuidance

PHONE WALLPAPER ERGONOMICS (NON-NEGOTIABLE):
- Compose vertically for a tall portrait smartphone screen with safe horizontal outer margins (subject anchored cleanly in the central 75% width so a tall 20:9 screen center-crop never cuts off key details).
- Keep the upper 30% of the canvas calm, spacious, and low-contrast so the lock-screen clock remains effortlessly readable — use an expressive, thematic calm field (warm saffron plaster, deep burgundy velvet, sage silk, pine haze, twilight plum, sunlit sandstone, teal mineral wash, etc., rather than defaulting repeatedly to pale grey/blue mist).
- Avoid chaotic high-frequency clutter across the middle-lower canvas so app icons stay legible.
- Strictly NO text, typography, watermarks, signatures, borders, or numerals inside the image.

SELF-VERIFICATION BEFORE FINAL OUTPUT:
Check your draft `image_prompt` and `real_world_briefing` to confirm:
(a) Zero text/numbers/words inside the scene,
(b) Zero literal holiday/news clichés, disasters, violence, or gore,
(c) Top 30% negative space explicitly described,
(d) Fresh art medium, palette, and motif distinct from recent visuals,
(e) Selected events are neutral, corroborated, and truly among the most important defining this date — never padding with minor trivia just to force a new topic.
""".trimIndent()
    }

    fun buildUserPrompt(
        snapshot: ContextSnapshot,
        settings: AppSettings,
        overrides: GenerationRequestOverrides,
        recentDossiers: List<WallpaperDossier>
    ): String {
        val isWeekly = settings.scheduleMode == ScheduleMode.WEEKLY
        val isMorningEdition = snapshot.timeOfDayLabel in setOf("dawn", "morning")
        val subtlety = overrides.subtletyOverride ?: settings.subtletyLevel
        val seedNote = (overrides.customSeedOverride ?: settings.customSeedNote).trim()
        val recentMemory = if (recentDossiers.isEmpty()) {
            "None yet (first generation)."
        } else {
            recentDossiers.take(10).joinToString("\n") { d ->
                "- Visuals Used: ${d.artMedium} | ${d.paletteSummary} | ${d.conceptSummary}"
            }
        }

        val activeWindowDesc = when {
            isWeekly -> "Next 7 days (${snapshot.weekLabel} starting ${snapshot.isoDate})"
            isMorningEdition -> "Morning Edition for ${snapshot.isoDate} (last 24h developments + today ahead)"
            else -> "Day Edition for ${snapshot.isoDate} (today's settled developments + overnight ahead)"
        }

        val searchDirective = if (settings.enableSearchGrounding) {
            val scopeWindow = if (isWeekly) {
                "yesterday through ${snapshot.weekLabel}"
            } else if (isMorningEdition) {
                "last ~24h developments + consequential events unfolding TODAY (${snapshot.isoDate})"
            } else {
                "today's major settled developments (${snapshot.isoDate}) + overnight shifts"
            }
            """
MANDATORY LIVE SEARCH & RIGID 80/100 IMPORTANCE THRESHOLD:
REQUIRED: Cover $scopeWindow using ONLY verified events from the exact date/year (`${snapshot.isoDate}`). NEVER mix in same-calendar-day events or encyclopedic summaries from previous years (e.g. 2024 or 2025).

Step 1 — Score EVERY verified candidate event & local weather turn objectively in `evaluated_events` (`importance_score` 0–100) using these rigid anchors:
- 90–100 (Historic / Global): War/ceasefire shifts, major UN/G7/G20 summits (>20 leaders), decisive national election/constitutional shifts, nationwide emergencies/strikes, historic space/science milestones.
- 80–89 (Front-Page Significant — CUTOFF = $IMPORTANCE_THRESHOLD): Consequential national leadership/policy decisions, major continental summits (e.g., EU/EPC), sharp market/central-bank shocks, or severe/freezing local weather shifts affecting daily life.
- 60–79 (Routine — EXCLUDED): Everyday party speeches, minor diplomatic meetings, typical drizzle/clouds, routine earnings, minor conferences.
- 0–59 (Noise — EXCLUDED): Celebrity/entertainment, sports scores, routine astronomy trivia (ordinary moon % or minor showers), PR fluff.

Step 2 — Rigid Inclusion Filter (NO arbitrary count target, NO random coin-toss):
1. INCLUDE EVERY corroborated event with `importance_score >= $IMPORTANCE_THRESHOLD` (whether 1, 2, 3, or 4 clear $IMPORTANCE_THRESHOLD — never pick only one and skip another that also scored >= $IMPORTANCE_THRESHOLD, and NEVER drop a top continuing story just for variety!).
2. EXCLUDE EVERYTHING with `importance_score < $IMPORTANCE_THRESHOLD`.
3. CALM-DAY EMPTY RULE: If ZERO news/weather events score >= $IMPORTANCE_THRESHOLD, return `what_happened: []`, `happening_today: []`, `coming_up: []`, and `encoded_clues: []`, and compose pure seasonal atmosphere with zero news cues.
""".trimIndent()
        } else {
            "Web search is disabled for this run. Evaluate ONLY local weather/season using the >= $IMPORTANCE_THRESHOLD threshold (include severe shifts in `happening_today`/`coming_up`, or leave arrays empty `[]` for pure calm atmosphere)."
        }

        val includeRoutineMoon = settings.newsFocus == NewsFocus.COSMOS_AND_EARTH ||
            snapshot.moonPhase.startsWith("Full Moon") ||
            snapshot.moonPhase.startsWith("New Moon")
        val moonLine = if (includeRoutineMoon) "\n- Notable Lunar Marker: ${snapshot.moonPhase}" else ""
        val outlookLine = snapshot.weatherOutlookSummary
            .takeIf { it.isNotBlank() }
            ?.let { "\n- Day-Ahead Weather Arc: $it" }
            ?: ""
        val calendarLine = snapshot.calendarDensitySummary?.let { "\n- User Day Rhythm: $it" } ?: ""
        val customSeedLine = if (seedNote.isNotBlank()) {
            "- Explicit User Nudge / Seed (honor this alongside real-world context): $seedNote"
        } else {
            "- Explicit User Nudge: None (full autopilot)."
        }

        val dateHeader = if (isWeekly) {
            "${snapshot.isoDate} (${snapshot.dayOfWeek}, ${snapshot.weekLabel})"
        } else {
            "${snapshot.isoDate} (${snapshot.dayOfWeek})"
        }

        return """
CURRENT REAL-WORLD CONTEXT:
- Active Edition: $activeWindowDesc
- Date: $dateHeader
- Season & Time of Day: ${snapshot.seasonLabel}, ${snapshot.timeOfDayLabel}
- Location: ${snapshot.cityRegion}, ${snapshot.country}
- Weather & Shift: ${snapshot.weatherSummary} (${snapshot.tempDeltaSummary})$outlookLine$moonLine$calendarLine
$customSeedLine

$searchDirective

VISUAL ANTI-REPETITION MEMORY (DO NOT REPEAT THESE ART MEDIUMS, DOMINANT COLOR FAMILIES, OR VISUAL MOTIFS):
$recentMemory

Return JSON with these exact keys in order:
- `evaluated_events`: Array of `{"topic": string, "scope": "WORLD"|"NATIONAL"|"LOCAL_WEATHER", "importance_score": int}` scoring all candidates evaluated above.
- `what_happened`: Array of concise neutral 1-sentence strings for EVERY `>= $IMPORTANCE_THRESHOLD` event that ALREADY happened recently (~last 24h, or earlier in the 7-day window if Weekly), or `[]` if none qualify.
- `happening_today`: Array of concise neutral 1-sentence strings for EVERY `>= $IMPORTANCE_THRESHOLD` event actively unfolding TODAY (${snapshot.isoDate}, including major today weather shifts), or `[]` if none qualify.
- `coming_up`: Array of concise neutral 1-sentence strings for EVERY `>= $IMPORTANCE_THRESHOLD` scheduled future event or upcoming severe weather shift ahead within this wallpaper's window, or `[]` if none qualify.
- `clue_title`: 2–5 evocative words naming this piece.
- `real_world_briefing`: Brief summary string mirroring the bullets above (always kept as a concise backup).
- `encoded_clues`: Array of `{"visual_element": string, "real_world_meaning": string}` covering ALL events scoring `>= $IMPORTANCE_THRESHOLD` (`[]` if zero events qualified). Match the chosen Subtlety ($subtlety/100): tangible/recognizable elements when Subtlety < 35, subtle cues when >= 70.
- `art_medium`: Exact medium/technique chosen (must differ from `Visuals Used` above).
- `palette_summary`: 3–4 expressive pigment tones from the full spectrum (must shift dominant hue family away from recent `Visuals Used` palettes).
- `concept_summary`: 1-sentence motif summary for future visual anti-repetition.
- `image_prompt`: 75–110 word art direction prompt ready for Nano Banana 2.1, strictly matching Subtlety ($subtlety/100) visual directness, rich thematic color pigments, calm thematic upper 30% clock void, safe vertical margins, and zero text.
""".trimIndent()
    }

    fun buildSeparateHomePrompt(
        lockBriefingTitle: String,
        lockMedium: String,
        lockPalette: String,
        lockConcept: String
    ): String = """
Create a serene, minimalist companion Home Screen wallpaper echoing the exact color palette ($lockPalette) and tactile material ($lockMedium) of the concept "$lockBriefingTitle" ($lockConcept), but stripped down to calm atmospheric gradient, subtle surface grain, and quiet negative space so smartphone app icons remain effortless to read. Vertical phone composition, safe margins, ultra-clean, zero text, zero busy foreground objects.
""".trimIndent()
}
