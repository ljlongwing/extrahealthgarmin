package com.zamotkin.extrahealthgarmin.garmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class GarminExistingWeighInsTest {

    @Test
    fun `parse_readsEveryMetric_gramsToKg`() {
        val weighIns = GarminExistingWeighIns.parse(RANGE_RESPONSE)

        assertEquals(
            listOf(
                GarminExistingWeighIn(LocalDate.parse("2026-10-21"), 110.9, 1_792_583_209L),
                GarminExistingWeighIn(LocalDate.parse("2026-10-20"), 111.4, 1_792_496_778L),
                GarminExistingWeighIn(LocalDate.parse("2026-10-20"), 111.2, 1_792_521_600L)
            ),
            weighIns
        )
    }

    @Test
    fun `parse_skipsEntriesWithoutDateOrWeight`() {
        val body = """
            {"dailyWeightSummaries":[{"allWeightMetrics":[
              {"calendarDate":"2026-10-21","weight":null},
              {"calendarDate":"","weight":80000.0},
              {"weight":80000.0},
              {"calendarDate":"2026-10-21","weight":80000.0}
            ]},{"summaryDate":"2026-10-20"}]}
        """.trimIndent()

        assertEquals(
            listOf(GarminExistingWeighIn(LocalDate.parse("2026-10-21"), 80.0)),
            GarminExistingWeighIns.parse(body)
        )
    }

    @Test
    fun `parse_emptyRange_isEmpty`() {
        assertTrue(GarminExistingWeighIns.parse("""{"dailyWeightSummaries":[]}""").isEmpty())
        assertTrue(GarminExistingWeighIns.parse("{}").isEmpty())
    }

    @Test
    fun `parse_malformedBody_throwsConnectionError`() {
        try {
            GarminExistingWeighIns.parse("<html>challenge</html>")
            fail("expected GarminConnectionError")
        } catch (e: GarminConnectionError) {
            // expected
        }
    }

    @Test
    fun `isAlreadyPresent_sameDayWithinPointOneKg`() {
        val day = LocalDate.parse("2026-10-21")
        val record = GarminWeighInRecord(MORNING, weightKg = 110.9f, percentFat = null, bmi = null)

        assertTrue(GarminExistingWeighIns.isAlreadyPresent(
            record, listOf(GarminExistingWeighIn(day, 110.95)), ZoneOffset.UTC))
        assertFalse(GarminExistingWeighIns.isAlreadyPresent(
            record, listOf(GarminExistingWeighIn(day, 111.05)), ZoneOffset.UTC))
        assertFalse(GarminExistingWeighIns.isAlreadyPresent(record, emptyList(), ZoneOffset.UTC))
    }

    @Test
    fun `isAlreadyPresent_sameTimestamp_differentRounding_matches`() {
        // Real case: the same scale reading routed through Fitbit landed in
        // Garmin 0.1 kg lower but with the identical timestamp.
        val record = GarminWeighInRecord(MORNING, weightKg = 111.7f, percentFat = 23.9f, bmi = null)
        val viaFitbit = GarminExistingWeighIn(LocalDate.parse("2026-10-21"), 111.6, MORNING)

        assertTrue(GarminExistingWeighIns.isAlreadyPresent(record, listOf(viaFitbit), ZoneOffset.UTC))
    }

    @Test
    fun `isAlreadyPresent_timestampWindowIsSixtySeconds`() {
        val record = GarminWeighInRecord(MORNING, weightKg = 111.7f, percentFat = null, bmi = null)
        fun at(offset: Long) = listOf(
            GarminExistingWeighIn(LocalDate.parse("2026-10-21"), 111.5, MORNING + offset)
        )

        assertTrue(GarminExistingWeighIns.isAlreadyPresent(record, at(60), ZoneOffset.UTC))
        assertTrue(GarminExistingWeighIns.isAlreadyPresent(record, at(-60), ZoneOffset.UTC))
        assertFalse(GarminExistingWeighIns.isAlreadyPresent(record, at(61), ZoneOffset.UTC))
    }

    @Test
    fun `rangePath_includesAllEntries`() {
        assertEquals(
            "/weight-service/weight/range/2026-10-20/2026-10-22?includeAll=true",
            GarminExistingWeighIns.rangePath(LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-22"))
        )
    }

    companion object {
        /** 2026-10-21T11:46:49Z. */
        private const val MORNING = 1_792_583_209L

        /** Trimmed shape of a real weight-service range response. */
        private val RANGE_RESPONSE = """
            {"dailyWeightSummaries":[
              {"summaryDate":"2026-10-21","numOfWeightEntries":1,"allWeightMetrics":[
                {"samplePk":1,"date":1792561609000,"calendarDate":"2026-10-21","weight":110900.0,
                 "timestampGMT":1792583209000,"sourceType":"INDEX_SCALE","bodyFat":null,"bmi":29.0}]},
              {"summaryDate":"2026-10-20","numOfWeightEntries":2,"allWeightMetrics":[
                {"samplePk":2,"date":1792475178000,"calendarDate":"2026-10-20","weight":111400.0,
                 "timestampGMT":1792496778000,"sourceType":"INDEX_SCALE","bodyFat":null,"bmi":29.1},
                {"samplePk":3,"date":1792500000000,"calendarDate":"2026-10-20","weight":111200.0,
                 "timestampGMT":1792521600000,"sourceType":"MANUAL","bodyFat":null,"bmi":29.1}]}
            ],"totalAverage":{},"previousDateWeight":{},"nextDateWeight":{}}
        """.trimIndent()
    }
}
