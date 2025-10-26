package com.example.tefal2garmin.utils

import org.junit.Test
import org.junit.Assert.*
import java.util.*

class CsvValidatorTest {
    
    private val validator = CsvValidator()
    
    @Test
    fun testValidateRecord_ValidRecord() {
        val record = mapOf(
            "Дата" to "2024-01-15",
            "Вес (кг)" to "70.5",
            "ИМТ" to "23.5",
            "Жир (% тела)" to "18.2"
        )
        
        val result = validator.validateRecord(record)
        
        assertTrue("Record should be valid", result.isValid)
        assertTrue("Should have no missing fields", result.missingFields.isEmpty())
        assertTrue("Should have no invalid fields", result.invalidFields.isEmpty())
        assertTrue("Should have no modified values", result.modifiedValues.isEmpty())
        assertEquals("Message should indicate validity", "Record is valid", result.message)
    }
    
    @Test
    fun testValidateRecord_MissingRequiredFields() {
        val record = mapOf(
            "ИМТ" to "23.5",
            "Жир (% тела)" to "18.2"
        )
        
        val result = validator.validateRecord(record)
        
        assertFalse("Record should be invalid", result.isValid)
        assertEquals("Should detect missing fields", 2, result.missingFields.size)
        assertTrue("Should include missing date", result.missingFields.contains("Дата"))
        assertTrue("Should include missing weight", result.missingFields.contains("Вес (кг)"))
        assertEquals("Should provide defaults for missing fields", 2, result.modifiedValues.size)
    }
    
    @Test
    fun testValidateRecord_InvalidDate() {
        val record = mapOf(
            "Дата" to "invalid-date",
            "Вес (кг)" to "70.5"
        )
        
        val result = validator.validateRecord(record)
        
        assertFalse("Record should be invalid", result.isValid)
        assertEquals("Should have no missing fields", 0, result.missingFields.size)
        assertEquals("Should detect invalid date", 1, result.invalidFields.size)
        assertTrue("Should include invalid date in message", result.invalidFields.any { it.contains("Дата") })
        assertEquals("Should provide default date", 1, result.modifiedValues.size)
    }
    
    @Test
    fun testValidateRecord_InvalidWeight() {
        val record = mapOf(
            "Дата" to "2024-01-15",
            "Вес (кг)" to "invalid-weight"
        )
        
        val result = validator.validateRecord(record)
        
        assertFalse("Record should be invalid", result.isValid)
        assertEquals("Should have no missing fields", 0, result.missingFields.size)
        assertEquals("Should detect invalid weight", 1, result.invalidFields.size)
        assertTrue("Should include invalid weight in message", result.invalidFields.any { it.contains("Вес (кг)") })
        assertEquals("Should provide default weight", 1, result.modifiedValues.size)
    }
    
    @Test
    fun testValidateRecord_InvalidBMI() {
        val record = mapOf(
            "Дата" to "2024-01-15",
            "Вес (кг)" to "70.5",
            "ИМТ" to "999.0"
        )
        
        val result = validator.validateRecord(record)
        
        assertFalse("Record should be invalid", result.isValid)
        assertEquals("Should have no missing fields", 0, result.missingFields.size)
        assertEquals("Should detect invalid BMI", 1, result.invalidFields.size)
        assertTrue("Should include invalid BMI in message", result.invalidFields.any { it.contains("ИМТ") })
        assertEquals("Should provide default BMI", 1, result.modifiedValues.size)
    }
    
    @Test
    fun testValidateRecord_InvalidFat() {
        val record = mapOf(
            "Дата" to "2024-01-15",
            "Вес (кг)" to "70.5",
            "Жир (% тела)" to "150.0"
        )
        
        val result = validator.validateRecord(record)
        
        assertFalse("Record should be invalid", result.isValid)
        assertEquals("Should have no missing fields", 0, result.missingFields.size)
        assertEquals("Should detect invalid fat", 1, result.invalidFields.size)
        assertTrue("Should include invalid fat in message", result.invalidFields.any { it.contains("Жир") })
        assertEquals("Should provide default fat", 1, result.modifiedValues.size)
    }
    
    @Test
    fun testValidateRecord_ValidDateFormats() {
        val validDates = listOf(
            "2024-01-15",
            "15.01.2024",
            "15/01/2024",
            "01/15/2024"
        )
        
        validDates.forEach { date ->
            val record = mapOf(
                "Дата" to date,
                "Вес (кг)" to "70.5"
            )
            
            val result = validator.validateRecord(record)
            assertTrue("Date format '$date' should be valid: ${result.message}", result.isValid)
        }
    }
    
