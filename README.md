# Tefal2Garmin Sync

[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

An Android utility app that converts CSV weight data exported from Tefal Goodvibes scales and uploads the weigh-ins straight into Garmin Connect — with the classic converted-CSV import kept as an automatic fallback.

## 📱 Features

- **🔄 CSV Conversion**: Transform Tefal Goodvibes CSV exports to Garmin Connect format
- **⬆️ Auto-Upload**: New weigh-ins (weight, percent fat, BMI) are uploaded to Garmin Connect automatically after conversion — no manual import step
- **🔐 One-Time Login**: Garmin Connect login with MFA support; only Keystore-encrypted tokens are stored, never your password
- **🛟 Resilient Fallback**: Any upload failure produces a CSV with only the unuploaded records and opens the Garmin web import, so nothing is ever lost
- **✅ Data Validation**: Comprehensive validation of file format, encoding, and structure
- **🌍 Multilingual**: Full English and Russian language support
- **📁 File Sharing**: Native Android share integration for easy file import
- **💾 Smart Saving**: Fallback CSV is saved automatically to the Downloads folder
- **🔗 Garmin Integration**: Direct upload via the Garmin Connect API, with direct links to the import page as fallback
- **🛡️ Privacy First**: CSV processing happens locally; the only network traffic is the Garmin upload itself, with all secrets redacted from logs

## 🚀 Quick Start

### Installation
1. Download `app-release.apk` from the releases
2. Enable "Install from unknown sources" in Android settings
3. Install the APK and grant storage permissions

### Usage
1. **Export** weight data from Tefal Goodvibes as CSV
2. **Share** the file and select "Tefal2Garmin Sync"
3. **Log in** (first time only) — if no valid Garmin token is stored yet, the app asks for your Garmin Connect email, password and, if enabled on the account, an MFA verification code. You can also reach this dialog any time via the "Connect Garmin" action on the main screen
4. **Wait** for the upload — the records are converted and sent to Garmin Connect automatically; nothing appears in Downloads on full success
5. **Fallback** — if the upload fails (or only some records are accepted), a CSV containing just the unuploaded records is saved to Downloads as `garmin-import-<timestamp>.csv` and the Garmin Connect import page opens automatically, as it always did

## 📋 Requirements

- **Android**: 5.0 (API 21) or higher
- **Storage**: ~10MB free space
- **Permissions**: Storage access for file processing
- **Network**: Internet connection for the Garmin auto-upload (CSV conversion and the fallback flow work fully offline)

## ⬆️ Garmin Auto-Upload

After a successful conversion the app no longer stops at the CSV: it uploads the new weigh-ins directly into Garmin Connect, using the unofficial DI OAuth2 API (wire contract of [python-garminconnect](https://github.com/cyberjunky/python-garminconnect) 0.3.x). No browser or WebView is involved in the happy path.

### How it works

1. **Convert** — the shared Tefal CSV is parsed, validated and mapped exactly as before (see **Technical Details** below).
2. **Dedup** — a local journal of already-sent timestamps (kept in app-private storage) filters out records that were uploaded earlier, so re-sharing the same CSV uploads nothing new.
3. **Login** (only when needed) — if no valid token is stored, the "Connect Garmin" login dialog opens automatically; after a successful login the upload proceeds on its own.
4. **Upload** — all new records are encoded into a single FIT file (weight, percent fat and BMI per record, built with the official Garmin FIT SDK) and uploaded as one multipart request to the Garmin Connect upload endpoint.
5. **Journal or fallback** — every record Garmin confirms is added to the journal; anything that failed is handed to the fallback CSV below.

### Connecting your Garmin account

- **One-time login dialog**: Garmin email + password, then an MFA verification code if your account uses two-factor authentication (the code delivery can be re-triggered from the dialog).
- The app stores **only Garmin's DI OAuth2 tokens** (`di_token`, `di_refresh_token`, `di_client_id`) — **your email and password are never saved anywhere**.
- Tokens are encrypted with an AES key that lives in the Android Keystore and persisted in the app's private storage; the key never leaves it.
- The DI refresh token lives **about 30 days** (observed; Garmin does not document it). The access token is refreshed automatically based on its expiry, so daily use needs no re-login — when the refresh token eventually expires, you simply log in again.

### Fallback: the residual CSV

Uploads can fail — you are offline, the token was revoked, Garmin rate-limits the request (HTTP 429), a server error persists after retries, or Garmin rejects individual records of the batch. In every failure case:

- records already uploaded are **not** duplicated (the journal protects them);
- a fallback CSV containing **only the records that were not uploaded** is saved to Downloads as `garmin-import-<timestamp>.csv`, in exactly the same Garmin format as before (`Body` / `Date,Weight,Fat,BMI`);
- the Garmin Connect web import page opens automatically, so you can import that file by hand.

On a fully successful upload **no CSV file is written at all**. There is no retry queue — a failed record ends up either in the residual CSV (import it via the web page) or you can re-share the original Tefal export to retry only what is still missing.

## ⚠️ Limitations

- **Device timezone assumed** — Tefal exports timestamps in local time with no timezone information. The app converts them to UTC using the device's timezone at conversion time; weigh-ins recorded in a different timezone than the phone's will be stored with a shifted time in Garmin Connect.
- **Unofficial API** — the upload uses Garmin's unofficial DI endpoint. Garmin can change or block it at any time (the older garth-based flows stopped working in March 2026). If that happens, the app still works end-to-end via the CSV fallback.
- **garmin.com only** — Garmin Connect China (garmin.cn) accounts are not supported.

## 🔒 Privacy & Security

- All CSV parsing, validation and conversion happen locally on the device. The only network traffic is the upload to Garmin Connect itself.
- The Garmin email and password exist only in memory during the login dialog and are never written to disk. The only persisted state is the DI token triple, AES-encrypted with an Android Keystore key.
- **Debug builds** log the full HTTP exchanges of the Garmin auth/upload traffic (logcat tag `GarminHttp`), but every secret value — `Authorization` headers, password, MFA code, `refresh_token`, service tickets, access tokens — is replaced with `<redacted>` before it reaches a log line.
- **Release builds** log nothing of the Garmin wire traffic; the redacting logger is inactive even if wiring changes.
- The debug spike trigger (below) logs token lengths and client ids only — never token values.

## 🏗️ Architecture

```
MVVM Architecture
├── View (MainActivity) - UI layer
├── ViewModel (MainViewModel) - Business logic
├── Repository (FileRepository) - CSV conversion
├── Repository (GarminRepository) - Upload orchestration
├── Garmin (garmin/) - DI OAuth2 auth, token store, FIT upload, dedup journal, redacting logger
└── Utils - CSV processing utilities
```

## 🛠️ Technical Details

### Input Example (Tefal Goodvibes Export)
```csv
Время измерения,Вес(кг),ИМТ,Пульс(bpm),Процент жира(%),Масса тела без жира(кг),Подкожный жир(%),Висцеральный жир,Вода в организме(%),Скелетные мышцы(%),Мышечная масса(кг),Костная масса(кг),Белок(%),Величина основного обмена(kcal),Метаболический возраст
"окт. 25, 2025 09:51:52",79.65,24.60,88.00,21.90,62.20,19.40,7.00,56.40,44.50,59.10,3.11,17.80,1713.00,52.00
"окт. 21, 2025 08:20:53",80.25,24.80,94.00,21.70,62.80,19.20,7.00,56.50,44.60,59.70,3.14,17.90,1727.00,52.00
```

The date column contains Russian dates (`окт. 25, 2025 09:51:52`) and is quoted because it includes a comma (RFC-4180 multiline-safe parsing).

### Output Example (Garmin Compatible)
```csv
Body
Date,Weight,Fat,BMI
2025-10-25,79.65,21.90,24.60
2025-10-21,80.25,21.70,24.80
```

The fallback CSV produced by a failed or partial auto-upload uses exactly this format and contains only the records that were not uploaded (see **Garmin Auto-Upload** above).

### MIME Type Support
- `text/csv` ✅
- `text/comma-separated-values` ✅
- `application/csv` ✅
- `application/x-csv` ✅
- `text/plain` ✅ (with CSV detection)
- `application/vnd.ms-excel` ✅

## 🔧 Development

### Prerequisites
- Android Studio Arctic Fox or later
- JDK 17 or higher
- Android SDK API 34

### Build Commands
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run tests
./gradlew testDebugUnitTest

# Lint check
./gradlew lintDebug
```

### Debug Garmin Auth Spike (debug builds only)

Debug builds contain a hidden trigger that runs one live Garmin DI OAuth2 login against the real endpoint and reports the result — a spike to check whether Garmin accepts the app's HTTP stack (Cloudflare/TLS-fingerprint question). Release builds have no such trigger and carry no credentials.

1. Put spike credentials into `~/.gradle/gradle.properties` (keeps them out of shell history and the repo):
   ```properties
   GARMINSPIKE_EMAIL=you@example.com
   GARMINSPIKE_PASSWORD=...
   GARMINSPIKE_MFA_CODE=...
   ```
   `GARMINSPIKE_MFA_CODE` is optional and only needed to exercise the MFA resume path. All three fields are blank by default, so a normal build (debug or release) embeds nothing.
2. Build and install the debug APK: `./gradlew assembleDebug`
3. Trigger: **long-press the instructions text** on the main screen (the status text works too, once visible). Results go to logcat only — nothing is shown in the UI.
4. Watch the run:
   ```bash
   adb logcat -s MainActivity GarminAuth GarminHttp
   ```
   `MainActivity` logs the spike stages (token lengths and client ids only), `GarminAuth` the login/MFA/refresh flow, `GarminHttp` the redacted HTTP exchanges.

⚠️ **Remove the `GARMINSPIKE_*` lines from `~/.gradle/gradle.properties` when you are done** — leftover credentials in that file end up compiled into every subsequent debug build.

### Project Structure
```
app/
├── src/main/
│   ├── java/com/example/tefal2garmin/
│   │   ├── MainActivity.kt          # Main UI
│   │   ├── data/                    # Data layer (CSV + upload repositories)
│   │   ├── garmin/                  # Garmin DI auth, token store, FIT upload, journal
│   │   ├── ui/                      # UI components
│   │   └── utils/                   # CSV processing
│   ├── res/                         # Resources
│   └── AndroidManifest.xml
├── build.gradle                     # App configuration
└── proguard-rules.pro              # Obfuscation rules
```

## 📚 Documentation

- [Release Notes](RELEASE_NOTES.md) - Version history and changes
- [Localization Guide](LOCALIZATION_README.md) - UI translation details
- [MIME Type Testing](MIME_TYPE_COMPATIBILITY_TESTING.md) - Compatibility testing
- [Troubleshooting](docs/complete-troubleshooting-guide.md) - Common issues and solutions

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Tefal Goodvibes for the weight scale data export
- Garmin Connect for the fitness data platform
- Android Open Source Project for the development platform

## 📞 Support

- **Issues**: [GitHub Issues](https://github.com/your-repo/issues)
- **Discussions**: [GitHub Discussions](https://github.com/your-repo/discussions)
- **Documentation**: See troubleshooting guide in-app

---

**Made with ❤️ for fitness enthusiasts**