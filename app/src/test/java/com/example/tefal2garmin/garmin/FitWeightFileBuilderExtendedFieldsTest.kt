package com.example.tefal2garmin.garmin

import com.garmin.fit.Decoder
import com.garmin.fit.Mesg
import com.garmin.fit.MesgNum
import com.garmin.fit.WeightScaleMesg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * FIT roundtrip tests for the task 2a extended metrics of
 * [FitWeightFileBuilder] — bug-named per the TDD list, decoding with the
 * official SDK [Decoder] (same pattern as GarminUploadClientTest, but on the
 * raw builder bytes instead of a captured multipart part).
 */
class FitWeightFileBuilderExtendedFieldsTest {

    @Test
    fun `extended_fields_roundtrip_through_decoder`() {
        val bytes = FitWeightFileBuilder.build(
            listOf(
                record(
                    boneMassKg = 3.25f,
                    leanBodyMassKg = 55.5f,
                    bodyWaterPercent = 57.5f,
                    basalMetabolicRateKcal = 1650f
                )
            )
        )

        val mesg = decodeSingle(bytes)
        assertEquals(3.25f, mesg.boneMass)
        assertEquals("leanBodyMassKg maps to FIT muscle_mass", 55.5f, mesg.muscleMass)
        assertEquals("bodyWaterPercent maps to FIT percent_hydration", 57.5f, mesg.percentHydration)
        assertEquals("basalMetabolicRateKcal maps to FIT basal_met (scale 4: 1650 is exact)",
            1650f, mesg.basalMet)
    }

    @Test
    fun `all_fields_together_roundtrip_through_decoder`() {
        val bytes = FitWeightFileBuilder.build(
            listOf(
                record(
                    weightKg = 100f,
                    percentFat = 25f,
                    bmi = 30.86f,
                    boneMassKg = 4.5f,
                    leanBodyMassKg = 70f,
                    bodyWaterPercent = 60f,
                    basalMetabolicRateKcal = 1800f
                )
            )
        )

        val mesg = decodeSingle(bytes)
        assertEquals(100f, mesg.weight)
        assertEquals(25f, mesg.percentFat)
        // BMI has profile scale 10: 30.86 -> 308.6 -> round 309 -> 30.9.
        assertEquals(30.9f, mesg.bmi, 0.01f)
        assertEquals(4.5f, mesg.boneMass)
        assertEquals(70f, mesg.muscleMass)
        assertEquals(60f, mesg.percentHydration)
        assertEquals(1800f, mesg.basalMet)
    }

    @Test
    fun `null_extended_fields_encode_nothing`() {
        val bytes = FitWeightFileBuilder.build(
            listOf(record(weightKg = 80f, percentFat = null, bmi = null))
        )

        val mesg = decodeSingle(bytes)
        assertEquals(80f, mesg.weight)
        assertUnset(mesg.percentFat, "percent_fat")
        assertUnset(mesg.boneMass, "bone_mass")
        assertUnset(mesg.muscleMass, "muscle_mass")
        assertUnset(mesg.percentHydration, "percent_hydration")
        assertUnset(mesg.basalMet, "basal_met")
    }

    @Test
    fun `out_of_range_extended_field_rejected_before_encoding`() {
        try {
            FitWeightFileBuilder.build(listOf(record(bodyWaterPercent = 150f)))
            fail("expected GarminValidationError for water percent 150")
        } catch (e: GarminValidationError) {
            assertTrue(e.message!!.contains("water", ignoreCase = true))
        }
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    private fun record(
        epochSecondsUtc: Long = EPOCH,
        weightKg: Float = 80f,
        percentFat: Float? = 22.5f,
        bmi: Float? = 24.5f,
        boneMassKg: Float? = null,
        leanBodyMassKg: Float? = null,
        bodyWaterPercent: Float? = null,
        basalMetabolicRateKcal: Float? = null
    ): GarminWeighInRecord = GarminWeighInRecord(
        epochSecondsUtc = epochSecondsUtc,
        weightKg = weightKg,
        percentFat = percentFat,
        bmi = bmi,
        boneMassKg = boneMassKg,
        leanBodyMassKg = leanBodyMassKg,
        bodyWaterPercent = bodyWaterPercent,
        basalMetabolicRateKcal = basalMetabolicRateKcal
    )

    /** Decodes the builder output and returns the single weight_scale message. */
    private fun decodeSingle(bytes: ByteArray): WeightScaleMesg {
        val decoder = Decoder(bytes)
        val weights = mutableListOf<WeightScaleMesg>()
        decoder.addListener { mesg: Mesg ->
            if (mesg.num == MesgNum.WEIGHT_SCALE) weights += WeightScaleMesg(mesg)
        }
        decoder.read()
        assertEquals(1, weights.size)
        return weights.single()
    }

    /** Unset FIT float fields surface as null or NaN on the JVM API. */
    private fun assertUnset(value: Float?, field: String) {
        assertTrue("$field must be unset for null input, got $value",
            value == null || value.isNaN())
    }

    companion object {
        /** 2025-10-09T09:46:40Z. */
        private const val EPOCH = 1_760_000_000L
    }
}
