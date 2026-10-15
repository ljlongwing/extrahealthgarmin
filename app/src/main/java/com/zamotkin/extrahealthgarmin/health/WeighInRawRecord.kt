package com.zamotkin.extrahealthgarmin.health

import java.time.Instant

/**
 * Which Health Connect record type a raw measurement came from.
 *
 * One flat enum over the seven record types the sync reads: [WEIGHT] anchors
 * a weigh-in, the body-composition types are its companions, and [HEIGHT] is
 * not part of any measurement — it only feeds the cached height used for BMI
 * (see [SyncEngine]).
 */
enum class RawMeasurementType {
    WEIGHT,
    BODY_FAT,
    BONE_MASS,
    LEAN_BODY_MASS,
    BODY_WATER_MASS,
    BASAL_METABOLIC_RATE,
    HEIGHT
}

/**
 * A single Health Connect measurement, flattened to primitive fields so the
 * aggregation and sync logic ([BodyMeasurementAggregator], [SyncEngine]) stay
 * pure Kotlin with zero connect-client types — which is what lets their unit
 * tests run as plain JVM JUnit.
 *
 * @property metadataId the Health Connect record id (`Metadata.id`); for a
 *   weigh-in this is the anchor weight record's id and doubles as the dedup
 *   key in [SyncState.processedRecordIds].
 * @property dataOriginPackage the package that recorded the value — the
 *   aggregator only merges companions whose origin matches the anchor's.
 * @property value the measurement in SI-ish display units: kg for the mass
 *   types, % for body fat, kcal/day for BMR, meters for height. Double to
 *   mirror the connect-client units losslessly; [WeighInMeasurement] narrows
 *   to the Floats the Garmin record carries.
 */
data class WeighInRawRecord(
    val metadataId: String,
    val type: RawMeasurementType,
    val instant: Instant,
    val dataOriginPackage: String,
    val value: Double
)
