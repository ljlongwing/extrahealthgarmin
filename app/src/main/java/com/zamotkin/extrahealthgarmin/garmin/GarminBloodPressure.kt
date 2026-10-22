package com.zamotkin.extrahealthgarmin.garmin

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * One blood pressure reading ready for Garmin Connect.
 *
 * [localEpochSeconds] is the reading's LOCAL wall-clock time encoded as if it
 * were UTC: Garmin's blood pressure import reads the FIT timestamp as local
 * time (verified live — a UTC timestamp landed 6 h off), unlike weight.
 * [pulse] is required: Garmin rejects a blood_pressure message without
 * heart_rate ("An unexpected error occurred while processing the file").
 */
data class GarminBloodPressureReading(
    val localEpochSeconds: Long,
    val systolic: Int,
    val diastolic: Int,
    val pulse: Int
) {
    /** Dedup key against readings Garmin already holds: local minute + values. */
    fun key(): String = key(localEpochSeconds, systolic, diastolic)

    companion object {
        // python-garminconnect set_blood_pressure ranges (mmHg / bpm).
        val SYSTOLIC_RANGE = 70..260
        val DIASTOLIC_RANGE = 40..150
        val PULSE_RANGE = 20..250

        fun key(localEpochSeconds: Long, systolic: Int, diastolic: Int): String =
            "${localEpochSeconds / 60}:$systolic/$diastolic"
    }
}

/** Pure parsers for the two Garmin GET responses blood pressure sync needs. */
object GarminBloodPressureParsing {

    /**
     * Heart rate (bpm) nearest to [atEpochMs] from a
     * `/wellness-service/wellness/dailyHeartRate?date=` body, if a sample lies
     * within [windowMs]. Null means "not (yet) known": the watch was off or
     * has not synced that stretch to Garmin yet.
     */
    fun nearestHeartRate(body: String, atEpochMs: Long, windowMs: Long): Int? {
        val values = JSONObject(body).optJSONArray("heartRateValues") ?: return null
        var best: Pair<Long, Int>? = null
        for (i in 0 until values.length()) {
            val sample = values.optJSONArray(i) ?: continue
            if (sample.isNull(0) || sample.isNull(1)) continue
            val distance = abs(sample.getLong(0) - atEpochMs)
            if (distance <= windowMs && (best == null || distance < best.first)) {
                best = distance to sample.getInt(1)
            }
        }
        return best?.second
    }

    /**
     * [GarminBloodPressureReading.key]s of the readings in a
     * `/bloodpressure-service/bloodpressure/range/{from}/{to}` body.
     */
    fun existingKeys(body: String): Set<String> {
        val summaries = JSONObject(body).optJSONArray("measurementSummaries") ?: return emptySet()
        val keys = mutableSetOf<String>()
        for (i in 0 until summaries.length()) {
            val measurements = summaries.optJSONObject(i)?.optJSONArray("measurements") ?: continue
            for (j in 0 until measurements.length()) {
                val m = measurements.optJSONObject(j) ?: continue
                val local = m.optString("measurementTimestampLocal").takeIf { it.isNotEmpty() } ?: continue
                // Garmin's local stamp looks like "2026-10-21T21:31:00.0".
                val seconds = LocalDateTime.parse(local.substringBefore('.'))
                    .toEpochSecond(ZoneOffset.UTC)
                keys += GarminBloodPressureReading.key(seconds, m.optInt("systolic"), m.optInt("diastolic"))
            }
        }
        return keys
    }
}
