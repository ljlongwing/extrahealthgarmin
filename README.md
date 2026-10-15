# Extra Health Garmin

[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

An Android app that syncs body-composition weigh-ins from **Health Connect** into **Garmin Connect** — automatically, in the background, with no account data ever stored beyond encrypted session tokens.

## 📱 Features

- **🔄 Health Connect source**: reads weight, body fat, bone mass, lean body mass, body water, basal metabolic rate and height measurements from any scale or app that writes into Health Connect
- **🧩 Smart aggregation**: measurements taken at the same time are combined into one weigh-in (weight + fat + BMI, with the height record cached for BMI)
- **⬆️ Garmin auto-upload**: new weigh-ins are encoded into a FIT file (official Garmin FIT SDK) and uploaded straight into your Garmin Connect account — no manual import, no CSV files
- **🔐 One-time login**: Garmin sign-in (email + password + MFA verification code, garth-style DI OAuth2) — only Keystore-encrypted tokens are stored, never your credentials
- **⏱️ Background sync**: a WorkManager job re-syncs roughly every 3 hours when a network connection exists; a manual **Sync now** button is always available
- **✅ Dedup journal**: record ids of uploaded weigh-ins are journaled on-device, so the same weigh-in is never uploaded twice — even across reinstalls of the sync flow or re-reads after a token expiry
- **🔁 Offline retry**: failed uploads stay retriable and ride along with the next sync pass (exponential backoff on network problems)
- **🌍 Multilingual**: full English and Russian UI

## 🚀 Quick Start

### Installation
1. Download `app-release.apk` from the releases
2. Enable "Install from unknown sources" in Android settings
3. Install the APK

### Usage
1. **Grant permissions** — on first launch tap the Health Connect status line to install/open Health Connect and grant the read permissions
2. **Connect Garmin** — sign in once with your Garmin email, password and (if enabled) MFA code; tokens are refreshed automatically afterwards
3. **Sync** — tap **Sync now**, or just let the ~3-hourly background sync do it: every new weigh-in lands in Garmin Connect on its own

## ⚠️ Limitations

- **Unofficial API** — the upload uses Garmin's DI endpoint (the wire contract of [garth](https://github.com/matin/garth) / python-garminconnect). Garmin can change or block it at any time.
- **garmin.com only** — Garmin Connect China (garmin.cn) accounts are not supported.
- **Background reads** — on some providers the Health Connect background-read permission cannot be granted; the periodic sync then quietly does nothing and manual foreground sync keeps working.

## 🔒 Privacy & Security

- The app **only reads** Health Connect data — it never adds, changes or deletes anything there.
- **Nothing is sent anywhere except Garmin Connect.** No analytics, no telemetry, no third parties.
- No measurement values are stored by the app — only record identifiers (for dedup) and sync bookkeeping (a changes token and a timestamp), in app-private storage.
- The Garmin email and password exist only in memory during the login dialog and are never written to disk. The only persisted credentials are DI OAuth2 tokens, AES-encrypted with an Android Keystore key.
- **Debug builds** log the HTTP exchanges of the Garmin traffic with every secret redacted (`Authorization` headers, password, MFA code, tokens → `<redacted>`); **release builds** log none of it.

## 🏗️ Architecture

```
MVVM, single activity
├── ui/MainViewModel            — LiveData state for the home screen
├── MainActivity                — Health Connect status, login dialog, sync UI
├── health/
│   ├── HealthConnectSource     — record reads + changes tokens
│   ├── BodyMeasurementAggregator — correlates measurements into weigh-ins
│   ├── SyncEngine              — one sync pass: fetch → cache height → aggregate → dedup → upload
│   ├── SyncWorker              — WorkManager ~3h periodic pass (same engine)
│   └── SyncStateStore          — sync state JSON in filesDir
├── data/GarminRepository       — upload orchestration gateway
└── garmin/                     — DI OAuth2 auth + token store (Keystore), FIT builder,
                                  upload client, dedup journal, redacting logger
```

State is deliberately a single JSON file in `filesDir` (no Room, no DataStore): the Health Connect changes token, the last-sync instant, the processed-record-id dedup set and the cached height.

## 🔧 Development

### Prerequisites
- Android Studio (Ladybug or later recommended)
- JDK 17+
- Android SDK API 36

### Build Commands
```bash
# Debug build
./gradlew assembleDebug

# Release build (needs local app/release.keystore — gitignored)
./gradlew assembleRelease

# Run tests (152 unit tests)
./gradlew testDebugUnitTest

# Lint check
./gradlew lintDebug

# Build + install + launch on a connected device
./debug_setup.sh

# Runtime logs
adb logcat -s MainActivity MainViewModel SyncWorker
```

### Debug Garmin Auth Spike (debug builds only)

Debug builds contain a hidden trigger that runs one live Garmin DI OAuth2 login and reports the result to logcat (release builds have no such trigger). Put `GARMINSPIKE_EMAIL` / `GARMINSPIKE_PASSWORD` / `GARMINSPIKE_MFA_CODE` into `~/.gradle/gradle.properties`, build, then **long-press the status text** on the main screen and watch `adb logcat -s MainActivity GarminAuth GarminHttp`. Remove the properties when done.

## 📚 Documentation

- [Release Notes](RELEASE_NOTES.md) — version history
- [docs/](docs/) — historical design and setup notes

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable (`app/src/test/java/com/zamotkin/extrahealthgarmin/`)
5. Submit a pull request

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
