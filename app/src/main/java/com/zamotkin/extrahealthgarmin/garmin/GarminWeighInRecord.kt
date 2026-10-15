package com.zamotkin.extrahealthgarmin.garmin

/**
 * One weigh-in destined for a Garmin `weight_scale` FIT upload.
 *
 * The optional fields mirror the Health Connect record contract: percent
 * fat and BMI may be absent for a measurement; when null the corresponding
 * FIT field is simply not
 * set (never an encoded zero — that would silently import fake values).
 * Task 2a added the body-composition quartet from Health Connect
 * (bone mass, lean body mass, body water percent, BMR) with the same rule.
 *
 * Range validation ([validateRanges]) runs BEFORE any encoding or network
 * call: the FIT weight_scale fields are uint16 with scale 100 (weight, fat,
 * bone mass, lean/muscle mass, hydration percent), scale 10 (BMI) and scale
 * 4 (BMR), so the SDK would silently truncate out-of-range floats. The
 * bounds below are human-plausible and deliberately stricter than the wire
 * limits.
 */
data class GarminWeighInRecord(
    /** Measurement time as UTC epoch seconds (FIT timestamps are UTC). */
    val epochSecondsUtc: Long,
    val weightKg: Float,
    val percentFat: Float?,
    val bmi: Float?,
    val boneMassKg: Float? = null,
    val leanBodyMassKg: Float? = null,
    val bodyWaterPercent: Float? = null,
    val basalMetabolicRateKcal: Float? = null
) {
    /**
     * Throws [GarminValidationError] when any present value is outside its
     * plausible range: 0 < weight <= 500 kg, 0 <= fat <= 100 %,
     * 0 < bmi <= 100, 0 <= bone <= 50 kg, 0 <= lean <= 300 kg,
     * 0 <= water <= 100 %, 500 <= BMR <= 10000 kcal/day. Non-finite values
     * (NaN/infinity) are rejected too.
     */
    fun validateRanges() {
        if (!weightKg.isFinite() || weightKg <= 0f || weightKg > MAX_WEIGHT_KG) {
            throw GarminValidationError(
                "Weight ${weightKg} kg outside plausible range (0, $MAX_WEIGHT_KG]"
            )
        }
        percentFat?.let { fat ->
            if (!fat.isFinite() || fat < 0f || fat > MAX_PERCENT_FAT) {
                throw GarminValidationError(
                    "Percent fat $fat % outside plausible range [0, $MAX_PERCENT_FAT]"
                )
            }
        }
        bmi?.let { value ->
            if (!value.isFinite() || value <= 0f || value > MAX_BMI) {
                throw GarminValidationError(
                    "BMI $value outside plausible range (0, $MAX_BMI]"
                )
            }
        }
        boneMassKg?.let { bone ->
            if (!bone.isFinite() || bone < 0f || bone > MAX_BONE_MASS_KG) {
                throw GarminValidationError(
                    "Bone mass $bone kg outside plausible range [0, $MAX_BONE_MASS_KG]"
                )
            }
        }
        leanBodyMassKg?.let { lean ->
            if (!lean.isFinite() || lean < 0f || lean > MAX_LEAN_BODY_MASS_KG) {
                throw GarminValidationError(
                    "Lean body mass $lean kg outside plausible range [0, $MAX_LEAN_BODY_MASS_KG]"
                )
            }
        }
        bodyWaterPercent?.let { water ->
            if (!water.isFinite() || water < 0f || water > MAX_BODY_WATER_PERCENT) {
                throw GarminValidationError(
                    "Body water percent $water % outside plausible range [0, $MAX_BODY_WATER_PERCENT]"
                )
            }
        }
        basalMetabolicRateKcal?.let { bmr ->
            if (!bmr.isFinite() || bmr < MIN_BMR_KCAL || bmr > MAX_BMR_KCAL) {
                throw GarminValidationError(
                    "BMR $bmr kcal/day outside plausible range [$MIN_BMR_KCAL, $MAX_BMR_KCAL]"
                )
            }
        }
    }

    /**
     * Journal key for this record: the composite `epochSecondsUtc:weightKg`.
     *
     * The timestamp alone (date-granular) silently dropped a same-day second
     * measurement with a different weight; the composite key uploads it too,
     * while an identical re-record (same timestamp AND weight) still dedupes.
     * Deliberately ignores the body-composition fields: Garmin replaces the
     * whole weigh-in on re-import, so enriching a journaled measurement with
     * a late companion must not bypass the journal.
     */
    fun journalKey(): String = "$epochSecondsUtc:$weightKg"

    companion object {
        const val MAX_WEIGHT_KG = 500f
        const val MAX_PERCENT_FAT = 100f
        const val MAX_BMI = 100f
        const val MAX_BONE_MASS_KG = 50f
        const val MAX_LEAN_BODY_MASS_KG = 300f
        const val MAX_BODY_WATER_PERCENT = 100f
        const val MIN_BMR_KCAL = 500f

        /** uint16 x scale 4 caps BMR at 65535/4 = 16383.75 kcal/day. */
        const val MAX_BMR_KCAL = 10_000f

        /** Validates every record, throwing on the first violation. */
        fun validateRanges(records: List<GarminWeighInRecord>) {
            records.forEach { it.validateRanges() }
        }
    }
}
