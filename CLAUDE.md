# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Single-module Android app (Kotlin, Gradle wrapper). minSdk 26 / targetSdk 34 / compileSdk 36.

```bash
./gradlew assembleDebug                # debug APK
./gradlew assembleRelease              # release APK (needs local app/release.keystore — gitignored)
./gradlew testDebugUnitTest            # all unit tests (JUnit4 + Mockito + coroutines-test + HC fakes + MockWebServer)
# Single class / single method:
./gradlew testDebugUnitTest --tests "com.zamotkin.extrahealthgarmin.health.SyncEngineTest"
./gradlew testDebugUnitTest --tests "com.zamotkin.extrahealthgarmin.garmin.UploadJournalTest.*"
./gradlew lintDebug
./debug_setup.sh                       # build + install + launch debug APK on the connected device
adb logcat -s MainActivity MainViewModel SyncWorker   # runtime logs (per-class TAGs)
```

App version: `versionCode`/`versionName` in `app/build.gradle`.

## Architecture

Extra Health Garmin syncs body-composition weigh-ins from **Health Connect** into **Garmin Connect**.
MVVM: `MainActivity` → `MainViewModel` (LiveData<`MainUiState`>) → `HealthSyncFacade` → `SyncEngine` →
`GarminRepository.uploadNewRecords` → `GarminUploadClient` (FIT upload via `FitWeightFileBuilder`).
No DI framework — wiring is manual in `HealthSyncWiring.facade` / `MainViewModelFactory`. Single
activity, no fragments; views via `findViewById`.

Packages under `app/src/main/java/com/zamotkin/extrahealthgarmin/`:

- `health/` — the sync core. `HealthConnectSource` reads the seven record types (weight, body fat,
  bone mass, lean body mass, body water, BMR, height) and manages changes tokens (first run = 30-day
  backfill; expired token = range re-read since the last sync anchor). `BodyMeasurementAggregator`
  correlates same-time measurements into one weigh-in (anchor + companions; the newest height record
  is cached so BMI survives). `SyncEngine` runs one pass: fetch → cache height → aggregate → dedup
  (against `SyncState.processedRecordIds`) → upload, mapping onto `SyncResult`
  (Idle/Uploaded/PartialUploaded/Retryable/LoginRequired). `SyncWorker` is the WorkManager ~3-hourly
  periodic pass over the same engine (network-constrained, exponential backoff; only Retryable asks
  WorkManager to retry). `HealthPermissions` pins the permission set mirrored in the manifest.
- `garmin/` — the Garmin side. `GarminAuthClient` implements DI OAuth2 login with MFA resume
  (garth/python-garminconnect wire contract); tokens live in `GarminTokenStore`, AES-encrypted by
  `TokenCrypto` (Android Keystore, alias `extrahealthgarmin_di_tokens`). `FitWeightFileBuilder`
  encodes weigh-ins into a FIT weight_scale file (official Garmin FIT SDK); `GarminUploadClient`
  uploads it; `UploadJournal` is the timestamp dedup journal; `RedactingHttpLogger` keeps secrets out
  of debug logs (inactive in release).
- `data/` — `GarminRepository` (`GarminUploadOrchestrator`): the UI-facing gateway that dedups
  against the journal, uploads new records as one batch and reports `UploadOutcome`; never throws.
- `ui/` — `MainViewModel` + factory.

State is deliberately one JSON file in `filesDir` (`hc-sync-state.json`, see `SyncStateStore`):
changes token, last-sync instant, processed-record-id dedup set, cached height. Atomic writes
(temp file + rename). No Room, no DataStore.

### Main flow

1. `MainActivity.onCreate` enqueues the periodic `SyncWorker` (idempotent) and renders the
   Health Connect status line; the permission screen is launched via the Activity Result API
   contract from `PermissionController`.
2. Manual sync ("Sync now") runs `HealthSyncFacade.sync()` directly so the UI gets instant
   `MainUiState` updates; the background worker runs the identical pass without touching UI state.
3. When no valid Garmin token is stored, the flow surfaces `LoginRequired` and the login dialog
   (`GarminAuthClient`, email + password + MFA) runs before the next attempt; token refresh is
   automatic thereafter.
4. Failed records are never journaled — they stay retriable and ride with the next sync pass.

## Conventions

- All UI strings via `R.string` — English in `res/values/strings.xml`, Russian in
  `res/values-ru/strings.xml`; keep both in sync (the app name "Extra Health Garmin" is the
  identical brand string in both locales — it is not translated).
- Logging: `android.util.Log` with a per-class `TAG` in the `companion object`.
- No DI framework — wiring stays manual and explicit.
- The toolchain is Gradle 9.6.0 / JDK 26 with a single AGP version (9.4.1) declared only in the
  root `build.gradle` `plugins {}` block — no `buildscript {}` classpath. AGP 9 compiles Kotlin via
  its built-in Kotlin support, so `org.jetbrains.kotlin.android` is not applied anywhere and
  Kotlin's `jvmTarget` defaults to `compileOptions.targetCompatibility` (Java 11, required by
  work-runtime 2.12.0's inline functions). `gradle.properties` keeps only `android.useAndroidX`,
  `org.gradle.jvmargs` and `android.enableResourceOptimizations=false` (the latter keeps the
  adaptive launcher icon rendering on API 37).
- JSpecify-era API notes: AndroidX dependencies are migrating to JSpecify nullness annotations —
  expect new/changed nullness warnings when bumping them; fix Kotlin call sites rather than
  suppressing.
- Changes to sync logic, aggregation, FIT encoding or auth must come with a unit test — the suite
  mirrors the packages, one test class per production class in
  `app/src/test/java/com/zamotkin/extrahealthgarmin/` (`health/`, `garmin/`, `data/`, `ui/`).

## Tools: when to use what

- **fetch** — live content of a specific URL: web docs, changelogs, raw HTML, localhost. Not for library docs (that's context7) and not for "where to read" lookups (web search).
- **context7** — BEFORE writing code against a library/framework (AndroidX, Lifecycle, Coroutines, WorkManager, Health Connect, Gradle): exact signatures, configuration, migrations. Even if you "remember the API" — the model's data may be stale. Order: `resolve-library-id`, then `query-docs`; one query — one concept. Not for business logic or general Kotlin concepts.
- **serena** — semantic navigation and edits: `find_symbol`, `find_referencing_symbols`, `replace_symbol_body` instead of grep+sed wherever exact symbol boundaries matter. File overview — `get_symbols_overview` rather than reading the whole file; at the start of a coding task — `initial_instructions`.
- **claude-mem** — memory across sessions. Before design/debugging/review — `mem_search`: don't re-solve what's solved. Durable lessons — `save_memory` (after a duplicate search); current to-do lists — `work_state_write`.

## Process

- **Task tracker: `br`** (beads, DB in `.beads/`) — all epics, tasks, issues and dependencies live there, not in markdown files. Create: `br create --type epic|task|bug`; link: `br dep add <child> <parent> --type parent-child`; check: `br epic status`, `br list --status open`, `br ready`. Keep statuses current as work progresses.
- **USE PARALLEL AGENTS WHEREVER POSSIBLE**: launch independent tasks/investigations/reviews as a single message with multiple Agent calls. Only in parallel where there are no shared files or state. EXCEPTION: when the session runs on the Kimi API (`kimi-for-coding` model), the provider gateway rate-limits subagent traffic hard (429s) — work WITHOUT subagents in that case: do investigations/reviews/implementations directly in the main session.
- Commits, code, and identifiers — in English. Commit attribution: `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- Principles: SOLID + KISS; code style — match the surroundings.
