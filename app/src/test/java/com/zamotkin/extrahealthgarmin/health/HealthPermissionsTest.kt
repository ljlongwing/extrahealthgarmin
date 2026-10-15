package com.zamotkin.extrahealthgarmin.health

import androidx.health.connect.client.HealthConnectClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Health Connect permission plumbing for the UI wiring (task 2b):
 * the exact permission set the manifest declares, the raw sdkStatus mapping
 * and the request-set builder. Pure JVM — no Android.
 */
class HealthPermissionsTest {

    @Test
    fun `required set contains exactly the 7 record reads plus background read`() {
        assertEquals(
            setOf(
                "android.permission.health.READ_WEIGHT",
                "android.permission.health.READ_BODY_FAT",
                "android.permission.health.READ_BONE_MASS",
                "android.permission.health.READ_LEAN_BODY_MASS",
                "android.permission.health.READ_BODY_WATER_MASS",
                "android.permission.health.READ_BASAL_METABOLIC_RATE",
                "android.permission.health.READ_HEIGHT",
                "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
            ),
            HealthPermissions.REQUIRED_PERMISSIONS
        )
    }

    @Test
    fun `foreground set is the required set minus the background read`() {
        assertEquals(
            HealthPermissions.REQUIRED_PERMISSIONS -
                HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND,
            HealthPermissions.FOREGROUND_READ_PERMISSIONS
        )
        assertEquals(7, HealthPermissions.FOREGROUND_READ_PERMISSIONS.size)
    }

    @Test
    fun `sdkStatus mapping - available, update required and unavailable`() {
        assertEquals(
            HcSdkStatus.Available,
            HealthPermissions.sdkStatusFromRaw(HealthConnectClient.SDK_AVAILABLE)
        )
        assertEquals(
            HcSdkStatus.NeedsUpdate,
            HealthPermissions.sdkStatusFromRaw(
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED
            )
        )
        assertEquals(
            HcSdkStatus.NotInstalled,
            HealthPermissions.sdkStatusFromRaw(HealthConnectClient.SDK_UNAVAILABLE)
        )
    }

    @Test
    fun `sdkStatus mapping - unknown raw values degrade to NotInstalled`() {
        assertEquals(HcSdkStatus.NotInstalled, HealthPermissions.sdkStatusFromRaw(0))
        assertEquals(HcSdkStatus.NotInstalled, HealthPermissions.sdkStatusFromRaw(Int.MIN_VALUE))
    }

    @Test
    fun `background read is requested only when the provider supports it`() {
        assertEquals(
            HealthPermissions.REQUIRED_PERMISSIONS,
            HealthPermissions.permissionsToRequest(backgroundReadAvailable = true)
        )
        assertEquals(
            HealthPermissions.FOREGROUND_READ_PERMISSIONS,
            HealthPermissions.permissionsToRequest(backgroundReadAvailable = false)
        )
    }

    @Test
    fun `hasForegroundPermissions requires all seven - background read is optional`() {
        val allSeven = HealthPermissions.FOREGROUND_READ_PERMISSIONS
        assertTrue(HealthPermissions.hasForegroundPermissions(allSeven))
        // Background read on top changes nothing — it never gates the sync.
        assertTrue(
            HealthPermissions.hasForegroundPermissions(
                allSeven + HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND
            )
        )
        allSeven.forEach { missing ->
            assertFalse(
                "grants without $missing must not count as synced-in",
                HealthPermissions.hasForegroundPermissions(allSeven - missing)
            )
        }
    }
}