    @Test
    fun testValidateRecord_ValidWeightFormats() {
        val validWeights = listOf("70.5", "70,5", "80", "65.2")
        
        validWeights.forEach { weight ->
            val record = mapOf(
                "Дата" to "2024-01-15",
                "Вес (кг)" to weight
            )
            
            val result = validator.validateRecord(record)
            assertTrue("Weight '$weight' should be valid: ${result.message}", result.isValid)
        }
    }
    
    @Test
    fun testValidateRecord_EmptyOptionalFields() {
        val record = mapOf(
            "Дата" to "2024-01-15",
            "Вес (кг)" to "70.5",
            "ИМТ" to "",
            "Жир (% тела)" to ""
        )

        val result = validator.validateRecord(record)

        assertTrue("Record should be valid with empty optional fields", result.isValid)
        assertTrue("Should have no modified values for empty fields", result.modifiedValues.isEmpty())
    }

    // Additional comprehensive tests for missing and invalid fields

    @Test
    fun testValidateRecord_MissingDateOnly() {
        val record = mapOf(
            "Вес (кг)" to "70.5",
            "ИМТ" to "23.5",
            "Жир (% тела)" to "18.2"
        )

        val result = validator.validateRecord(record)

        assertFalse("Record should be invalid without date", result.isValid)
        assertEquals("Should detect one missing field", 1, result.missingFields.size)
        assertTrue("Should include missing date", result.missingFields.contains("Дата"))
        assertEquals("Should provide default date", 1, result.modifiedValues.size)
        assertTrue("Should provide default date", result.modifiedValues.containsKey("Дата"))
        assertTrue("Default date should be in correct format", result.modifiedValues["Дата"]?.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) == true)
    }

    @Test
    fun testValidateRecord_MissingWeightOnly() {
        val record = mapOf(
            "Дата" to "2024-01-15",
            "ИМТ" to "23.5",
            "Жир (% тела)" to "18.2"
        )

        val result = validator.validateRecord(record)

        assertFalse("Record should be invalid without weight", result.isValid)
        assertEquals("Should detect one missing field", 1, result.missingFields.size)
        assertTrue("Should include missing weight", result.missingFields.contains("Вес (кг)"))
        assertEquals("Should provide default weight", 1, result.modifiedValues.size)
        assertEquals("Default weight should be 0.0", "0.0", result.modifiedValues["Вес (кг)"])
    }

    @Test
    fun testValidateRecord_InvalidDateAndMissingWeight() {
        val record = mapOf(
            "Дата" to "not-a-date",
            "ИМТ" to "23.5",
            "Жир (% тела)" to "18.2"
        )

        val result = validator.validateRecord(record)

        assertFalse("Record should be invalid", result.isValid)
        assertEquals("Should detect one missing field", 1, result.missingFields.size)
        assertTrue("Should include missing weight", result.missingFields.contains("Вес (кг)"))
        assertEquals("Should detect one invalid field", 1, result.invalidFields.size)
        assertTrue("Should include invalid date", result.invalidFields.any { it.contains("Дата") })
        assertEquals("Should provide two modified values", 2, result.modifiedValues.size)
    }

    @Test
    fun testValidateRecord_InvalidNumericFields() {
        val record = mapOf(
            "Дата" to "2024-01-15",
            "Вес (кг)" to "70.5",
            "ИМТ" to "not-a-number",
            "Жир (% тела)" to "invalid-percent"
        )

        val result = validator.validateRecord(record)

        assertFalse("Record should be invalid with invalid numeric fields", result.isValid)
        assertEquals("Should detect two invalid fields", 2, result.invalidFields.size)
        assertTrue("Should include invalid BMI", result.invalidFields.any { it.contains("ИМТ") })
        assertTrue("Should include invalid fat", result.invalidFields.any { it.contains("Жир") })
        assertEquals("Should provide two modified values", 2, result.modifiedValues.size)
    }

    @Test
    fun testValidateRecord_AllFieldsMissing() {
        val record = mapOf<String, String>()

        val result = validator.validateRecord(record)

        assertFalse("Record should be invalid with all fields missing", result.isValid)
        assertEquals("Should detect two missing required fields", 2, result.missingFields.size)
        assertTrue("Should include missing date", result.missingFields.contains("Дата"))
        assertTrue("Should include missing weight", result.missingFields.contains("Вес (кг)"))
        assertEquals("Should provide two modified values", 2, result.modifiedValues.size)
    }

    @Test
    fun testValidateRecord_NegativeWeight() {
        val record = mapOf(
            "Дата" to "2024-01-15",
            "Вес (кг)" to "-5.0",
            "ИМТ" to "23.5",
            "Жир (% тела)" to "18.2"
        )

        val result = validator.validateRecord(record)

        assertFalse("Record should be invalid with negative weight", result.isValid)
        assertEquals("Should detect one invalid field", 1, result.invalidFields.size)
        assertTrue("Should include invalid weight", result.invalidFields.any { it.contains("Вес") })
        assertEquals("Should provide one modified value", 1, result.modifiedValues.size)
    }

    @Test
    fun testValidateRecord_WeightTooHigh() {
        val record = mapOf(
            "Дата" to "2024-01-15",
            "Вес (кг)" to "1500.0",
            "ИМТ" to "23.5",
            "Жир (% тела)" to "18.2"
        )

        val result = validator.validateRecord(record)

        assertFalse("Record should be invalid with excessively high weight", result.isValid)
        assertEquals("Should detect one invalid field", 1, result.invalidFields.size)
        assertTrue("Should include invalid weight", result.invalidFields.any { it.contains("Вес") })
    }

    @Test
    fun testValidateRecord_NegativeFatPercentage() {
        val record = mapOf(
            "Дата" to "2024-01-15",
            "Вес (кг)" to "70.5",
            "ИМТ" to "23.5",
            "Жир (% тела)" to "-5.0"
        )

        val result = validator.validateRecord(record)

        assertFalse("Record should be invalid with negative fat percentage", result.isValid)
        assertEquals("Should detect one invalid field", 1, result.invalidFields.size)
        assertTrue("Should include invalid fat", result.invalidFields.any { it.contains("Жир") })
        assertEquals("Should provide one modified value", 1, result.modifiedValues.size)
    }

    @Test
    fun testValidateRecord_FatPercentageOver100() {
        val record = mapOf(
            "Дата" to "2024-01-15",
            "Вес (кг)" to "70.5",
            "ИМТ" to "23.5",
            "Жир (% тела)" to "150.0"
        )

        val result = validator.validateRecord(record)

        assertFalse("Record should be invalid with fat percentage over 100", result.isValid)
        assertEquals("Should detect one invalid field", 1, result.invalidFields.size)
        assertTrue("Should include invalid fat", result.invalidFields.any { it.contains("Жир") })
    }

    @Test
    fun testValidateRecord_MixedValidAndInvalid() {
        val record = mapOf(
            "Дата" to "2024-01-15",  // Valid
            "Вес (кг)" to "70.5",    // Valid
            "ИМТ" to "not-a-number", // Invalid
            "Жир (% тела)" to "18.2" // Valid
        )

        val result = validator.validateRecord(record)

        assertFalse("Record should be invalid due to BMI", result.isValid)
        assertTrue("Should have no missing fields", result.missingFields.isEmpty())
        assertEquals("Should detect one invalid field", 1, result.invalidFields.size)
        assertTrue("Should include invalid BMI", result.invalidFields.any { it.contains("ИМТ") })
        assertEquals("Should provide one modified value", 1, result.modifiedValues.size)
    }

    @Test
    fun testValidateRecord_WhitespaceInFields() {
        val record = mapOf(
            "Дата" to "  2024-01-15  ",
            "Вес (кг)" to "  70.5  ",
            "ИМТ" to "  23.5  ",
            "Жир (% тела)" to "  18.2  "
        )

        val result = validator.validateRecord(record)

        assertTrue("Record should be valid with whitespace-trimmed fields", result.isValid)
        assertTrue("Should have no issues", result.missingFields.isEmpty() && result.invalidFields.isEmpty())
    }

    @Test
    fun testValidateRecord_AllFieldsEmpty() {
        val record = mapOf(
            "Дата" to "",
            "Вес (кг)" to "",
            "ИМТ" to "",
            "Жир (% тела)" to ""
        )

        val result = validator.validateRecord(record)

        assertFalse("Record should be invalid with empty required fields", result.isValid)
        assertEquals("Should detect two missing fields", 2, result.missingFields.size)
        assertEquals("Should provide two modified values", 2, result.modifiedValues.size)
    }
}