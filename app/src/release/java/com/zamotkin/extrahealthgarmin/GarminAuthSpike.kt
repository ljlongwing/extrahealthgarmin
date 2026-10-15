package com.zamotkin.extrahealthgarmin

/**
 * Release no-op for the debug-only Garmin DI-auth live spike. The real
 * implementation lives in the debug source set (`app/src/debug/...`) and
 * consumes the GARMINSPIKE_* BuildConfig fields that exist ONLY in the
 * debug build type — release BuildConfig carries none of them, so release
 * code cannot reference them and links this no-op instead.
 */
object GarminAuthSpike {
    @Suppress("UNUSED_PARAMETER")
    fun trigger(activity: MainActivity) {
        // Intentionally empty in release builds.
    }
}
