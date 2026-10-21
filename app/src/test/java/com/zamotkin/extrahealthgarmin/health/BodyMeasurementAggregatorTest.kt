package com.zamotkin.extrahealthgarmin.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Correlation-rule tests for [BodyMeasurementAggregator] — bug-named per the
 * task 2a TDD list (window boundary, origin isolation, nearest-of-type,
 * young-anchor deferral, derived math, own-package skip). Plain JUnit: the
 * aggregator is pure Kotlin with zero connect-client types.
 */
class BodyMeasurementAggregatorTest {

    // ------------------------------------------------------------------ //
    //  Window boundary                                                    //
    // ------------------------------------------------------------------ //

    @Test
    fun `companion_exactly_60s_from_anchor_is_merged`() {
        val anchorInstant = NOW.minusSeconds(120)

        for (offsetMs in listOf(60_000L, -60_000L)) {
            val result = BodyMeasurementAggregator.aggregate(
                records = listOf(
                    weight("w1", anchorInstant),
                    fat("f1", anchorInstant.plusMillis(offsetMs))
                ),
                now = NOW,
                ownPackage = OWN,
                heightMeters = null
            )
            assertEquals("offset ${offsetMs}ms must stay in the ±60s window",
                1, result.measurements.size)
            assertEquals(20f, result.measurements[0].percentFat)
        }
    }

    @Test
    fun `companion_60s_and_1ms_from_anchor_is_excluded`() {
        val anchorInstant = NOW.minusSeconds(120)

        for (offsetMs in listOf(60_001L, -60_001L)) {
            val result = BodyMeasurementAggregator.aggregate(
                records = listOf(
                    weight("w1", anchorInstant),
                    fat("f1", anchorInstant.plusMillis(offsetMs))
                ),
                now = NOW,
                ownPackage = OWN,
                heightMeters = null
            )
            assertEquals("offset ${offsetMs}ms must fall outside the ±60s window",
                1, result.measurements.size)
            assertNull("out-of-window fat must not leak into the measurement",
                result.measurements[0].percentFat)
        }
    }

    // ------------------------------------------------------------------ //
    //  Origin isolation                                                   //
    // ------------------------------------------------------------------ //

    @Test
    fun `companions_from_different_data_origin_not_merged`() {
        val t = NOW.minusSeconds(120)

        val result = BodyMeasurementAggregator.aggregate(
            records = listOf(
                weight("w1", t, origin = "com.vendor.scale"),
                fat("f1", t.plusSeconds(5), origin = "com.other.app")
            ),
            now = NOW,
            ownPackage = OWN,
            heightMeters = null
        )

        assertEquals(1, result.measurements.size)
        assertNull(result.measurements[0].percentFat)
    }

    // ------------------------------------------------------------------ //
    //  Nearest-of-type                                                    //
    // ------------------------------------------------------------------ //

    @Test
    fun `zero_valued_companions_count_as_not_measured`() {
        val t = NOW.minusSeconds(120)

        val result = BodyMeasurementAggregator.aggregate(
            records = listOf(
                weight("w1", t),
                fat("f0", t.plusSeconds(1), percent = 0.0),
                bone("b0", t.plusSeconds(1), massKg = 0.0),
                water("h0", t.plusSeconds(1), massKg = 0.0),
                bmr("m0", t.plusSeconds(1), kcal = 0.0)
            ),
            now = NOW,
            ownPackage = OWN,
            heightMeters = null
        )

        val m = result.measurements.single()
        assertNull("0 % fat means bioimpedance failed, not 0 % fat", m.percentFat)
        assertNull(m.boneMassKg)
        assertNull(m.bodyWaterPercent)
        assertNull(m.basalMetabolicRateKcal)
    }

    @Test
    fun `nearest_same_type_companion_wins`() {
        val t = NOW.minusSeconds(120)

        val result = BodyMeasurementAggregator.aggregate(
            records = listOf(
                weight("w1", t),
                fat("far", t.plusSeconds(40), percent = 31.5),
                fat("near", t.plusSeconds(5), percent = 20.5)
            ),
            now = NOW,
            ownPackage = OWN,
            heightMeters = null
        )

        assertEquals(1, result.measurements.size)
        assertEquals("the companion nearest the anchor must win",
            20.5f, result.measurements[0].percentFat)
    }

