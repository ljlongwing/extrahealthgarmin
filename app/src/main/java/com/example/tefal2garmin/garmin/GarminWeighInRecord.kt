package com.example.tefal2garmin.garmin

/**
 * One weigh-in destined for a Garmin `weight_scale` FIT upload.
 *
 * The optional fields mirror the Tefal CSV contract: percent fat and BMI may
 * be absent for a row; when null the corresponding FIT field is simply not
 * set (never an encoded zero — that would silently import fake values).
 *
 * Range validation ([validateRanges]) runs BEFORE any encoding or network
 * call: the FIT weight_scale fields are uint16 with scale 100 (weight, fat)
 * and scale 10 (BMI), so the SDK would silently truncate out-of-range
 * floats. The bounds below are human-plausible and deliberately stricter
 * than the wire limits.
 */
data class GarminWeighInRecord(
    /** Measurement time as UTC epoch seconds (FIT timestamps are UTC). */
    val epochSecondsUtc: Long,
    val weightKg: Float,
    val percentFat: Float?,
    val bmi: Float?
) {
    /**
     * Throws [GarminValidationError] when any present value is outside the
     * plausible range: 0 < weight <= 500 kg, 0 <= fat <= 100 %,
     * 0 < bmi <= 100. Non-finite values (NaN/infinity) are rejected too.
     */
    fun validateRanges() {
        if (weightKg.isNaN() || weightKg <= 0f || weightKg > MAX_WEIGHT_KG) {
            throw GarminValidationError(
                "Weight ${weightKg} kg outside plausible range (0, $MAX_WEIGHT_KG]"
            )
        }
        percentFat?.let { fat ->
            if (fat.isNaN() || fat < 0f || fat > MAX_PERCENT_FAT) {
                throw GarminValidationError(
                    "Percent fat $fat % outside plausible range [0, $MAX_PERCENT_FAT]"
                )
            }
        }
        bmi?.let { value ->
            if (value.isNaN() || value <= 0f || value > MAX_BMI) {
                throw GarminValidationError(
                    "BMI $value outside plausible range (0, $MAX_BMI]"
                )
            }
        }
    }

    /** Journal key for this record: the epoch-second UTC string. */
    fun journalKey(): String = epochSecondsUtc.toString()

    companion object {
        const val MAX_WEIGHT_KG = 500f
        const val MAX_PERCENT_FAT = 100f
        const val MAX_BMI = 100f

        /** Validates every record, throwing on the first violation. */
        fun validateRanges(records: List<GarminWeighInRecord>) {
            records.forEach { it.validateRanges() }
        }
    }
}
