# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Single-module Android app (Kotlin, Gradle wrapper). minSdk 21 / target & compileSdk 34.

```bash
./gradlew assembleDebug                # debug APK
./gradlew assembleRelease              # release APK (needs local app/release.keystore — gitignored)
./gradlew testDebugUnitTest            # all unit tests (JUnit4 + Mockito + Robolectric)
# Single class / single method:
./gradlew testDebugUnitTest --tests "com.example.tefal2garmin.utils.CsvValidatorTest"
./gradlew testDebugUnitTest --tests "com.example.tefal2garmin.utils.CsvLineParserTest.parseCsvLine*"
./gradlew connectedDebugAndroidTest    # instrumented ShareIntentTest — needs a device/emulator
./gradlew lintDebug
./debug_setup.sh                       # build + install debug APK, push a test CSV to the device
adb logcat -s MainActivity MainViewModel FileRepository   # runtime logs (per-class TAGs)
```

App version: `versionCode`/`versionName` in `app/build.gradle`.

## Architecture

Converts CSV weight exports from Tefal Goodvibes scales into Garmin Connect-compatible CSV. Fully offline — no network calls (the INTERNET permission is reserved for a future Garmin API). MVVM: `MainActivity` → `MainViewModel` (LiveData<`MainUiState`>) → `FileRepository` → stateless `utils/` classes. No DI framework — wiring is manual in `MainViewModelFactory.createInstance`. Single activity, no fragments; views via `findViewById`.

### Input → output contract (the core of the app)

- **Input** is the Goodvibes CSV with **Russian headers** (`Время измерения`, `Вес(кг)`, `ИМТ`, `Процент жира(%)`) and **Russian dates** (`окт. 25, 2025 09:51:52`). `CsvFieldMapper` owns all normalization: Russian month abbreviations → `YYYY-MM-DD`, comma decimal separators → dot; it also knows an alternative (older) Tefal header set.
- **Output** contract is `GarminCsvSchema`: first line `Body`, then header `Date,Weight,Fat,BMI` (note the order), one row per record. `generateHeader`/`validateRecord`/`formatRecord` are the single source of that format — change it there, not in string concatenation at call sites.

### Main flow: share intent → Garmin CSV

1. `MainActivity` is both launcher and share target — `AndroidManifest.xml` registers `ACTION_SEND`/`ACTION_SEND_MULTIPLE` filters for a ladder of CSV-ish MIME types (`text/csv`, `text/comma-separated-values`, `application/csv`, `application/x-csv`, `text/plain`, `application/vnd.ms-excel`, `text/*`).
2. `MimeTypeValidator.validateIntentForCsv` extracts the stream URI or the text extra; low-confidence types (`text/plain` etc.) must also pass `appearsToBeCsv` sniffing.
3. Storage permission is requested only below API 33; content URIs need nothing on Android 13+.
4. `FileRepository.processCsvFile` (Dispatchers.IO) runs the pipeline: `CsvValidator.validateEncoding` → `CsvReader.readCsvFile` → `validateDelimiter` → `validateHeaders` → `parseCsvLines` (`CsvLineParser` handles quoted fields; `detectAndMergeMultilineRecords` merges RFC-4180 multiline records) → per row `RecordProcessor.processRecord` (validates via `CsvValidator`, maps Tefal → Garmin fields via `CsvFieldMapper`) → `GarminCsvSchema.formatRecord` builds the output string.
5. On success `MainActivity` auto-saves to public Downloads as `garmin-import-<timestamp>.csv` and opens the Garmin Connect import URL after 1.5 s.

Two output paths exist: production builds the CSV string via `GarminCsvSchema` inside `FileRepository.prepareCsvData`; `GarminCsvGenerator` (file-writing API) is exercised mainly by unit/instrumented tests.

### Conventions

- All UI strings via `R.string` — English in `res/values/strings.xml`, Russian in `res/values-ru/`; keep both in sync.
- Logging: `android.util.Log` with a per-class `TAG` in the `companion object`.
- `utils/` classes are stateless namespaces — logic lives in `companion object` functions.
- `gradle.properties` carries Java 25 ↔ Kotlin 1.9 workarounds (Kotlin daemon disabled, jvm-target validation ignored), and the root `build.gradle` has both a `buildscript` classpath and the `plugins` DSL with different AGP versions — deliberate recovery state, don't "clean it up" without reading `docs/gradle-*.md` first.
- Changes to parsing, normalization, or the output format must come with a unit test — the suite mirrors the pipeline, one test class per util in `app/src/test/java/com/example/tefal2garmin/utils/`, plus `EndToEndIntegrationTest`. Sample fixtures (including a real Goodvibes export) live in `test_files/`.

## Tools: when to use what

- **fetch** — live content of a specific URL: web docs, changelogs, raw HTML, localhost. Not for library docs (that's context7) and not for "where to read" lookups (web search).
- **context7** — BEFORE writing code against a library/framework (AndroidX, Lifecycle, Coroutines, Robolectric, Gradle): exact signatures, configuration, migrations. Even if you "remember the API" — the model's data may be stale. Order: `resolve-library-id`, then `query-docs`; one query — one concept. Not for business logic or general Kotlin concepts.
- **serena** — semantic navigation and edits: `find_symbol`, `find_referencing_symbols`, `replace_symbol_body` instead of grep+sed wherever exact symbol boundaries matter. File overview — `get_symbols_overview` rather than reading the whole file; at the start of a coding task — `initial_instructions`.
- **claude-mem** — memory across sessions. Before design/debugging/review — `mem_search`: don't re-solve what's solved. Durable lessons — `save_memory` (after a duplicate search); current to-do lists — `work_state_write`.

## Process

- **USE PARALLEL AGENTS WHEREVER POSSIBLE**: launch independent tasks/investigations/reviews as a single message with multiple Agent calls. Only in parallel where there are no shared files or state.
- Commits, code, and identifiers — in English. Commit attribution: `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
- Principles: SOLID + KISS; code style — match the surroundings.