    // ------------------------------------------------------------------ //
    //  Young-anchor deferral                                              //
    // ------------------------------------------------------------------ //

    @Test
    fun `young_anchor_without_companions_is_deferred`() {
        val young = NOW.minusSeconds(10)

        val result = BodyMeasurementAggregator.aggregate(
            records = listOf(weight("w1", young)),
            now = NOW,
            ownPackage = OWN,
            heightMeters = null
        )

        assertTrue("a <30s lone anchor must not upload yet", result.measurements.isEmpty())
        assertEquals(setOf("w1"), result.deferredAnchorIds)
    }

    @Test
    fun `young_anchor_with_companion_is_not_deferred`() {
        val young = NOW.minusSeconds(10)

        val result = BodyMeasurementAggregator.aggregate(
            records = listOf(
                weight("w1", young),
                fat("f1", young.plusSeconds(2))
            ),
            now = NOW,
            ownPackage = OWN,
            heightMeters = null
        )

        assertEquals("a young anchor WITH companions is already complete",
            1, result.measurements.size)
        assertTrue(result.deferredAnchorIds.isEmpty())
    }

    @Test
    fun `old_lone_anchor_still_produces_measurement`() {
        val old = NOW.minusSeconds(600)

        val result = BodyMeasurementAggregator.aggregate(
            records = listOf(weight("w1", old)),
            now = NOW,
            ownPackage = OWN,
            heightMeters = null
        )

        assertEquals("an anchor past the young window uploads even without companions",
            1, result.measurements.size)
        assertEquals(80f, result.measurements[0].weightKg)
    }

    @Test
    fun `anchor_29s_old_is_young_but_31s_old_is_not`() {
        val youngResult = BodyMeasurementAggregator.aggregate(
            records = listOf(weight("young", NOW.minusSeconds(29))),
            now = NOW, ownPackage = OWN, heightMeters = null
        )
        assertTrue("a 29s-old lone anchor is still young",
            youngResult.measurements.isEmpty() && youngResult.deferredAnchorIds.size == 1)

        val oldResult = BodyMeasurementAggregator.aggregate(
            records = listOf(weight("old", NOW.minusSeconds(31))),
            now = NOW, ownPackage = OWN, heightMeters = null
        )
        assertEquals(1, oldResult.measurements.size)
    }

    // ------------------------------------------------------------------ //
    //  Derived metrics                                                    //
    // ------------------------------------------------------------------ //

    @Test
    fun `body_water_mass_converted_to_percent`() {
        val t = NOW.minusSeconds(120)

        val result = BodyMeasurementAggregator.aggregate(
            records = listOf(
                weight("w1", t, kg = 100.0),
                water("h1", t.plusSeconds(3), massKg = 50.0)
            ),
            now = NOW,
            ownPackage = OWN,
            heightMeters = null
        )

        assertEquals(1, result.measurements.size)
        assertEquals("50 kg water / 100 kg weight must be 50 %",
            50.0f, result.measurements[0].bodyWaterPercent!!, 0.001f)
    }

    @Test
    fun `bmi_computed_from_height`() {
        val t = NOW.minusSeconds(120)

        val result = BodyMeasurementAggregator.aggregate(
            records = listOf(weight("w1", t, kg = 80.0)),
            now = NOW,
            ownPackage = OWN,
            heightMeters = 1.80
        )

        assertEquals(1, result.measurements.size)
        assertEquals("80 kg / (1.80 m)^2 must be 24.69",
            24.69f, result.measurements[0].bmi!!, 0.01f)
    }

    @Test
    fun `bmi_null_when_height_missing`() {
        val result = BodyMeasurementAggregator.aggregate(
            records = listOf(weight("w1", NOW.minusSeconds(120), kg = 80.0)),
            now = NOW,
            ownPackage = OWN,
            heightMeters = null
        )

        assertNull(result.measurements[0].bmi)
    }

    // ------------------------------------------------------------------ //
    //  Own-package skip + multi-anchor                                    //
    // ------------------------------------------------------------------ //

    @Test
    fun `own_package_records_ignored`() {
        val t = NOW.minusSeconds(120)

        val result = BodyMeasurementAggregator.aggregate(
            records = listOf(
                weight("w1", t, origin = OWN),
                fat("f1", t.plusSeconds(2), origin = OWN)
            ),
            now = NOW,
            ownPackage = OWN,
            heightMeters = null
        )

        assertTrue("records this app wrote itself must never re-import",
            result.measurements.isEmpty())
    }

