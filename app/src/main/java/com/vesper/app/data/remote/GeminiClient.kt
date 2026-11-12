package com.vesper.app.data.remote

import android.util.Base64
import com.vesper.app.domain.model.AppSettings
import com.vesper.app.domain.model.DiscoveredModelsCatalog
import com.vesper.app.domain.model.EncodedClue
import com.vesper.app.domain.model.GroundingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class DirectorEvaluatedEvent(
    val topic: String,
    val scope: String = "WORLD",
    @SerialName("importance_score") val importanceScore: Int = 0
)

@Serializable
data class DirectorJsonOutput(
    @SerialName("evaluated_events") val evaluatedEvents: List<DirectorEvaluatedEvent> = emptyList(),
    @SerialName("what_happened") val whatHappened: List<String> = emptyList(),
    @SerialName("happening_today") val happeningToday: List<String> = emptyList(),
    @SerialName("coming_up") val comingUp: List<String> = emptyList(),
    @SerialName("clue_title") val clueTitle: String,
    @SerialName("real_world_briefing") val realWorldBriefing: String = "",
    @SerialName("encoded_clues") val encodedClues: List<DirectorClueItem> = emptyList(),
    @SerialName("art_medium") val artMedium: String,
    @SerialName("palette_summary") val paletteSummary: String,
    @SerialName("concept_summary") val conceptSummary: String,
    @SerialName("image_prompt") val imagePrompt: String
)

@Serializable
data class DirectorClueItem(
    @SerialName("visual_element") val visualElement: String,
    @SerialName("real_world_meaning") val realWorldMeaning: String
)

data class DirectorResult(
    val output: DirectorJsonOutput,
    val groundingSources: List<GroundingSource>,
    val modelUsed: String
)

data class GeneratedImageResult(
    val bytes: ByteArray,
    val mimeType: String,
    val modelUsed: String
)

class GeminiApiException(val statusCode: Int, message: String) : Exception(message)

