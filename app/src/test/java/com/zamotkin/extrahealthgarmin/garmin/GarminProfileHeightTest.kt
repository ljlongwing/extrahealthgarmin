package com.zamotkin.extrahealthgarmin.garmin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class GarminProfileHeightTest {

    @Test
    fun `parseMeters_readsCentimetres`() {
        val body = """{"id":1,"userData":{"height":195.5800018310547,"weight":112500.0,"measurementSystem":"statute_us"}}"""

        assertEquals(1.9558, GarminProfileHeight.parseMeters(body)!!, 1e-6)
    }

    @Test
    fun `parseMeters_missingOrImplausible_isNull`() {
        assertNull(GarminProfileHeight.parseMeters("""{"userData":{}}"""))
        assertNull(GarminProfileHeight.parseMeters("""{"userData":{"height":null}}"""))
        assertNull(GarminProfileHeight.parseMeters("""{"userData":{"height":0.0}}"""))
        assertNull(GarminProfileHeight.parseMeters("""{"userData":{"height":300.0}}"""))
        assertNull(GarminProfileHeight.parseMeters("{}"))
    }

    @Test
    fun `parseMeters_malformedBody_throwsConnectionError`() {
        try {
            GarminProfileHeight.parseMeters("<html>challenge</html>")
            fail("expected GarminConnectionError")
        } catch (e: GarminConnectionError) {
            // expected
        }
    }

    @Test
    fun `withBmi_fillsOnlyMissingBmi`() {
        val bare = GarminWeighInRecord(1L, weightKg = 110.0f, percentFat = null, bmi = null)
        val withBmi = bare.copy(bmi = 30.0f)

        assertEquals(27.5f, GarminProfileHeight.withBmi(bare, 2.0).bmi!!, 0.001f)
        assertEquals(30.0f, GarminProfileHeight.withBmi(withBmi, 2.0).bmi!!, 0.001f)
    }
}
