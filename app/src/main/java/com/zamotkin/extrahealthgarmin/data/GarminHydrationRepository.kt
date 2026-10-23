package com.zamotkin.extrahealthgarmin.data

import android.content.Context
import android.util.Log
import com.zamotkin.extrahealthgarmin.garmin.ApiGetResult
import com.zamotkin.extrahealthgarmin.garmin.GarminAuthClient
import com.zamotkin.extrahealthgarmin.garmin.GarminTokenStore
import com.zamotkin.extrahealthgarmin.garmin.GarminTokens
import com.zamotkin.extrahealthgarmin.garmin.KeystoreAesCrypto
import com.zamotkin.extrahealthgarmin.garmin.SharedPreferencesTokenPersistence
import com.zamotkin.extrahealthgarmin.health.HydrationGateway
import com.zamotkin.extrahealthgarmin.health.HydrationLog
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Network seam for [GarminHydrationRepository] (fakes in unit tests). */
internal fun interface HydrationRemote {
    suspend fun apiPutJson(tokens: GarminTokens, path: String, json: String): ApiGetResult
}

/**
 * Garmin side of hydration sync: one PUT to the daily hydration log per
 * entry (python-garminconnect's add_hydration_data endpoint), in order, stopping
 * at the first failure. Never throws.
 */
class GarminHydrationRepository internal constructor(
    private val remote: HydrationRemote,
    private val tokenStore: GarminTokenStore
) : HydrationGateway {

    constructor(context: Context) : this(
        remote = GarminAuthClient().let { auth -> HydrationRemote(auth::apiPutJson) },
        tokenStore = GarminTokenStore(SharedPreferencesTokenPersistence(context), KeystoreAesCrypto())
    )

    override suspend fun send(logs: List<HydrationLog>): Int {
        var tokens = tokenStore.load() ?: return 0
        var sent = 0
        try {
            for (log in logs) {
                val body = JSONObject()
                    .put("calendarDate", log.calendarDate)
                    .put("timestampLocal", log.timestampLocal)
                    .put("valueInML", log.valueMl)
                tokens = remote.apiPutJson(tokens, LOG_PATH, body.toString()).tokens
                sent++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Auth problems included: the weigh-in pass owns token clearing and the sign-in alert.
            Log.w(TAG, "Hydration: log call failed after $sent of ${logs.size}; the rest wait", e)
        }
        tokenStore.save(tokens)
        Log.d(TAG, "Hydration: $sent of ${logs.size} log call(s) sent")
        return sent
    }

    private companion object {
        const val TAG = "GarminHydration"
        const val LOG_PATH = "/usersummary-service/usersummary/hydration/log"
    }
}
