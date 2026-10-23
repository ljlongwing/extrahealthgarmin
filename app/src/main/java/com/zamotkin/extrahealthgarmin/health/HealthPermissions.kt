package com.zamotkin.extrahealthgarmin.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient

/**
 * Availability of the Health Connect SDK/provider on this device (task 2b).
 * Maps the flat [HealthConnectClient.getSdkStatus] ints onto the three states
 * the UI distinguishes.
 */
enum class HcSdkStatus {
    /** Provider not installed (or unknown) — UI offers the Play Store link. */
    NotInstalled,

    /** Provider installed but too old — UI offers the Play Store link too. */
    NeedsUpdate,

    /** Provider present and new enough to talk to. */
    Available
}

/**
 * Health Connect permission plumbing for the UI layer (task 2b): the exact
 * permission strings the manifest declares, the sdkStatus mapping and the
 * permission-request set builder. Everything here is plain data + pure
 * functions over strings and ints, so the JVM unit tests need no Android.
 *
 * The 7 record-read permissions gate the sync (foreground reads). The
 * background-read permission is *opportunistic*: it is requested whenever the
 * provider supports it (see [permissionsToRequest]) but never gates the UI —
 * on Android 14 or an old provider apk it cannot be granted at all, in which
 * case the periodic SyncWorker simply catches the SecurityException and
 * succeeds without data while manual foreground sync keeps working.
 *
 * The string values must stay in sync with the `uses-permission` entries in
 * AndroidManifest.xml and with the runtime strings
 * `HealthPermission.getReadPermission` produces for the seven synced record
 * types (see [HealthConnectSource.SYNCED_RECORD_TYPES]).
 */
object HealthPermissions {

    // The eight uses-permission strings from AndroidManifest.xml —
    // HealthPermissionsTest pins the exact set.
    const val READ_WEIGHT = "android.permission.health.READ_WEIGHT"
    const val READ_BODY_FAT = "android.permission.health.READ_BODY_FAT"
    const val READ_BONE_MASS = "android.permission.health.READ_BONE_MASS"
    const val READ_LEAN_BODY_MASS = "android.permission.health.READ_LEAN_BODY_MASS"
    const val READ_BODY_WATER_MASS = "android.permission.health.READ_BODY_WATER_MASS"
    const val READ_BASAL_METABOLIC_RATE =
        "android.permission.health.READ_BASAL_METABOLIC_RATE"
    const val READ_HEIGHT = "android.permission.health.READ_HEIGHT"
    const val READ_HEALTH_DATA_IN_BACKGROUND =
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    /** Optional: enables blood pressure sync, never gates the weigh-in sync. */
    const val READ_BLOOD_PRESSURE = "android.permission.health.READ_BLOOD_PRESSURE"

    /** The reads a sync pass needs while the app is in the foreground. */
    val FOREGROUND_READ_PERMISSIONS: Set<String> = setOf(
        READ_WEIGHT,
        READ_BODY_FAT,
        READ_BONE_MASS,
        READ_LEAN_BODY_MASS,
        READ_BODY_WATER_MASS,
        READ_BASAL_METABOLIC_RATE,
        READ_HEIGHT
    )

    /** Everything the app declares — the foreground set plus background reads. */
    val REQUIRED_PERMISSIONS: Set<String> =
        FOREGROUND_READ_PERMISSIONS + READ_HEALTH_DATA_IN_BACKGROUND

    /** Raw [HealthConnectClient.getSdkStatus] value to UI status. */
    fun sdkStatusFromRaw(rawSdkStatus: Int): HcSdkStatus = when (rawSdkStatus) {
        HealthConnectClient.SDK_AVAILABLE -> HcSdkStatus.Available
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcSdkStatus.NeedsUpdate
        // SDK_UNAVAILABLE and anything unknown degrade to NotInstalled.
        else -> HcSdkStatus.NotInstalled
    }

    /** Provider availability on this device. */
    fun sdkStatus(context: Context): HcSdkStatus =
        sdkStatusFromRaw(HealthConnectClient.getSdkStatus(context))

    /**
     * The set to hand to the Health Connect permission screen. Background
     * reads are only requested when the provider supports them — on an old
     * provider apk that row could never be granted.
     */
    fun permissionsToRequest(backgroundReadAvailable: Boolean): Set<String> =
        (if (backgroundReadAvailable) REQUIRED_PERMISSIONS else FOREGROUND_READ_PERMISSIONS) +
            READ_BLOOD_PRESSURE

    /** Whether [granted] covers every foreground read (the sync gate). */
    fun hasForegroundPermissions(granted: Set<String>): Boolean =
        granted.containsAll(FOREGROUND_READ_PERMISSIONS)
}
