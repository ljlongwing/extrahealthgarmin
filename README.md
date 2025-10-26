# Tefal2Garmin Sync

[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

An Android utility app that converts CSV weight data exported from Tefal Goodvibes scales into Garmin Connect-compatible format for seamless import.

## 📱 Features

- **🔄 CSV Conversion**: Transform Tefal Goodvibes CSV exports to Garmin Connect format
- **✅ Data Validation**: Comprehensive validation of file format, encoding, and structure
- **🌍 Multilingual**: Full English and Russian language support
- **📁 File Sharing**: Native Android share integration for easy file import
- **💾 Smart Saving**: Automatic saving to Downloads folder
- **🔗 Garmin Integration**: Direct links to Garmin Connect import page
- **🛡️ Privacy First**: All processing happens locally, no data transmission

## 🚀 Quick Start

### Installation
1. Download `app-release.apk` from the releases
2. Enable "Install from unknown sources" in Android settings
3. Install the APK and grant storage permissions

### Usage
1. **Export** weight data from Tefal Goodvibes as CSV
2. **Share** the file and select "Tefal2Garmin Sync"
3. **Wait** for processing to complete
4. **Save** the converted file to your device
5. **Import** into Garmin Connect

## 📋 Requirements

- **Android**: 5.0 (API 21) or higher
- **Storage**: ~10MB free space
- **Permissions**: Storage access for file processing

## 🏗️ Architecture

```
MVVM Architecture
├── View (MainActivity) - UI layer
├── ViewModel (MainViewModel) - Business logic
├── Repository (FileRepository) - Data access
└── Utils - CSV processing utilities
```

## 🛠️ Technical Details

### Supported Input Format
```csv
Date,Weight,BMI,Fat
2024-01-15,75.5,24.2,18.5
2024-01-16,75.2,24.1,18.3
```

### Output Format (Garmin Compatible)
```csv
Body
Date,Weight,BMI,Fat
2024-01-15,75.5,24.2,18.5
2024-01-16,75.2,24.1,18.3
```

### MIME Type Support
- `text/csv` ✅
- `text/comma-separated-values` ✅
- `application/csv` ✅
- `text/plain` ✅ (with CSV detection)
- `application/vnd.ms-excel` ✅
- `application/octet-stream` ✅

## 🔧 Development

### Prerequisites
- Android Studio Arctic Fox or later
- JDK 11 or higher
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

### Project Structure
```
app/
├── src/main/
│   ├── java/com/example/tefal2garmin/
│   │   ├── MainActivity.kt          # Main UI
│   │   ├── data/                    # Data layer
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