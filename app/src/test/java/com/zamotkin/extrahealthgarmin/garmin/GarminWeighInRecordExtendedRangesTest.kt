package com.zamotkin.extrahealthgarmin.garmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Range-boundary tests for the task 2a metric extension of
 * [GarminWeighInRecord] — bug-named per the TDD list: just-in passes,
 * just-out (and NaN) fail per field, legacy construction keeps null defaults.
 */
class GarminWeighInRecordExtendedRangesTest {

    // ------------------------------------------------------------------ //
    //  boneMassKg: [0, 50]                                                //
    // ------------------------------------------------------------------ //

    @Test
    fun `bone_mass_0_and_50_pass_just_out_fails`() {
        valid(rec(boneMassKg = 0f))
        valid(rec(boneMassKg = 50f))
        invalid(rec(boneMassKg = -0.1f), "bone")
        invalid(rec(boneMassKg = 50.01f), "bone")
        invalid(rec(boneMassKg = Float.NaN), "bone")
    }

    // ------------------------------------------------------------------ //
    //  leanBodyMassKg: [0, 300]                                           //
    // ------------------------------------------------------------------ //

    @Test
    fun `lean_mass_0_and_300_pass_just_out_fails`() {
        valid(rec(leanBodyMassKg = 0f))
        valid(rec(leanBodyMassKg = 300f))
        invalid(rec(leanBodyMassKg = -0.1f), "lean")
        invalid(rec(leanBodyMassKg = 300.01f), "lean")
        invalid(rec(leanBodyMassKg = Float.NaN), "lean")
    }

    // ------------------------------------------------------------------ //
    //  bodyWaterPercent: [0, 100]                                         //
    // ------------------------------------------------------------------ //

    @Test
    fun `water_percent_0_and_100_pass_just_out_fails`() {
        valid(rec(bodyWaterPercent = 0f))
        valid(rec(bodyWaterPercent = 100f))
        invalid(rec(bodyWaterPercent = -0.1f), "water")
        invalid(rec(bodyWaterPercent = 100.01f), "water")
        invalid(rec(bodyWaterPercent = Float.NaN), "water")
    }

    // ------------------------------------------------------------------ //
    //  basalMetabolicRateKcal: [500, 10000]                               //
    // ------------------------------------------------------------------ //

    @Test
    fun `bmr_500_and_10000_pass_just_out_fails`() {
        valid(rec(basalMetabolicRateKcal = 500f))
        valid(rec(basalMetabolicRateKcal = 10_000f))
        invalid(rec(basalMetabolicRateKcal = 499.9f), "BMR")
        invalid(rec(basalMetabolicRateKcal = 10_000.1f), "BMR")
        invalid(rec(basalMetabolicRateKcal = Float.NaN), "BMR")
    }

    // ------------------------------------------------------------------ //
    //  Wire limits of the FIT profile (uint16 x scale)                    //
    // ------------------------------------------------------------------ //

    @Test
    fun `new_field_maxima_fit_their_fit_wire_fields`() {
        // weight_scale profile (verified against com.garmin:fit:21.176.0):
        // bone_mass/muscle_mass scale 100, percent_hydration scale 100,
        // basal_met scale 4 — all uint16 (max 65535).
        assertTrue(GarminWeighInRecord.MAX_BONE_MASS_KG * 100 <= 65535f)
        assertTrue(GarminWeighInRecord.MAX_LEAN_BODY_MASS_KG * 100 <= 65535f)
        assertTrue(GarminWeighInRecord.MAX_BODY_WATER_PERCENT * 100 <= 65535f)
        assertTrue(GarminWeighInRecord.MAX_BMR_KCAL * 4 <= 65535f)
    }

    // ------------------------------------------------------------------ //
    //  Backwards compatibility                                            //
    // ------------------------------------------------------------------ //

    @Test
    fun `legacy_constructor_leaves_new_fields_null`() {
        val record = GarminWeighInRecord(1_760_000_000L, 80f, 22.5f, 24.7f)

        assertNull(record.boneMassKg)
        assertNull(record.leanBodyMassKg)
        assertNull(record.bodyWaterPercent)
        assertNull(record.basalMetabolicRateKcal)
        record.validateRanges()
    }

    @Test
    fun `journal_key_unchanged_by_new_fields`() {
        val legacy = GarminWeighInRecord(1_760_000_000L, 80f, 22.5f, 24.7f)
        val extended = legacy.copy(
            boneMassKg = 3.2f,
            leanBodyMassKg = 60f,
            bodyWaterPercent = 57.5f,
            basalMetabolicRateKcal = 1650f
        )

        assertEquals("journalKey must stay timestamp:weight regardless of new fields",
            legacy.journalKey(), extended.journalKey())
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    private fun rec(
        weightKg: Float = 80f,
        percentFat: Float? = 22.5f,
        bmi: Float? = 24.7f,
        boneMassKg: Float? = null,
        leanBodyMassKg: Float? = null,
        bodyWaterPercent: Float? = null,
        basalMetabolicRateKcal: Float? = null
    ): GarminWeighInRecord = GarminWeighInRecord(
        epochSecondsUtc = 1_760_000_000L,
        weightKg = weightKg,
        percentFat = percentFat,
        bmi = bmi,
        boneMassKg = boneMassKg,
        leanBodyMassKg = leanBodyMassKg,
        bodyWaterPercent = bodyWaterPercent,
        basalMetabolicRateKcal = basalMetabolicRateKcal
    )

    @Test
    fun `infinite_values_rejected_in_every_field`() {
        // NaN-only checks let infinity reach the FIT uint16 encoder (cast
        // saturates/wraps silently) — task 5 fix: isFinite everywhere.
        invalid(rec(weightKg = Float.POSITIVE_INFINITY), "weight")
        invalid(rec(percentFat = Float.POSITIVE_INFINITY), "fat")
        invalid(rec(bmi = Float.NEGATIVE_INFINITY), "BMI")
        invalid(rec(boneMassKg = Float.POSITIVE_INFINITY), "bone")
        invalid(rec(leanBodyMassKg = Float.POSITIVE_INFINITY), "lean")
        invalid(rec(bodyWaterPercent = Float.POSITIVE_INFINITY), "water")
        invalid(rec(basalMetabolicRateKcal = Float.POSITIVE_INFINITY), "BMR")
        valid(rec())
    }

    private fun valid(record: GarminWeighInRecord) = record.validateRanges()

    private fun invalid(record: GarminWeighInRecord, fieldName: String) {
        try {
            record.validateRanges()
            fail("expected GarminValidationError for $fieldName = $record")
        } catch (e: GarminValidationError) {
            assertTrue("message must name the field '$fieldName': ${e.message}",
                e.message!!.contains(fieldName, ignoreCase = true))
        }
    }
}
