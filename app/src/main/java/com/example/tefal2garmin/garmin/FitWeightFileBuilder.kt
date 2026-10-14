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
 *  - one `weight_scale` message per record: timestamp (field 253), weight
 *    (field 0, scale 100), percent_fat (field 1, scale 100), bmi (field 13,
 *    scale 10) — the SDK applies the profile's scale/offset when encoding,
 *    so callers pass real-world floats.
 *
 * Null [GarminWeighInRecord.percentFat]/[GarminWeighInRecord.bmi] leave the
 * field unset (never an encoded zero). Records are range-validated BEFORE
 * encoding — the uint16 wire fields would otherwise silently truncate
 * out-of-range values.
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
        }

    /** Newest record timestamp, used for file_id.time_created (UTC). */
    private fun latestTimestampMs(records: List<GarminWeighInRecord>): Long =
        (records.maxOf { it.epochSecondsUtc } * MILLIS_PER_SECOND)

    private const val MILLIS_PER_SECOND = 1000L
}
