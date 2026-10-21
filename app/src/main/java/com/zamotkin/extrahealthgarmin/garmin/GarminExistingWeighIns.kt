package com.zamotkin.extrahealthgarmin.garmin

import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlin.math.abs

/** A weigh-in Garmin Connect already holds, as reported by the weight-service. */
data class GarminExistingWeighIn(
    /** Garmin's `calendarDate`: the local day the weigh-in belongs to. */
    val calendarDate: LocalDate,
    val weightKg: Double,
    /** Garmin's `timestampGMT` in epoch seconds; null when absent. */
    val epochSecondsUtc: Long? = null
)

/**
 * Server-side duplicate check against Garmin Connect's own weigh-in list.
 *
 * The local [UploadJournal] only knows what THIS install uploaded; weigh-ins
 * that reached Garmin some other way (a previous install, another sync tool,
 * manual entry) would be uploaded again. Before uploading, the repository
 * reads `GET /weight-service/weight/range/{from}/{to}?includeAll=true` and
 * skips every record [isAlreadyPresent] matches.
 *
 * A record matches an existing weigh-in when either
 *  - their timestamps are within [TIMESTAMP_TOLERANCE_SECONDS], whatever the
 *    weights: routes through other services keep the scale's timestamp but
 *    round the weight differently (e.g. via pounds), easily by 0.1 kg; or
 *  - they fall on the same local calendar day with weights within
 *    [WEIGHT_TOLERANCE_KG]: for tools that re-time the weigh-in (upload time,
 *    whole minutes) but keep the value.
 */
object GarminExistingWeighIns {

    /** Weigh-ins this close in time are the same measurement. */
    const val TIMESTAMP_TOLERANCE_SECONDS = 60L

    /** Weights closer than this on the same day count as the same weigh-in. */
    const val WEIGHT_TOLERANCE_KG = 0.1

    /** Endpoint path for [from]..[to] inclusive (local dates). */
    fun rangePath(from: LocalDate, to: LocalDate): String =
        "/weight-service/weight/range/$from/$to?includeAll=true"

    /**
     * Parses the weight-service range response. Garmin reports weight in
     * grams and `timestampGMT` in epoch milliseconds; entries without a
     * usable date or weight are skipped. Throws [GarminConnectionError] when
     * the body is not the expected JSON object.
     */
    fun parse(body: String): List<GarminExistingWeighIn> {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw GarminConnectionError("Weight range: malformed JSON response", e)
        }
        val summaries = root.optJSONArray("dailyWeightSummaries") ?: return emptyList()
        val result = mutableListOf<GarminExistingWeighIn>()
        for (i in 0 until summaries.length()) {
            val metrics = summaries.optJSONObject(i)?.optJSONArray("allWeightMetrics") ?: continue
            for (j in 0 until metrics.length()) {
                val metric = metrics.optJSONObject(j) ?: continue
                val grams = metric.optDouble("weight", Double.NaN)
                val date = try {
                    LocalDate.parse(metric.optString("calendarDate"))
                } catch (e: DateTimeParseException) {
                    null
                }
                if (date == null || !grams.isFinite() || grams <= 0.0) continue
                val timestampMs = metric.optLong("timestampGMT", 0L)
                result += GarminExistingWeighIn(
                    calendarDate = date,
                    weightKg = grams / GRAMS_PER_KG,
                    epochSecondsUtc = if (timestampMs > 0L) timestampMs / MS_PER_SECOND else null
                )
            }
        }
        return result
    }

    /** True when [existing] already holds [record] (see the class match rule). */
    fun isAlreadyPresent(
        record: GarminWeighInRecord,
        existing: List<GarminExistingWeighIn>,
        zone: ZoneId
    ): Boolean {
        val day = localDate(record, zone)
        return existing.any { garmin ->
            val sameMoment = garmin.epochSecondsUtc != null &&
                abs(garmin.epochSecondsUtc - record.epochSecondsUtc) <= TIMESTAMP_TOLERANCE_SECONDS
            val sameDaySameWeight = garmin.calendarDate == day &&
                abs(garmin.weightKg - record.weightKg) < WEIGHT_TOLERANCE_KG
            sameMoment || sameDaySameWeight
        }
    }

    /** The local day [record] falls on in [zone]. */
    fun localDate(record: GarminWeighInRecord, zone: ZoneId): LocalDate =
        Instant.ofEpochSecond(record.epochSecondsUtc).atZone(zone).toLocalDate()

    private const val GRAMS_PER_KG = 1000.0
    private const val MS_PER_SECOND = 1000L
}
