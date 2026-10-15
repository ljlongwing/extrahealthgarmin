package com.example.tefal2garmin.garmin

import com.garmin.fit.BufferEncoder
import com.garmin.fit.DateTime
import com.garmin.fit.File
import com.garmin.fit.FileIdMesg
import com.garmin.fit.WeightScaleMesg
import java.util.Date

/**
 * Encodes weigh-in records into a weight-scale FIT file with the official
 * Garmin FIT SDK (`com.garmin:fit`), mirroring the python-garminconnect
 * `FitEncoderWeight` wire format:
 *
 *  - one `file_id` message with type WEIGHT and `time_created` (UTC);
 *  - one `weight_scale` message per record. Setters and field scales,
 *    verified against com.garmin:fit:21.176.0 (all uint16):
 *     - timestamp (field 253);
 *     - weight (field 0, scale 100, kg) — `setWeight(Float)`;
 *     - percent_fat (field 1, scale 100, %) — `setPercentFat(Float)`;
 *     - percent_hydration (field 2, scale 100, %) — `setPercentHydration(Float)`;
 *     - bone_mass (field 4, scale 100, kg) — `setBoneMass(Float)`;
 *     - muscle_mass (field 5, scale 100, kg) — `setMuscleMass(Float)`;
 *     - basal_met (field 7, scale 4, kcal/day) — `setBasalMet(Float)`;
 *     - bmi (field 13, scale 10) — `setBmi(Float)`.
 *
 * The SDK applies the profile's scale/offset when encoding, so callers pass
 * real-world floats. Note the naming bridge: Health Connect lean body mass
 * maps to FIT `muscle_mass` (the profile has no lean-mass field), and Health
 * Connect body water MASS arrives here already converted to a percent.
 *
 * Null record fields leave the FIT field unset (never an encoded zero).
 * Records are range-validated BEFORE encoding — the uint16 wire fields would
 * otherwise silently truncate out-of-range values.
 */
object FitWeightFileBuilder {

    /** Returns the encoded FIT bytes; throws [GarminValidationError] first. */
    fun build(records: List<GarminWeighInRecord>): ByteArray {
        GarminWeighInRecord.validateRanges(records)
        val encoder = BufferEncoder()
        encoder.write(fileIdMessage(records))
        records.forEach { encoder.write(weightScaleMessage(it)) }
        return encoder.close()
    }

    private fun fileIdMessage(records: List<GarminWeighInRecord>): FileIdMesg =
        FileIdMesg().apply {
            type = File.WEIGHT
            timeCreated = DateTime(Date(latestTimestampMs(records)))
        }

    private fun weightScaleMessage(record: GarminWeighInRecord): WeightScaleMesg =
        WeightScaleMesg().apply {
            // DateTime(Date) converts Unix epoch ms -> FIT seconds (UTC).
            timestamp = DateTime(Date(record.epochSecondsUtc * MILLIS_PER_SECOND))
            weight = record.weightKg
            record.percentFat?.let { percentFat = it }
            record.bmi?.let { bmi = it }
            record.boneMassKg?.let { boneMass = it }
            record.leanBodyMassKg?.let { muscleMass = it }
            record.bodyWaterPercent?.let { percentHydration = it }
            record.basalMetabolicRateKcal?.let { basalMet = it }
        }

    /** Newest record timestamp, used for file_id.time_created (UTC). */
    private fun latestTimestampMs(records: List<GarminWeighInRecord>): Long =
        (records.maxOf { it.epochSecondsUtc } * MILLIS_PER_SECOND)

    private const val MILLIS_PER_SECOND = 1000L
}