    @Test
    fun `two_anchors_produce_two_measurements`() {
        val t1 = NOW.minus(Duration.ofMinutes(30))
        val t2 = NOW.minus(Duration.ofMinutes(10))

        val result = BodyMeasurementAggregator.aggregate(
            records = listOf(
                weight("w1", t1, kg = 80.0),
                bone("b1", t1.plusSeconds(4), massKg = 3.2),
                weight("w2", t2, kg = 80.5),
                lean("l2", t2.plusSeconds(6), massKg = 60.0)
            ),
            now = NOW,
            ownPackage = OWN,
            heightMeters = null
        )

        assertEquals(2, result.measurements.size)
        assertEquals(listOf("w1", "w2"), result.measurements.map { it.recordId })
        assertEquals(3.2f, result.measurements[0].boneMassKg)
        assertEquals(60f, result.measurements[1].leanBodyMassKg)
    }

    @Test
    fun `toGarminRecord_maps_all_fields`() {
        val t = NOW.minusSeconds(120)

        val garmin = BodyMeasurementAggregator.aggregate(
            records = listOf(
                weight("w1", t, kg = 100.0),
                fat("f1", t.plusSeconds(1), percent = 25.0),
                bone("b1", t.plusSeconds(2), massKg = 3.5),
                lean("l1", t.plusSeconds(3), massKg = 70.0),
                water("h1", t.plusSeconds(4), massKg = 57.5),
                bmr("m1", t.plusSeconds(5), kcal = 1650.0)
            ),
            now = NOW,
            ownPackage = OWN,
            heightMeters = 1.80
        ).measurements.single().toGarminRecord()

        assertEquals(t.epochSecond, garmin.epochSecondsUtc)
        assertEquals(100f, garmin.weightKg)
        assertEquals(25f, garmin.percentFat)
        assertEquals(3.5f, garmin.boneMassKg)
        assertEquals(70f, garmin.leanBodyMassKg)
        assertEquals(57.5f, garmin.bodyWaterPercent!!, 0.001f)
        assertEquals(1650f, garmin.basalMetabolicRateKcal)
        assertEquals("100 / 1.8^2 = 30.86", 30.86f, garmin.bmi!!, 0.01f)
    }

    // ------------------------------------------------------------------ //
    //  Helpers                                                            //
    // ------------------------------------------------------------------ //

    private fun weight(
        id: String,
        instant: Instant,
        kg: Double = 80.0,
        origin: String = VENDOR
    ): WeighInRawRecord = WeighInRawRecord(
        metadataId = id,
        type = RawMeasurementType.WEIGHT,
        instant = instant,
        dataOriginPackage = origin,
        value = kg
    )

    private fun fat(
        id: String,
        instant: Instant,
        percent: Double = 20.0,
        origin: String = VENDOR
    ): WeighInRawRecord = WeighInRawRecord(
        metadataId = id,
        type = RawMeasurementType.BODY_FAT,
        instant = instant,
        dataOriginPackage = origin,
        value = percent
    )

    private fun bone(id: String, instant: Instant, massKg: Double, origin: String = VENDOR) =
        WeighInRawRecord(id, RawMeasurementType.BONE_MASS, instant, origin, massKg)

    private fun lean(id: String, instant: Instant, massKg: Double, origin: String = VENDOR) =
        WeighInRawRecord(id, RawMeasurementType.LEAN_BODY_MASS, instant, origin, massKg)

    private fun water(id: String, instant: Instant, massKg: Double, origin: String = VENDOR) =
        WeighInRawRecord(id, RawMeasurementType.BODY_WATER_MASS, instant, origin, massKg)

    private fun bmr(id: String, instant: Instant, kcal: Double, origin: String = VENDOR) =
        WeighInRawRecord(id, RawMeasurementType.BASAL_METABOLIC_RATE, instant, origin, kcal)

    companion object {
        /** 2026-10-14T10:00:00Z — fixed so `now` is deterministic. */
        private val NOW = Instant.parse("2026-10-14T10:00:00Z")
        private const val VENDOR = "com.vendor.scale"
        private const val OWN = "com.zamotkin.extrahealthgarmin"
    }
}
