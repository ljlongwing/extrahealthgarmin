package com.example.tefal2garmin.garmin

/**
 * Typed exceptions for the Garmin auth/API layer, mirroring the hierarchy of
 * the python-garminconnect fork's `exceptions.py`:
 *
 *  - [GarminConnectionError] — transport and protocol failures, including
 *    Cloudflare/bot HTML challenges and malformed responses.
 *  - [GarminAuthError] — credentials or tokens were rejected (wrong password,
 *    CAPTCHA required, refresh token dead, token rejected after refresh).
 *  - [GarminTooManyRequestsError] — HTTP 429; never retried by design.
 *  - [GarminNotFoundError] — HTTP 404 on an API resource; subclasses the
 *    connection error like the fork, so broad handlers keep working.
 *
 * All carry a human-readable detail suitable for logs and (later) UI states.
 */
open class GarminConnectionError(message: String, cause: Throwable? = null) : Exception(message, cause)

/** HTTP 404 from a Garmin API endpoint. */
class GarminNotFoundError(message: String) : GarminConnectionError(message)

/** HTTP 429 (header or JSON body). Rate limits are surfaced, never retried. */
open class GarminTooManyRequestsError(message: String) : Exception(message)

/** Credentials/tokens rejected: wrong password, CAPTCHA, dead refresh token. */
class GarminAuthError(message: String) : Exception(message)

/**
 * Keystore-backed encryption failed (keystore unavailable, key corrupted,
 * AES unsupported on this API level). [GarminTokenStore] treats this as
 * "no tokens stored" — it must never crash the app.
 */
class TokenCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
