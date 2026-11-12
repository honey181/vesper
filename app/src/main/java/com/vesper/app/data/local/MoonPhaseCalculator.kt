package com.vesper.app.data.local

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.floor
import kotlin.math.roundToInt

object MoonPhaseCalculator {
    // Known new moon epoch: 2000-01-06T18:14:00Z
    private const val SYNODIC_MONTH_DAYS = 29.53058867
    private const val EPOCH_SECONDS = 947182440L

    fun describeMoonPhase(instant: Instant = Instant.now()): String {
        val daysSinceEpoch = (instant.epochSecond - EPOCH_SECONDS) / 86400.0
        val cycles = daysSinceEpoch / SYNODIC_MONTH_DAYS
        val phaseFraction = cycles - floor(cycles)
        val illumination = ((1.0 - kotlin.math.cos(phaseFraction * 2.0 * Math.PI)) * 50.0).roundToInt()

        val name = when {
            phaseFraction < 0.03 || phaseFraction > 0.97 -> "New Moon"
            phaseFraction < 0.22 -> "Waxing Crescent"
            phaseFraction < 0.28 -> "First Quarter"
            phaseFraction < 0.47 -> "Waxing Gibbous"
            phaseFraction < 0.53 -> "Full Moon"
            phaseFraction < 0.72 -> "Waning Gibbous"
            phaseFraction < 0.78 -> "Last Quarter"
            else -> "Waning Crescent"
        }
        return "$name (${illumination}% illuminated)"
    }

    fun inferMeteorologicalSeason(date: LocalDate, latitude: Double?): String {
        val northern = (latitude ?: 45.0) >= 0.0
        val month = date.monthValue
        val day = date.dayOfMonth
        val northSeason = when {
            month == 12 || month <= 2 -> "Winter"
            month in 3..5 -> if (month == 3 && day < 20) "Late Winter / Early Spring" else "Spring"
            month in 6..8 -> if (month == 6 && day < 21) "Late Spring / Early Summer" else "Summer"
            else -> if (month == 9 && day < 22) "Late Summer / Early Autumn" else "Autumn"
        }
        if (northern) return northSeason
        return when {
            northSeason.contains("Winter") && northSeason.contains("Spring") -> "Late Summer / Early Autumn"
            northSeason == "Winter" -> "Summer"
            northSeason == "Spring" -> "Autumn"
            northSeason.contains("Spring") -> "Late Autumn / Early Winter"
            northSeason == "Summer" -> "Winter"
            northSeason.contains("Summer") -> "Late Winter / Early Spring"
            else -> "Spring"
        }
    }
}
