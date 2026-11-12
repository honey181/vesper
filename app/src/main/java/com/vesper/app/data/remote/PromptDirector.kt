package com.vesper.app.data.remote

import com.vesper.app.domain.model.AppSettings
import com.vesper.app.domain.model.ArtStyleOption
import com.vesper.app.domain.model.ContextSnapshot
import com.vesper.app.domain.model.GenerationRequestOverrides
import com.vesper.app.domain.model.NewsFocus
import com.vesper.app.domain.model.ScheduleMode
import com.vesper.app.domain.model.WallpaperDossier

object PromptDirector {

    fun buildSystemInstruction(settings: AppSettings, overrides: GenerationRequestOverrides): String {
        val subtlety = overrides.subtletyOverride ?: settings.subtletyLevel
        val styleOption = overrides.styleOverride ?: settings.artStyleOption
        val customStyleText = overrides.customStyleTextOverride ?: settings.customStyleDirective

        val subtletyBand = when {
            subtlety >= 70 -> "ULTRA-SUBTLE CIPHER ($subtlety/100): Communicate through pure oblique symbolism, materials, atmospheric light, geometric counts, shadow angles, and textures. Avoid recognizable literal holiday icons or obvious figurative objects."
            subtlety >= 35 -> "POETIC EDITORIAL ($subtlety/100): Blend atmospheric realism or surreal still-life metaphors with restrained, gallery-worthy symbolism. Never cliché, never literal clipart."
            else -> "FIGURATIVE ATMOSPHERIC ($subtlety/100): Allow evocative landscapes, interiors, or architectural scenes rooted in the place and week, while keeping the execution restrained, quiet, and elevated."
        }

        val styleGuidance = when (styleOption) {
            ArtStyleOption.AUTO_SURPRISE ->
                "Medium Selection: Autonomously choose an elevated museum-grade medium (e.g., architectural chiaroscuro, Japanese woodblock/ink wash, macro mineral & glass refraction, minimalist Bauhaus plaster, velvet nocturnal oil, fine-grain editorial photography) that has NOT appeared in recent history."
            ArtStyleOption.CUSTOM ->
                "Medium Selection: Strictly render in this aesthetic: ${customStyleText.ifBlank { "Minimalist museum-grade fine art" }}."
            else ->
                "Medium Selection: Strictly render in ${styleOption.label} — ${styleOption.directive}"
        }

        return """
You are Vesper, an enigmatic cultural observer and high-taste art director designing bespoke mobile wallpapers.
Every cycle you quietly perceive what is happening in the user's real-world week and translate that intelligence into museum-grade visual art—candy for the eyes and quiet nutrition for a curious mind.

CORE PHILOSOPHY ("SUBTLETY OVER SPECTACLE"):
1. Never produce bloated, literal, or tacky imagery. Strictly forbid flags, holiday mascots, calendars, headlines, text, letters, numbers, clocks, UI chrome, or sensational news depictions.
2. Encode real-world awareness like an observant spy leaving an artistic calling card: use subtle material cues, light temperature, sky azimuth, botanical life, geometric counts, tension between elements, and quiet atmospheric hints.
3. Contrastive Examples (follow the GOOD pattern):
   - BAD: US Independence Day week -> Fireworks bursting over the Statue of Liberty in red, white, and blue.
   - GOOD: US Independence Day week -> Nocturne ink-wash over dark Atlantic water, weathered archival paper grain, 13 tiny warm ember arcs drifting into a vast quiet indigo sky, subtle oxidized copper patina.
   - BAD: Major election week + sudden cold front -> A ballot box covered in snow.
    - GOOD: Major election week + sudden cold front -> Sharp geometric frost fracture dividing warm tungsten light and cool cobalt glass across dark volcanic slate.
   - BAD: Space launch / major storm + busy day -> Cartoon rocket next to umbrellas and coffee cups.
   - GOOD: Historic space milestone / major cold front -> Minimalist obsidian horizon with one hairline silver trajectory rising cleanly through serene upper mist, quiet sanctuary negative space.

INTELLIGENCE FOCUS:
${settings.newsFocus.guidance}

SUBTLETY DIAL:
$subtletyBand

$styleGuidance

PHONE WALLPAPER ERGONOMICS (NON-NEGOTIABLE):
- Compose vertically for a tall portrait smartphone screen with safe horizontal outer margins (subject anchored cleanly in the central 75% width so a tall 20:9 screen center-crop never cuts off key details).
- Keep the upper 30% of the canvas calm, spacious, and low-contrast (mist, sky, shadow gradient, open stone/paper) so the lock-screen clock remains effortlessly readable.
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
        val seedNote = (overrides.customSeedOverride ?: settings.customSeedNote).trim()
        val recentMemory = if (recentDossiers.isEmpty()) {
            "None yet (first generation)."
        } else {
            recentDossiers.take(10).joinToString("\n") { d ->
                val briefSnippet = d.realWorldBriefing.replace('\n', ' ').trim().take(140)
                "- ${d.clueTitle} | Visuals Used: ${d.artMedium}; ${d.paletteSummary}; ${d.conceptSummary} | Prior Brief: $briefSnippet"
            }
        }

        val activeWindowDesc = when {
            isWeekly -> "Next 7 days (${snapshot.weekLabel} starting ${snapshot.isoDate})"
            isMorningEdition -> "Morning Edition for ${snapshot.isoDate} (last 24h developments + today ahead)"
            else -> "Day Edition for ${snapshot.isoDate} (today's settled developments + overnight ahead)"
        }

        val searchDirective = if (settings.enableSearchGrounding) {
            if (isWeekly) {
                """
LIVE SEARCH & OBJECTIVE EDITORIAL RULES (WEEKLY EDITION):
Use Google Search right now across international news wires AND national/regional news in ${snapshot.country} / ${snapshot.cityRegion} (in local language when helpful, summarized cleanly in English):
1. OBJECTIVE SIGNIFICANCE FIRST: Pick the genuinely most consequential developments defining this 7-day period (`yesterday into ${snapshot.weekLabel}`) as an objective chronicle — major world/national policy, economy, science, society, or severe regional weather.
2. NEUTRAL & CORROBORATED: Prefer consensus front-page stories confirmed by major reputable public broadcasters / news agencies. Zero clickbait, zero celebrity/tabloid chatter.
3. NO ASTRONOMY FILLER: Unless Cosmos focus is chosen, ignore minor astronomy facts (routine shooting-star counts or ordinary moon %) unless something rare affects ordinary people or reaches front-page news (e.g., total eclipse, widespread aurora, historic space launch).
4. SIGNIFICANCE BEATS NOVELTY: Never drop a towering defining story just because it appeared in `Prior Brief` — vary the visual art completely instead.
Select only what genuinely deserves to define this week (1–3 signals; never pad with trivial filler).
""".trimIndent()
            } else {
                val timeScope = if (isMorningEdition) {
                    "This wallpaper runs in the morning like an objective daily morning edition (`yesterday afternoon & overnight` into `what is scheduled or shifting today`, ${snapshot.isoDate}):"
                } else {
                    "This wallpaper runs later in the day for ${snapshot.isoDate} (`what broke or settled today` plus `important shifts through tomorrow morning`):"
                }
                """
LIVE SEARCH & OBJECTIVE EDITORIAL RULES (DAILY EDITION):
$timeScope
Search Google across BOTH international front-page wires AND local/national news for ${snapshot.cityRegion}, ${snapshot.country} (checking local-language reporting where useful, summarized neutrally in English):
1. CHRONICLE LITMUS TEST: Imagine looking back at ${snapshot.isoDate} in history. Choose the events an objective, non-partisan broadsheet morning edition would lead with:
   - Major developments from the last ~24h (world, ${snapshot.country} national policy/economy/society, or serious breaking events you shouldn't miss),
   - Important things actively scheduled or unfolding TODAY on ${snapshot.isoDate} (key votes, major summits/decisions, strikes/disruptions, major regional civic moments — omit minor completed local fluff from yesterday),
   - Meaningful local meteorological shifts for ${snapshot.cityRegion} when weather genuinely changes the day (using the forecast cues below).
2. ACCESSIBILITY FOR AVERAGE PEOPLE: Unless Cosmos focus is active, skip niche astronomy/science trivia (e.g., routine minor meteor showers or moon percentage phases). Only mention sky/space when truly mainstream (rare visible eclipse, widespread aurora, Full Moon, solstice, major mission milestone).
3. SIGNIFICANCE BEATS NOVELTY (`Prior Brief` Rule): Do NOT force obscure or trivial topics just to differ from yesterday. If a historic multi-day crisis or summit objectively remains the #1 defining fact today, keep covering it (advancing to today's newest development if any) and differentiate purely through fresh visual art.
4. ZERO FILLER RULE: Include 1–3 signals strictly based on real substance — let 1 big story (or quiet local weather + season on genuinely calm days) carry the piece rather than padding with weak news.
""".trimIndent()
            }
        } else {
            "Web search is disabled for this run. Rely calmly on today's local meteorological outlook, regional season, and atmosphere below."
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
        val briefInstruction = if (isWeekly) {
            "2–3 neutral, crisp sentences summarizing what objectively defines this week globally/nationally alongside notable local weather or civic shifts."
        } else if (isMorningEdition) {
            "2–3 neutral, crisp morning-brief sentences covering the key real-world developments from the last 24h and what shifts or unfolds today (${snapshot.isoDate}) — skip weak filler."
        } else {
            "2–3 neutral, crisp sentences covering today's defining developments (${snapshot.isoDate}) and notable overnight/tomorrow weather shifts — skip weak filler."
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

VISUAL ANTI-REPETITION MEMORY (DO NOT REPEAT THESE ART MEDIUMS, PALETTES, OR VISUAL MOTIFS):
$recentMemory

Return JSON matching the required schema:
- `clue_title`: 2–5 evocative words naming this piece.
- `real_world_briefing`: $briefInstruction
- `encoded_clues`: 1–3 entries matching only the genuine high-signal elements chosen (never pad with weak filler).
- `art_medium`: Exact medium/technique chosen (must differ from `Visuals Used` above).
- `palette_summary`: 3–4 color tones (must differ from `Visuals Used` above).
- `concept_summary`: 1-sentence motif summary for future visual anti-repetition.
- `image_prompt`: 75–110 word art direction prompt ready for Nano Banana 2.1, describing subject, materials, lighting, palette, calm upper 30% clock void, safe vertical margins, and zero text.
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