class GeminiClient(private val httpClient: OkHttpClient) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta"

    suspend fun fetchModelsCatalog(apiKey: String): DiscoveredModelsCatalog = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        require(cleanKey.isNotBlank()) { "Gemini API key is missing." }

        val allNames = mutableListOf<String>()
        var pageToken: String? = null
        var safetyLoop = 0
        do {
            val url = buildString {
                append("$baseUrl/models?pageSize=100")
                if (!pageToken.isNullOrBlank()) append("&pageToken=$pageToken")
            }
            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", cleanKey)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    throw GeminiApiException(response.code, parseFriendlyError(response.code, body))
                }
                val root = json.parseToJsonElement(body).jsonObject
                val models = root["models"]?.jsonArray.orEmpty()
                for (item in models) {
                    val obj = item.jsonObject
                    val rawName = obj["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val id = rawName.removePrefix("models/")
                    val methods = obj["supportedGenerationMethods"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        .orEmpty()
                    if ("generateContent" in methods) {
                        allNames += id
                    }
                }
                pageToken = root["nextPageToken"]?.jsonPrimitive?.contentOrNull
            }
            safetyLoop++
        } while (!pageToken.isNullOrBlank() && safetyLoop < 5)

        val textModels = allNames
            .filter { id ->
                val lower = id.lowercase()
                lower.contains("gemini") &&
                    (lower.contains("flash") || lower.contains("pro")) &&
                    !lower.contains("image") &&
                    !lower.contains("nano-banana") &&
                    !lower.contains("tts") &&
                    !lower.contains("omni") &&
                    !lower.contains("live") &&
                    !lower.contains("audio") &&
                    !lower.contains("robotics")
            }
            .distinct()
            .sortedWith(modelComparator(preferFlash = true))

        val imageModels = allNames
            .filter { id ->
                val lower = id.lowercase()
                lower.contains("nano-banana") ||
                    (lower.contains("gemini") && lower.contains("image"))
            }
            .distinct()
            .sortedWith(modelComparator(preferFlash = false))

        val bestText = textModels.firstOrNull { !it.contains("preview", true) && !it.contains("exp", true) }
            ?: textModels.firstOrNull()
        val bestImage = imageModels.firstOrNull { !it.contains("preview", true) && !it.contains("exp", true) }
            ?: imageModels.firstOrNull()

        DiscoveredModelsCatalog(
            textModels = textModels,
            imageModels = imageModels,
            latestSuggestedTextModel = bestText,
            latestSuggestedImageModel = bestImage,
            lastCheckedEpochMs = System.currentTimeMillis()
        )
    }

    suspend fun generateDirectorBrief(
        apiKey: String,
        preferredModelId: String,
        fallbackModelId: String,
        systemInstruction: String,
        userPrompt: String,
        enableSearchGrounding: Boolean
    ): DirectorResult = withContext(Dispatchers.IO) {
        val modelsToTry = listOf(preferredModelId, fallbackModelId)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        var lastError: Throwable? = null
        for (modelId in modelsToTry) {
            if (enableSearchGrounding) {
                // Step 1: Dedicated live Google Search call strictly anchored to the current year/date.
                val searchPass = runCatching {
                    executeGroundedSearch(apiKey = apiKey, modelId = modelId, userPrompt = userPrompt)
                }.getOrNull()

                if (searchPass != null && searchPass.first.isNotBlank()) {
                    val groundedPrompt = """
$userPrompt

VERIFIED CURRENT-YEAR LIVE WEB RESEARCH (authoritative news sources — base ALL news items strictly on these verified current events + provided weather context; reject any older historical events):
${searchPass.first}
""".trimIndent()
                    val step2 = runCatching {
                        executeDirectorCall(
                            apiKey = apiKey,
                            modelId = modelId,
                            systemInstruction = systemInstruction,
                            userPrompt = groundedPrompt,
                            useSearchGrounding = false,
                            useSchema = true
                        )
                    }
                    if (step2.isSuccess) {
                        val result = step2.getOrThrow()
                        return@withContext result.copy(groundingSources = searchPass.second)
                    }
                    lastError = step2.exceptionOrNull()
                }

                // Fallback: Single grounded schema call if search pre-pass failed cleanly.
                val fallbackAttempt = runCatching {
                    executeDirectorCall(
                        apiKey = apiKey,
                        modelId = modelId,
                        systemInstruction = systemInstruction,
                        userPrompt = userPrompt,
                        useSearchGrounding = true,
                        useSchema = true
                    )
                }
                if (fallbackAttempt.isSuccess) return@withContext fallbackAttempt.getOrThrow()
                lastError = fallbackAttempt.exceptionOrNull() ?: lastError
            } else {
                val attempt = runCatching {
                    executeDirectorCall(
                        apiKey = apiKey,
                        modelId = modelId,
                        systemInstruction = systemInstruction,
                        userPrompt = userPrompt,
                        useSearchGrounding = false,
                        useSchema = true
                    )
                }
                if (attempt.isSuccess) return@withContext attempt.getOrThrow()
                lastError = attempt.exceptionOrNull()
            }
        }
        throw lastError ?: IllegalStateException("Creative Director generation failed")
    }

    internal fun buildDirectorGenerationConfig(
        useSchema: Boolean,
        useHighThinking: Boolean
    ): JsonObject = buildJsonObject {
        put("temperature", 0.2)
        if (useSchema) {
            put("responseMimeType", "application/json")
            put("responseSchema", directorResponseSchema())
        }
        if (useHighThinking) {
            put("thinkingConfig", buildJsonObject {
                put("thinkingLevel", "HIGH")
            })
        }
    }

    internal fun buildSearchGenerationConfig(useHighThinking: Boolean): JsonObject = buildJsonObject {
        put("temperature", 0.1)
        if (useHighThinking) {
            put("thinkingConfig", buildJsonObject {
                put("thinkingLevel", "HIGH")
            })
        }
    }

    private fun executeDirectorCall(
        apiKey: String,
        modelId: String,
        systemInstruction: String,
        userPrompt: String,
        useSearchGrounding: Boolean,
        useSchema: Boolean
    ): DirectorResult {
        return try {
            executeDirectorCallInternal(
                apiKey = apiKey,
                modelId = modelId,
                systemInstruction = systemInstruction,
                userPrompt = userPrompt,
                useSearchGrounding = useSearchGrounding,
                useSchema = useSchema,
                useHighThinking = true
            )
        } catch (ex: GeminiApiException) {
            if (ex.statusCode == 400) {
                executeDirectorCallInternal(
                    apiKey = apiKey,
                    modelId = modelId,
                    systemInstruction = systemInstruction,
                    userPrompt = userPrompt,
                    useSearchGrounding = useSearchGrounding,
                    useSchema = useSchema,
                    useHighThinking = false
                )
            } else {
                throw ex
            }
        }
    }

    private fun executeDirectorCallInternal(
        apiKey: String,
        modelId: String,
        systemInstruction: String,
        userPrompt: String,
        useSearchGrounding: Boolean,
        useSchema: Boolean,
        useHighThinking: Boolean
    ): DirectorResult {
        val payload = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemInstruction) })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", userPrompt) })
                    })
                })
            })
            if (useSearchGrounding) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("google_search", buildJsonObject {})
                    })
                })
            }
            put("generationConfig", buildDirectorGenerationConfig(useSchema, useHighThinking))
        }

        val request = Request.Builder()
            .url("$baseUrl/models/$modelId:generateContent")
            .header("x-goog-api-key", apiKey.trim())
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw GeminiApiException(response.code, parseFriendlyError(response.code, body))
            }

            val root = json.parseToJsonElement(body).jsonObject
            val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw IllegalStateException("No candidates returned from $modelId")

            val textParts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("\n")
                ?.trim()
                .orEmpty()

            if (textParts.isBlank()) {
                val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
                throw IllegalStateException("Empty response from $modelId (finishReason=$finishReason)")
            }

            val cleanedJson = extractJsonObjectString(textParts)
            val parsed = json.decodeFromString<DirectorJsonOutput>(cleanedJson)
            val sources = extractGroundingSources(candidate)
            val resolvedModel = root["modelVersion"]?.jsonPrimitive?.contentOrNull ?: modelId

            return DirectorResult(
                output = parsed,
                groundingSources = sources,
                modelUsed = resolvedModel
            )
        }
    }

    private fun executeGroundedSearch(
        apiKey: String,
        modelId: String,
        userPrompt: String
    ): Pair<String, List<GroundingSource>> {
        return try {
            executeGroundedSearchInternal(apiKey, modelId, userPrompt, useHighThinking = true)
        } catch (ex: GeminiApiException) {
            if (ex.statusCode == 400) {
                executeGroundedSearchInternal(apiKey, modelId, userPrompt, useHighThinking = false)
            } else {
                throw ex
            }
        }
    }

    private fun executeGroundedSearchInternal(
        apiKey: String,
        modelId: String,
        userPrompt: String,
        useHighThinking: Boolean
    ): Pair<String, List<GroundingSource>> {
        val searchPrompt = buildGroundedSearchPrompt(userPrompt)

        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", searchPrompt) })
                    })
                })
            })
            put("tools", buildJsonArray {
                add(buildJsonObject { put("google_search", buildJsonObject {}) })
            })
            put("generationConfig", buildSearchGenerationConfig(useHighThinking))
        }

        val request = Request.Builder()
            .url("$baseUrl/models/$modelId:generateContent")
            .header("x-goog-api-key", apiKey.trim())
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw GeminiApiException(response.code, parseFriendlyError(response.code, body))
            }
            val root = json.parseToJsonElement(body).jsonObject
            val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: return "" to emptyList()
            val textParts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("\n")
                ?.trim()
                .orEmpty()
            val sources = extractGroundingSources(candidate)
            return textParts to sources
        }
    }

    internal fun buildGroundedSearchPrompt(userPrompt: String): String {
        val lines = userPrompt.lines()
        val dateLine = lines.firstOrNull { it.startsWith("- Date:") } ?: ""
        val locationLine = lines.firstOrNull { it.startsWith("- Location:") } ?: ""
        val editionLine = lines.firstOrNull { it.startsWith("- Active Edition:") } ?: ""
        val currentYear = Regex("""\b(20\d{2})\b""").find(dateLine)?.groupValues?.getOrNull(1) ?: "current year"
        return """
Execute Google Search RIGHT NOW for breaking and front-page real-world news matching this exact context:
$editionLine
$dateLine
$locationLine

MANDATORY DATE & SOURCE VERIFICATION RULES:
1. ALWAYS include the exact year $currentYear in your Google Search queries (e.g., world front-page news $currentYear, national news $currentYear) across reputable news agencies/public broadcasters (Reuters, AP, BBC, DW, Tagesschau, official press portals).
2. STRICT CURRENT-YEAR ONLY: Verify the actual publication date & year of every single event BEFORE reporting it. DISCARD any Wikipedia overview, "On this day" retrospective, or event from previous years (NEVER confuse same-month events from prior years like 2024 or 2025 with $currentYear).
3. Include BOTH major international developments AND major national/regional events for the location above.
4. Return concise, factual bullet points organized with the exact date (`MMM D, $currentYear`) under:
   - What Happened (recent / last ~24h)
   - Happening Today
   - Coming Up (upcoming scheduled events)
""".trimIndent()
    }

    suspend fun generateWallpaperImage(
        apiKey: String,
        preferredModelId: String,
        fallbackModelId: String,
        prompt: String,
        aspectRatio: String,
        imageSize: String,
        thinkingLevel: String
    ): GeneratedImageResult = withContext(Dispatchers.IO) {
        val modelsToTry = listOf(preferredModelId, fallbackModelId)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        var lastError: Throwable? = null
        for (modelId in modelsToTry) {
            val attempt1 = runCatching {
                executeImageCall(apiKey, modelId, prompt, aspectRatio, imageSize, thinkingLevel)
            }
            if (attempt1.isSuccess) return@withContext attempt1.getOrThrow()

            val ex = attempt1.exceptionOrNull()
            // If a user manually selected an image model that rejects thinkingConfig, retry cleanly without thinkingConfig
            if (ex is GeminiApiException && ex.statusCode == 400 && thinkingLevel.isNotBlank()) {
                val retryNoThinking = runCatching {
                    executeImageCall(apiKey, modelId, prompt, aspectRatio, imageSize, null)
                }
                if (retryNoThinking.isSuccess) return@withContext retryNoThinking.getOrThrow()
                lastError = retryNoThinking.exceptionOrNull()
            } else {
                lastError = ex
            }
        }
        throw lastError ?: IllegalStateException("Image generation failed")
    }

    private fun executeImageCall(
        apiKey: String,
        modelId: String,
        prompt: String,
        aspectRatio: String,
        imageSize: String,
        thinkingLevel: String?
    ): GeneratedImageResult {
        val payload = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                })
                put("imageConfig", buildJsonObject {
                    put("aspectRatio", aspectRatio)
                    put("imageSize", imageSize)
                })
                if (!thinkingLevel.isNullOrBlank()) {
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingLevel", thinkingLevel)
                    })
                }
            })
        }

        val request = Request.Builder()
            .url("$baseUrl/models/$modelId:generateContent")
            .header("x-goog-api-key", apiKey.trim())
            .post(payload.toString().toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw GeminiApiException(response.code, parseFriendlyError(response.code, body))
            }

            val root = json.parseToJsonElement(body).jsonObject
            val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw IllegalStateException("No image candidate returned from $modelId")

            val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            for (part in parts) {
                val partObj = part.jsonObject
                val inlineData = partObj["inlineData"]?.jsonObject ?: partObj["inline_data"]?.jsonObject
                if (inlineData != null) {
                    val b64 = inlineData["data"]?.jsonPrimitive?.contentOrNull
                    val mime = inlineData["mimeType"]?.jsonPrimitive?.contentOrNull
                        ?: inlineData["mime_type"]?.jsonPrimitive?.contentOrNull
                        ?: "image/png"
                    if (!b64.isNullOrBlank()) {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        if (bytes.isNotEmpty()) {
                            val resolvedModel = root["modelVersion"]?.jsonPrimitive?.contentOrNull ?: modelId
                            return GeneratedImageResult(bytes, mime, resolvedModel)
                        }
                    }
                }
            }
            val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
            throw IllegalStateException("Model $modelId completed ($finishReason) without image data.")
        }
    }

    private fun directorResponseSchema(): JsonObject = buildJsonObject {
        put("type", "OBJECT")
        put("properties", buildJsonObject {
            put("evaluated_events", buildJsonObject {
                put("type", "ARRAY")
                put("items", buildJsonObject {
                    put("type", "OBJECT")
                    put("properties", buildJsonObject {
                        put("topic", buildJsonObject { put("type", "STRING") })
                        put("scope", buildJsonObject { put("type", "STRING") })
                        put("importance_score", buildJsonObject { put("type", "INTEGER") })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("topic"))
                        add(JsonPrimitive("scope"))
                        add(JsonPrimitive("importance_score"))
                    })
                })
            })
            put("what_happened", buildJsonObject {
                put("type", "ARRAY")
                put("items", buildJsonObject { put("type", "STRING") })
            })
            put("happening_today", buildJsonObject {
                put("type", "ARRAY")
                put("items", buildJsonObject { put("type", "STRING") })
            })
            put("coming_up", buildJsonObject {
                put("type", "ARRAY")
                put("items", buildJsonObject { put("type", "STRING") })
            })
            put("clue_title", buildJsonObject { put("type", "STRING") })
            put("real_world_briefing", buildJsonObject { put("type", "STRING") })
            put("encoded_clues", buildJsonObject {
                put("type", "ARRAY")
                put("items", buildJsonObject {
                    put("type", "OBJECT")
                    put("properties", buildJsonObject {
                        put("visual_element", buildJsonObject { put("type", "STRING") })
                        put("real_world_meaning", buildJsonObject { put("type", "STRING") })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("visual_element"))
                        add(JsonPrimitive("real_world_meaning"))
                    })
                })
            })
            put("art_medium", buildJsonObject { put("type", "STRING") })
            put("palette_summary", buildJsonObject { put("type", "STRING") })
            put("concept_summary", buildJsonObject { put("type", "STRING") })
            put("image_prompt", buildJsonObject { put("type", "STRING") })
        })
        put("required", buildJsonArray {
            add(JsonPrimitive("evaluated_events"))
            add(JsonPrimitive("what_happened"))
            add(JsonPrimitive("happening_today"))
            add(JsonPrimitive("coming_up"))
            add(JsonPrimitive("clue_title"))
            add(JsonPrimitive("real_world_briefing"))
            add(JsonPrimitive("encoded_clues"))
            add(JsonPrimitive("art_medium"))
            add(JsonPrimitive("palette_summary"))
            add(JsonPrimitive("concept_summary"))
            add(JsonPrimitive("image_prompt"))
        })
    }

    private fun extractGroundingSources(candidate: JsonObject): List<GroundingSource> {
        val chunks = candidate["groundingMetadata"]?.jsonObject?.get("groundingChunks")?.jsonArray
            ?: return emptyList()
        val mapped = chunks.mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            val uri = web["uri"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = web["title"]?.jsonPrimitive?.contentOrNull ?: uri
            GroundingSource(title = title, url = uri)
        }.distinctBy { it.url }

        val nonEncyclopedia = mapped.filterNot {
            it.title.equals("wikipedia.org", ignoreCase = true) ||
                it.title.equals("google.com", ignoreCase = true)
        }
        return (if (nonEncyclopedia.isNotEmpty()) nonEncyclopedia else mapped).take(6)
    }

    private fun extractJsonObjectString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1)
        }
        return trimmed
    }

    private fun parseFriendlyError(code: Int, body: String): String {
        val apiMsg = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: body.take(240)

        return when {
            code == 429 && apiMsg.contains("limit: 0", ignoreCase = true) ->
                "Quota limit is 0 — link Google Cloud Billing (or your AI Pro \$10 Developer Credit) to this AI Studio project to run Nano Banana 2.1."
            code == 401 || code == 403 ->
                "Authentication failed ($code): Check your Google AI Studio API key. Details: $apiMsg"
            else ->
                "Gemini API error ($code): $apiMsg"
        }
    }

    private fun modelComparator(preferFlash: Boolean): Comparator<String> = Comparator { a, b ->
        fun score(id: String): Int {
            val l = id.lowercase()
            var s = 0
            if (!l.contains("preview") && !l.contains("exp") && !l.contains("latest")) s += 1000
            if (l.contains("nano-banana")) s += 500
            if (preferFlash && l.contains("flash") && !l.contains("lite")) s += 300
            if (!preferFlash && !l.contains("lite")) s += 200
            // Extract numeric version e.g. 2.1, 3.8, 3.5 -> multiply by 100
            val match = Regex("""(\d+)\.(\d+)""").find(l)
            if (match != null) {
                val major = match.groupValues[1].toIntOrNull() ?: 0
                val minor = match.groupValues[2].toIntOrNull() ?: 0
                s += major * 50 + minor * 5
            }
            return s
        }
        score(b).compareTo(score(a))
    }
}
