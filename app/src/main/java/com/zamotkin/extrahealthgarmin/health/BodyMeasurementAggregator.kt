package com.zamotkin.extrahealthgarmin.health

import com.zamotkin.extrahealthgarmin.garmin.GarminWeighInRecord
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * One weigh-in assembled from a Health Connect weight anchor plus its
 * body-composition companions. The DTO [SyncEngine] uploads; the field set
 * mirrors [GarminWeighInRecord] one to one.
 *
 * @property recordId the anchor weight record's `Metadata.id`.
 * @property sourceAppPackage the anchor's `DataOrigin.packageName` — kept for
 *   the upload journal and diagnostics.
 */
data class WeighInMeasurement(
    val recordId: String,
    val timestamp: Instant,
    val weightKg: Float,
    val percentFat: Float?,
    val boneMassKg: Float?,
    val leanBodyMassKg: Float?,
    val bodyWaterPercent: Float?,
    val basalMetabolicRateKcal: Float?,
    val bmi: Float?,
    val sourceAppPackage: String
) {
    /** Maps this measurement onto the Garmin upload record (epoch seconds). */
    fun toGarminRecord(): GarminWeighInRecord = GarminWeighInRecord(
        epochSecondsUtc = timestamp.epochSecond,
        weightKg = weightKg,
        percentFat = percentFat,
        bmi = bmi,
        boneMassKg = boneMassKg,
        leanBodyMassKg = leanBodyMassKg,
        bodyWaterPercent = bodyWaterPercent,
        basalMetabolicRateKcal = basalMetabolicRateKcal
    )
}

/**
 * Correlates raw Health Connect records into weigh-in measurements.
 *
 * Rules (Strangler phase 2a spec):
 *  - [RawMeasurementType.WEIGHT] records are anchors; one anchor -> at most
 *    one [WeighInMeasurement].
 *  - Companions (body fat, bone mass, lean body mass, body water mass, BMR)
 *    must come from the SAME `DataOrigin.packageName` as the anchor and lie
 *    within [COMPANION_WINDOW_MS] of it, inclusive — a companion exactly
 *    ±60 s away merges, ±60.001 s does not.
 *  - When several same-type companions qualify, the one nearest the anchor
 *    wins.
 *  - A young anchor (age < [YOUNG_ANCHOR_MAX_AGE_MS]) with no companions is
 *    deferred: body-composition readings often arrive a few seconds after
 *    the weight, so uploading immediately would ship a bare weight that a
 *    later companion can no longer enrich (changes tokens are one-shot).
 *    Deferred anchors are reported, NOT uploaded and NOT marked processed.
 *  - `bodyWaterPercent = waterMassKg / weightKg * 100` (Health Connect keeps
 *    water as a mass; Garmin wants a percent).
 *  - `bmi = weightKg / heightMeters^2` when a height is known.
 *  - Records this app itself wrote (`ownPackage`) never re-import — that
 *    would duplicate measurements round-tripped through Garmin.
 *  - [RawMeasurementType.HEIGHT] records are neither anchors nor companions.
 *
 * Pure function of its inputs — no connect-client or Android types.
 */
object BodyMeasurementAggregator {

    /** Outcome of one aggregation pass. */
    data class AggregationResult(
        val measurements: List<WeighInMeasurement>,
        val deferredAnchorIds: Set<String>
    )

    fun aggregate(
        records: List<WeighInRawRecord>,
        now: Instant,
        ownPackage: String,
        heightMeters: Double?
    ): AggregationResult {
        val candidates = records
            .filter { it.dataOriginPackage != ownPackage }
            .filter { it.type != RawMeasurementType.HEIGHT }

        val measurements = mutableListOf<WeighInMeasurement>()
        val deferredAnchorIds = mutableSetOf<String>()

        for (anchor in candidates) {
            if (anchor.type != RawMeasurementType.WEIGHT) continue

            val companionsByType = companionTypes.associateWith { type ->
                candidates.asSequence()
                    .filter { it.type == type }
                    .filter { it.dataOriginPackage == anchor.dataOriginPackage }
                    .filter { abs(offsetMs(anchor, it)) <= COMPANION_WINDOW_MS }
                    .minByOrNull { abs(offsetMs(anchor, it)) }
            }

            val isYoung = Duration.between(anchor.instant, now).toMillis() <
                YOUNG_ANCHOR_MAX_AGE_MS
            if (isYoung && companionsByType.values.all { it == null }) {
                deferredAnchorIds += anchor.metadataId
                continue
            }

            measurements += buildMeasurement(anchor, companionsByType, heightMeters)
        }

        return AggregationResult(measurements, deferredAnchorIds)
    }

    private val companionTypes = setOf(
        RawMeasurementType.BODY_FAT,
        RawMeasurementType.BONE_MASS,
        RawMeasurementType.LEAN_BODY_MASS,
        RawMeasurementType.BODY_WATER_MASS,
        RawMeasurementType.BASAL_METABOLIC_RATE
    )

    private fun buildMeasurement(
        anchor: WeighInRawRecord,
        companionsByType: Map<RawMeasurementType, WeighInRawRecord?>,
        heightMeters: Double?
    ): WeighInMeasurement {
        val weightKg = anchor.value.toFloat()
        val waterMassKg =
            companionsByType[RawMeasurementType.BODY_WATER_MASS]?.value?.toFloat()
        return WeighInMeasurement(
            recordId = anchor.metadataId,
            timestamp = anchor.instant,
            weightKg = weightKg,
            percentFat = companionsByType[RawMeasurementType.BODY_FAT]?.value?.toFloat(),
            boneMassKg = companionsByType[RawMeasurementType.BONE_MASS]?.value?.toFloat(),
            leanBodyMassKg =
                companionsByType[RawMeasurementType.LEAN_BODY_MASS]?.value?.toFloat(),
            bodyWaterPercent = when {
                waterMassKg != null && weightKg > 0f -> waterMassKg / weightKg * 100f
                else -> null
            },
            basalMetabolicRateKcal =
                companionsByType[RawMeasurementType.BASAL_METABOLIC_RATE]?.value?.toFloat(),
            bmi = when {
                heightMeters != null && heightMeters > 0.0 ->
                    weightKg / (heightMeters * heightMeters).toFloat()
                else -> null
            },
            sourceAppPackage = anchor.dataOriginPackage
        )
    }

    private fun offsetMs(anchor: WeighInRawRecord, other: WeighInRawRecord): Long =
        Duration.between(anchor.instant, other.instant).toMillis()

    /** Signed companion window, ±60 s inclusive. */
    private const val COMPANION_WINDOW_MS = 60_000L

    /** Anchors younger than this without companions wait for a later pass. */
    private const val YOUNG_ANCHOR_MAX_AGE_MS = 30_000L
}
