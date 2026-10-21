package com.zamotkin.extrahealthgarmin.garmin

import org.json.JSONException
import org.json.JSONObject

/**
 * BMI height fallback from the Garmin Connect user profile.
 *
 * BMI needs a height, and Health Connect often has none: few apps write
 * height records, and Health Connect itself has no manual entry. Garmin
 * Connect asks for a height at sign-up, so the profile is the most reliable
 * second source. Read via `GET /userprofile-service/userprofile/user-settings`
 * (`userData.height`, centimetres).
 */
object GarminProfileHeight {

    const val PATH = "/userprofile-service/userprofile/user-settings"

    /**
     * Height in metres from the user-settings response, or null when it is
     * absent or outside [MIN_HEIGHT_M]..[MAX_HEIGHT_M]. Throws
     * [GarminConnectionError] when the body is not the expected JSON object.
     */
    fun parseMeters(body: String): Double? {
        val root = try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw GarminConnectionError("User settings: malformed JSON response", e)
        }
        val cm = root.optJSONObject("userData")?.optDouble("height", Double.NaN) ?: return null
        val meters = cm / CM_PER_M
        return meters.takeIf { it.isFinite() && it in MIN_HEIGHT_M..MAX_HEIGHT_M }
    }

    /** [record] with its BMI computed from [heightMeters] when it has none. */
    fun withBmi(record: GarminWeighInRecord, heightMeters: Double): GarminWeighInRecord =
        if (record.bmi != null) record
        else record.copy(bmi = (record.weightKg / (heightMeters * heightMeters)).toFloat())

    private const val CM_PER_M = 100.0
    private const val MIN_HEIGHT_M = 0.5
    private const val MAX_HEIGHT_M = 2.75
}
