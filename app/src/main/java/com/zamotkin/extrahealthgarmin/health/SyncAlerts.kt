package com.zamotkin.extrahealthgarmin.health

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import androidx.core.content.edit
import com.zamotkin.extrahealthgarmin.MainActivity
import com.zamotkin.extrahealthgarmin.R
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Problems the unattended [SyncWorker] cannot fix itself and the user would
 * otherwise only notice when weigh-ins stop arriving in Garmin Connect.
 */
enum class SyncAlert(val notificationId: Int) {
    /** Garmin sign-in is gone; the app must be opened to sign in again. */
    LOGIN_REQUIRED(1),

    /** Health Connect read permissions were revoked. */
    HEALTH_CONNECT_ACCESS_LOST(2),

    /** Garmin refused some weigh-ins; they are dropped for good. */
    RECORDS_REJECTED(3),

    /** Uploads have kept failing for [SyncAlerts.FAILING_THRESHOLD]. */
    SYNC_FAILING(4),

    /** Android may pause the app (and revoke permissions) when unused. */
    APP_MAY_BE_PAUSED(5),

    /** Battery optimization lets Android delay the periodic sync for hours. */
    BATTERY_OPTIMIZED(6);

    companion object {
        /** Device settings that throttle the worker, checked on every pass. */
        val SETTINGS: Set<SyncAlert> = setOf(APP_MAY_BE_PAUSED, BATTERY_OPTIMIZED)
    }
}

/** How one background pass ended, as far as alerts are concerned. */
enum class PassOutcome { UPLOADED, IDLE, RECORDS_REJECTED, LOGIN_REQUIRED, ACCESS_LOST, FAILED }

/** Persisted between passes: the open alerts and when the current failure streak began. */
data class AlertState(
    val shown: Set<SyncAlert> = emptySet(),
    val failingSinceEpochMs: Long? = null
)

data class AlertDecision(
    val state: AlertState,
    val post: Set<SyncAlert>,
    val cancel: Set<SyncAlert>
)

/**
 * Pure decision table (JVM-tested in SyncAlertsTest). Each lasting alert is
 * posted once per episode: a pass with the same problem stays silent, a pass
 * that proves the problem gone cancels it. Settings alerts are re-checked on
 * every pass, whatever the pass outcome. [SyncAlert.RECORDS_REJECTED] is
 * the exception — every rejection is its own event (rejected records are
 * marked processed and never come back), so it is never kept open.
 */
object SyncAlerts {

    val FAILING_THRESHOLD: Duration = Duration.ofHours(24)

    fun outcomeOf(result: SyncResult): PassOutcome = when (result) {
        is SyncResult.Uploaded -> PassOutcome.UPLOADED
        SyncResult.Idle -> PassOutcome.IDLE
        is SyncResult.PartialUploaded -> PassOutcome.RECORDS_REJECTED
        SyncResult.LoginRequired -> PassOutcome.LOGIN_REQUIRED
        SyncResult.Retryable -> PassOutcome.FAILED
    }

    fun decide(
        state: AlertState,
        outcome: PassOutcome,
        settingsAlerts: Set<SyncAlert>,
        nowEpochMs: Long
    ): AlertDecision {
        var open = state.shown - SyncAlert.SETTINGS
        var failingSince = state.failingSinceEpochMs
        when (outcome) {
            // Reached Garmin and uploaded: every sync problem is over.
            PassOutcome.UPLOADED, PassOutcome.RECORDS_REJECTED -> {
                open = emptySet()
                failingSince = null
            }
            // Read Health Connect but may never have contacted Garmin (nothing
            // new to upload), so it says nothing about the Garmin sign-in.
            PassOutcome.IDLE -> open = open - SyncAlert.HEALTH_CONNECT_ACCESS_LOST
            PassOutcome.LOGIN_REQUIRED -> {
                open = open - SyncAlert.HEALTH_CONNECT_ACCESS_LOST - SyncAlert.SYNC_FAILING +
                    SyncAlert.LOGIN_REQUIRED
                failingSince = null
            }
            PassOutcome.ACCESS_LOST -> open = open + SyncAlert.HEALTH_CONNECT_ACCESS_LOST
            PassOutcome.FAILED -> {
                failingSince = failingSince ?: nowEpochMs
                if (nowEpochMs - failingSince >= FAILING_THRESHOLD.toMillis()) {
                    open = open + SyncAlert.SYNC_FAILING
                }
            }
        }
        open = open + settingsAlerts
        val rejected = setOfNotNull(
            SyncAlert.RECORDS_REJECTED.takeIf { outcome == PassOutcome.RECORDS_REJECTED }
        )
        return AlertDecision(
            state = AlertState(open, failingSince),
            post = (open - state.shown) + rejected,
            cancel = state.shown - open
        )
    }
}

