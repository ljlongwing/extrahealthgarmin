# Contributing to Extra Health Garmin

Thanks for your interest in contributing! This document covers the essentials.

## Project setup

1. Fork and clone the repository
2. Open in Android Studio (Ladybug or later) — JDK 17+ and Android SDK API 36 are expected
3. Build a debug APK to verify the toolchain:

```bash
./gradlew assembleDebug
```

4. Run the full unit-test suite (JUnit4 + Mockito + coroutines-test + MockWebServer):

```bash
./gradlew testDebugUnitTest
```

5. To run on a device: `./debug_setup.sh` (build + install + launch), logs via `adb logcat -s MainActivity MainViewModel SyncWorker`

## Pull request conventions

- **Language**: code, identifiers, commits and PR descriptions in English.
- **Tests**: changes to sync logic, aggregation, FIT encoding or auth **must** come with a unit test — the suite mirrors the packages under `app/src/test/java/com/zamotkin/extrahealthgarmin/` (`health/`, `garmin/`, `data/`, `ui/`).
- **Keep it small**: one logical change per PR; match the surrounding code style (per-class `TAG` logging, manual wiring, no DI framework).
- **No secrets ever**: never commit Garmin credentials, tokens, keystores or `local.properties`. Debug logging must stay redacted (`RedactingHttpLogger` pattern).
- **Lint**: `./gradlew lintDebug` should pass.

## Architecture orientation

Read [CLAUDE.md](CLAUDE.md) first — it documents the MVVM flow
(`MainActivity` → `MainViewModel` → `HealthSyncFacade` → `SyncEngine` → `GarminRepository` → `GarminUploadClient`),
the Health Connect changes-token sync model and the Garmin DI OAuth2 wire contract.

## Reporting issues

Use the GitHub issue templates (bug report / feature request) and include:
Android version, device model, app version, and — for sync issues — a redacted logcat (`RedactingHttpLogger` already strips secrets from debug logs).
