# Extra Health Garmin — Release Notes

## Version 2.0.0 — The Health Connect Pivot

### Overview

**Extra Health Garmin** (formerly a scale-CSV converter — see the 1.0.0 notes below) is now a
Health Connect → Garmin Connect sync app. Instead of converting manually-exported CSV files,
it reads body-composition measurements straight from Health Connect and uploads the weigh-ins
into Garmin Connect automatically.

### What changed

- **Health Connect as the source**: reads weight, body fat, bone mass, lean body mass, body
  water, basal metabolic rate and height records (7 types), with an initial 30-day backfill and
  incremental changes-token syncs afterwards
- **Smart weigh-in aggregation**: measurements taken at the same moment are merged into one
  weigh-in; the newest height record is cached so BMI keeps working
- **Automatic upload to Garmin Connect**: weigh-ins are encoded as a FIT file (official Garmin
  FIT SDK) and pushed via the DI OAuth2 upload endpoint — the CSV import flow is gone entirely
- **Garmin login with MFA**: one-time sign-in (email + password + verification code); only
  Keystore-encrypted session tokens persist, credentials never touch disk
- **Background sync**: a WorkManager job runs the same sync pass roughly every 3 hours
  (network-constrained, exponential backoff) alongside the manual **Sync now** button
- **Dedup journal + offline retry**: uploaded record ids are journaled on-device so nothing is
  ever uploaded twice; failed records stay retriable for the next pass
- **Rebrand**: new name, package `com.zamotkin.extrahealthgarmin`, new vector adaptive launcher
  icon (heart + ECG pulse on navy)
- **Platform floor raised**: minSdk moves from 21 to 26 (Android 8.0) — required by the Health
  Connect client library

### Upgrade notes

- The new app installs **alongside** version 1.x (different application id); the old app must be
  uninstalled manually if no longer wanted
- The old CSV state (dedup journal entries from CSV uploads) is not migrated — Health Connect
  becomes the single source of truth
- Garmin login must be performed once after upgrading (the encrypted token store starts clean)

### Technical

- **Minimum Android version**: API 26 (Android 8.0)
- **Target Android version**: API 34
- **Compile SDK**: 36
- **Architecture**: MVVM (single activity), `health/` + `garmin/` + `data/` + `ui/` packages,
  sync state in one JSON file in `filesDir`
- **Tests**: 152 unit tests (JUnit4 + Mockito + coroutines-test + Health Connect fakes +
  MockWebServer)

---

## Version 1.0.0 — Original CSV Converter Release

The original MVP: an Android utility that accepted Goodvibes scale CSV exports via the Android
share system, validated and converted them into the Garmin Connect Fitbit-CSV import format,
saved them to Downloads and opened the Garmin web import. Multilingual (EN/RU) UI, minSdk 21.
Later 1.x iterations added direct upload to Garmin Connect via the DI OAuth2 API with a
converted-CSV fallback and a dedup journal. Fully superseded by 2.0.0.

**Release date**: October 2025