/**
 * Posts/cancels the [SyncAlert] notifications for [SyncWorker] and keeps the
 * small [AlertState] in SharedPreferences. Never throws — an alert problem
 * must not fail the sync pass.
 */
class SyncAlertNotifier(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun onPass(outcome: PassOutcome, rejectedCount: Int = 0) {
        try {
            val decision = SyncAlerts.decide(
                state = load(),
                outcome = outcome,
                settingsAlerts = settingsAlerts(),
                nowEpochMs = System.currentTimeMillis()
            )
            save(decision.state)
            val manager = NotificationManagerCompat.from(context)
            decision.cancel.forEach { manager.cancel(it.notificationId) }
            decision.post.forEach { post(manager, it, rejectedCount) }
        } catch (e: Exception) {
            Log.w(TAG, "Sync alert handling failed", e)
        }
    }

    private fun post(manager: NotificationManagerCompat, alert: SyncAlert, rejectedCount: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Notifications not allowed; dropping $alert")
            return
        }
        ensureChannel()
        val (title, text) = when (alert) {
            SyncAlert.LOGIN_REQUIRED ->
                R.string.alert_login_title to context.getString(R.string.alert_login_text)
            SyncAlert.HEALTH_CONNECT_ACCESS_LOST ->
                R.string.alert_hc_access_title to context.getString(R.string.alert_hc_access_text)
            SyncAlert.RECORDS_REJECTED ->
                R.string.alert_rejected_title to
                    context.getString(R.string.alert_rejected_text, rejectedCount)
            SyncAlert.SYNC_FAILING ->
                R.string.alert_failing_title to context.getString(R.string.alert_failing_text)
            SyncAlert.APP_MAY_BE_PAUSED ->
                R.string.alert_paused_title to context.getString(R.string.alert_paused_text)
            SyncAlert.BATTERY_OPTIMIZED ->
                R.string.alert_battery_title to context.getString(R.string.alert_battery_text)
        }
        // Settings alerts open system settings; everything else is fixed in the app.
        val target = when (alert) {
            SyncAlert.APP_MAY_BE_PAUSED ->
                IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)
            // App info rather than the battery-exemption request, which needs a
            // Play-restricted permission; Battery -> Unrestricted is one tap away.
            SyncAlert.BATTERY_OPTIMIZED -> Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
            else -> Intent(context, MainActivity::class.java)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(
            context, alert.notificationId, target,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify(alert.notificationId, notification)
    }

    private fun ensureChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.alert_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    private fun settingsAlerts(): Set<SyncAlert> = setOfNotNull(
        SyncAlert.APP_MAY_BE_PAUSED.takeIf { appMayBePaused() },
        SyncAlert.BATTERY_OPTIMIZED.takeIf {
            !context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
        }
    )

    private fun appMayBePaused(): Boolean = try {
        when (PackageManagerCompat.getUnusedAppRestrictionsStatus(context).get(5, TimeUnit.SECONDS)) {
            UnusedAppRestrictionsConstants.API_30_BACKPORT,
            UnusedAppRestrictionsConstants.API_30,
            UnusedAppRestrictionsConstants.API_31 -> true
            else -> false
        }
    } catch (e: Exception) {
        Log.w(TAG, "Unused-app restriction status unavailable", e)
        false
    }

    private fun load(): AlertState = AlertState(
        shown = prefs.getStringSet(KEY_SHOWN, emptySet()).orEmpty()
            .mapNotNull { name -> SyncAlert.entries.firstOrNull { it.name == name } }
            .toSet(),
        failingSinceEpochMs = prefs.getLong(KEY_FAILING_SINCE, -1L).takeIf { it >= 0 }
    )

    private fun save(state: AlertState) {
        prefs.edit {
            putStringSet(KEY_SHOWN, state.shown.map { it.name }.toSet())
            putLong(KEY_FAILING_SINCE, state.failingSinceEpochMs ?: -1L)
        }
    }

    private companion object {
        const val TAG = "SyncAlerts"
        const val CHANNEL_ID = "sync-problems"
        const val PREFS_NAME = "sync-alerts"
        const val KEY_SHOWN = "shown"
        const val KEY_FAILING_SINCE = "failing_since"
    }
}
