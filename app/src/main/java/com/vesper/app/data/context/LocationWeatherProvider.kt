package com.vesper.app.data.context

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.vesper.app.data.local.MoonPhaseCalculator
import com.vesper.app.domain.model.AppSettings
import com.vesper.app.domain.model.ContextSnapshot
import com.vesper.app.domain.model.LocationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class LocationWeatherProvider(
    private val context: Context,
    private val httpClient: OkHttpClient
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun collectSnapshot(settings: AppSettings): ContextSnapshot = withContext(Dispatchers.IO) {
        val nowDate = LocalDate.now()
        val nowTime = LocalTime.now()
        val weekOfYear = nowDate.get(WeekFields.of(Locale.getDefault()).weekOfWeekBasedYear())
        val timeOfDayLabel = when (nowTime.hour) {
            in 4..7 -> "dawn"
            in 8..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "dusk / twilight"
            else -> "deep night"
        }

        val resolvedLoc = resolveLocation(settings)
        val weatherInfo = if (settings.enableWeatherContext && resolvedLoc.latitude != null && resolvedLoc.longitude != null) {
            fetchOpenMeteoWeather(resolvedLoc.latitude, resolvedLoc.longitude)
        } else {
            WeatherResult("unavailable", "no temp delta available", "")
        }

        val calendarSummary = if (settings.enableCalendarDensity) {
            readCalendarBusynessCount()
        } else null

        ContextSnapshot(
            isoDate = nowDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            dayOfWeek = nowDate.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() },
            weekLabel = "Week $weekOfYear of ${nowDate.year}",
            seasonLabel = MoonPhaseCalculator.inferMeteorologicalSeason(nowDate, resolvedLoc.latitude),
            timeOfDayLabel = timeOfDayLabel,
            cityRegion = resolvedLoc.city,
            country = resolvedLoc.country,
            latitude = resolvedLoc.latitude,
            longitude = resolvedLoc.longitude,
            weatherSummary = weatherInfo.weatherSummary,
            tempDeltaSummary = weatherInfo.tempDeltaSummary,
            moonPhase = MoonPhaseCalculator.describeMoonPhase(),
            calendarDensitySummary = calendarSummary,
            weatherOutlookSummary = weatherInfo.outlookSummary
        )
    }

    private data class ResolvedLocation(
        val city: String,
        val country: String,
        val latitude: Double?,
        val longitude: Double?
    )

    internal data class WeatherResult(
        val weatherSummary: String,
        val tempDeltaSummary: String,
        val outlookSummary: String
    )

    private suspend fun resolveLocation(settings: AppSettings): ResolvedLocation {
        val localeCountry = Locale.getDefault().displayCountry.ifBlank { "Unknown Country" }
        val tzCityGuess = ZoneId.systemDefault().id.substringAfter('/', "").replace('_', ' ')

        if (settings.locationMode == LocationMode.MANUAL_CITY && settings.manualCity.isNotBlank()) {
            return geocodeCityViaOpenMeteo(settings.manualCity, localeCountry)
        }

        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCoarse) {
            val loc = getCoarseDeviceLocation()
            if (loc != null) {
                val (city, country) = reverseGeocodeCoarse(loc.latitude, loc.longitude)
                val resolved = ResolvedLocation(
                    city = city.ifBlank { tzCityGuess.ifBlank { "Local Region" } },
                    country = country.ifBlank { localeCountry },
                    latitude = loc.latitude,
                    longitude = loc.longitude
                )
                cacheResolvedLocation(resolved)
                return resolved
            }
        }

        // Background WorkManager fallback: reuse last resolved location if OS withheld background location
        readCachedLocation()?.let { return it }

        // Graceful fallback: manual city if populated, otherwise timezone city + device locale country
        val queryCity = settings.manualCity.ifBlank { tzCityGuess }
        if (queryCity.isNotBlank()) {
            return geocodeCityViaOpenMeteo(queryCity, localeCountry)
        }
        return ResolvedLocation("Local Region", localeCountry, null, null)
    }

    private fun cacheResolvedLocation(loc: ResolvedLocation) {
        val prefs = context.getSharedPreferences("vesper_loc_cache", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("city", loc.city)
            .putString("country", loc.country)
            .putString("lat", loc.latitude?.toString())
            .putString("lon", loc.longitude?.toString())
            .apply()
    }

    private fun readCachedLocation(): ResolvedLocation? {
        val prefs = context.getSharedPreferences("vesper_loc_cache", Context.MODE_PRIVATE)
        val city = prefs.getString("city", null) ?: return null
        val country = prefs.getString("country", null) ?: return null
        val lat = prefs.getString("lat", null)?.toDoubleOrNull()
        val lon = prefs.getString("lon", null)?.toDoubleOrNull()
        return ResolvedLocation(city, country, lat, lon)
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCoarseDeviceLocation(): Location? = withTimeoutOrNull(4500L) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@withTimeoutOrNull null
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
        for (provider in providers) {
            val last = runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
            if (last != null) return@withTimeoutOrNull last
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            return@withTimeoutOrNull suspendCancellableCoroutine { cont ->
                lm.getCurrentLocation(
                    LocationManager.NETWORK_PROVIDER,
                    null,
                    context.mainExecutor
                ) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            }
        }
        null
    }

    private suspend fun reverseGeocodeCoarse(lat: Double, lon: Double): Pair<String, String> =
        withTimeoutOrNull(3500L) {
            if (!Geocoder.isPresent()) return@withTimeoutOrNull "" to ""
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            val addr = addresses.firstOrNull()
                            val city = addr?.locality ?: addr?.subAdminArea ?: addr?.adminArea ?: ""
                            val country = addr?.countryName ?: ""
                            if (cont.isActive) cont.resume(city to country)
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume("" to "")
                        }
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                val list = runCatching { geocoder.getFromLocation(lat, lon, 1) }.getOrNull()
                val addr = list?.firstOrNull()
                val city = addr?.locality ?: addr?.subAdminArea ?: addr?.adminArea ?: ""
                val country = addr?.countryName ?: ""
                city to country
            }
        } ?: ("" to "")

    private fun geocodeCityViaOpenMeteo(cityQuery: String, fallbackCountry: String): ResolvedLocation {
        return runCatching {
            val encoded = URLEncoder.encode(cityQuery.trim(), "UTF-8")
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=en&format=json"
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ResolvedLocation(cityQuery, fallbackCountry, null, null)
                val body = resp.body.string()
                if (body.isBlank()) return ResolvedLocation(cityQuery, fallbackCountry, null, null)
                val root = json.parseToJsonElement(body).jsonObject
                val first = root["results"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: return ResolvedLocation(cityQuery, fallbackCountry, null, null)
                val name = first["name"]?.jsonPrimitive?.content ?: cityQuery
                val country = first["country"]?.jsonPrimitive?.content ?: fallbackCountry
                val lat = first["latitude"]?.jsonPrimitive?.doubleOrNull
                val lon = first["longitude"]?.jsonPrimitive?.doubleOrNull
                ResolvedLocation(name, country, lat, lon)
            }
        }.getOrDefault(ResolvedLocation(cityQuery, fallbackCountry, null, null))
    }

    private fun fetchOpenMeteoWeather(lat: Double, lon: Double): WeatherResult {
        return runCatching {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,weather_code,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_gusts_10m_max" +
                "&past_days=1&forecast_days=2&timezone=auto"
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return WeatherResult("weather unavailable", "no delta", "")
                val body = resp.body.string()
                if (body.isBlank()) return WeatherResult("weather unavailable", "no delta", "")
                parseOpenMeteoForecast(body)
            }
        }.getOrDefault(WeatherResult("weather unavailable", "steady", ""))
    }

    /**
     * Reads ONLY the count of non-all-day calendar events today when the user explicitly opts in.
     * Never reads or transmits titles, attendees, or notes.
     */
    private fun readCalendarBusynessCount(): String? {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        return runCatching {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val startMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(startMs.toString())
                .appendPath(endMs.toString())
                .build()

            val projection = arrayOf(CalendarContract.Instances._ID)
            val selection = "${CalendarContract.Instances.ALL_DAY} = 0"
            var count = 0
            context.contentResolver.query(uri, projection, selection, null, null)?.use { cursor ->
                count = cursor.count
            }
            when {
                count == 0 -> "completely open schedule (0 meetings today)"
                count in 1..2 -> "light schedule ($count events today)"
                count in 3..5 -> "moderate schedule ($count events today)"
                else -> "intense packed schedule ($count events today — keep composition restorative and calm)"
            }
        }.getOrNull()
    }

    companion object {
        private val parserJson = Json { ignoreUnknownKeys = true }

        internal fun parseOpenMeteoForecast(body: String): WeatherResult {
            val root = parserJson.parseToJsonElement(body).jsonObject
            val current = root["current"]?.jsonObject
            val temp = current?.get("temperature_2m")?.jsonPrimitive?.doubleOrNull
            val code = current?.get("weather_code")?.jsonPrimitive?.intOrNull ?: -1
            val desc = describeWmoCode(code)
            val tempText = temp?.let { "${it.roundToInt()}°C" } ?: ""

            val daily = root["daily"]?.jsonObject
            val maxTemps = daily?.get("temperature_2m_max")?.jsonArray
            val minTemps = daily?.get("temperature_2m_min")?.jsonArray
            val dailyCodes = daily?.get("weather_code")?.jsonArray
            val precipSums = daily?.get("precipitation_sum")?.jsonArray
            val precipProbs = daily?.get("precipitation_probability_max")?.jsonArray
            val windGusts = daily?.get("wind_gusts_10m_max")?.jsonArray

            val yesterdayMax = maxTemps?.getOrNull(0)?.jsonPrimitive?.doubleOrNull
            val todayMax = maxTemps?.getOrNull(1)?.jsonPrimitive?.doubleOrNull
            val todayMin = minTemps?.getOrNull(1)?.jsonPrimitive?.doubleOrNull
            val tomorrowMax = maxTemps?.getOrNull(2)?.jsonPrimitive?.doubleOrNull
            val tomorrowMin = minTemps?.getOrNull(2)?.jsonPrimitive?.doubleOrNull

            val deltaText = if (yesterdayMax != null && todayMax != null) {
                val diff = (todayMax - yesterdayMax).roundToInt()
                when {
                    diff >= 4 -> "${diff}°C warmer jump vs yesterday"
                    diff <= -4 -> "${kotlin.math.abs(diff)}°C colder drop vs yesterday"
                    else -> "steady temperature vs yesterday"
                }
            } else "steady temperature vs yesterday"

            val todayRange = if (todayMin != null && todayMax != null) {
                "day ${todayMin.roundToInt()}°–${todayMax.roundToInt()}°C"
            } else ""

            val summaryParts = buildList {
                val nowStr = listOf(desc, tempText).filter { it.isNotBlank() }.joinToString(", ")
                if (nowStr.isNotBlank()) add(nowStr)
                if (todayRange.isNotBlank()) add(todayRange)
            }

            val todayCode = dailyCodes?.getOrNull(1)?.jsonPrimitive?.intOrNull ?: -1
            val todayDesc = describeWmoCode(todayCode)
            val todayPrecipProb = precipProbs?.getOrNull(1)?.jsonPrimitive?.intOrNull
            val todayPrecipMm = precipSums?.getOrNull(1)?.jsonPrimitive?.doubleOrNull
            val todayGustKmh = windGusts?.getOrNull(1)?.jsonPrimitive?.doubleOrNull

            val tomorrowCode = dailyCodes?.getOrNull(2)?.jsonPrimitive?.intOrNull ?: -1
            val tomorrowDesc = if (tomorrowCode >= 0) describeWmoCode(tomorrowCode) else ""

            val alerts = buildList {
                if ((todayMin != null && todayMin <= 1.0) || (tomorrowMin != null && tomorrowMin <= 0.0)) {
                    val frostVal = listOfNotNull(todayMin, tomorrowMin).minOrNull()?.roundToInt()
                    if (frostVal != null) add("frost / freeze risk (${frostVal}°C low)")
                }
                if (todayCode in setOf(95, 96, 99)) {
                    add("thunderstorm risk today")
                } else if (todayCode in setOf(71, 73, 75, 77, 85, 86)) {
                    add("snowfall expected today")
                } else if ((todayPrecipMm != null && todayPrecipMm >= 8.0) || (todayPrecipProb != null && todayPrecipProb >= 70)) {
                    val mmNote = todayPrecipMm?.let { "${it.roundToInt()}mm" } ?: ""
                    val probNote = todayPrecipProb?.let { "${it}%" } ?: ""
                    add("significant rain (${listOf(mmNote, probNote).filter { it.isNotBlank() }.joinToString(", ")})")
                }
                if (todayGustKmh != null && todayGustKmh >= 50.0) {
                    add("strong wind gusts up to ${todayGustKmh.roundToInt()} km/h")
                }
                if (todayCode in setOf(0, 1) && (todayPrecipProb == null || todayPrecipProb <= 20)) {
                    add("clear sky window tonight for skywatching")
                }
                if (todayMax != null && tomorrowMax != null) {
                    val nextDiff = (tomorrowMax - todayMax).roundToInt()
                    if (kotlin.math.abs(nextDiff) >= 4 || tomorrowCode in setOf(71, 73, 75, 77, 85, 86, 95, 96, 99)) {
                        val trend = if (nextDiff >= 4) "+${nextDiff}°C warmer tomorrow"
                        else if (nextDiff <= -4) "${kotlin.math.abs(nextDiff)}°C colder shift tomorrow"
                        else tomorrowDesc
                        if (trend.isNotBlank()) add("upcoming shift: $trend ($tomorrowDesc)")
                    }
                }
            }

            val outlook = buildString {
                if (todayCode >= 0) {
                    append("Forecast for today: $todayDesc")
                    if (todayRange.isNotBlank()) append(" ($todayRange)")
                }
                if (todayPrecipProb != null && todayPrecipProb >= 35) {
                    append(", ${todayPrecipProb}% precip chance")
                }
                if (alerts.isNotEmpty()) {
                    if (isNotEmpty()) append(" · Key weather cues: ")
                    append(alerts.joinToString("; "))
                }
            }

            return WeatherResult(
                weatherSummary = summaryParts.joinToString(" · "),
                tempDeltaSummary = deltaText,
                outlookSummary = outlook
            )
        }

        private fun describeWmoCode(code: Int): String = when (code) {
            0 -> "clear sky"
            1 -> "mainly clear"
            2 -> "partly cloudy"
            3 -> "overcast grey sky"
            45, 48 -> "fog and mist"
            51, 53, 55 -> "fine drizzle"
            56, 57 -> "freezing drizzle"
            61, 63, 65 -> "rain"
            66, 67 -> "freezing rain"
            71, 73, 75, 77 -> "snowfall"
            80, 81, 82 -> "passing rain showers"
            85, 86 -> "snow showers"
            95, 96, 99 -> "thunderstorm"
            else -> "ambient sky"
        }
    }
}
