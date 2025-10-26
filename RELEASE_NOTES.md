# Tefal2Garmin Sync - Release Notes

## Version 1.0.0 (MVP Release)

### Overview
Tefal2Garmin Sync is an Android utility app that converts CSV weight data exported from Tefal Goodvibes scales into Garmin Connect-compatible format for seamless import.

### Key Features
- **CSV File Processing**: Accepts CSV files from Tefal Goodvibes app via Android share system
- **Data Validation**: Validates file format, encoding (UTF-8), and column structure
- **Multiline Support**: Handles complex CSV files with multiline records
- **Russian Date Parsing**: Supports Russian month abbreviations in date fields
- **Garmin Format Conversion**: Transforms data to Garmin Connect's Fitbit CSV import format
- **File Saving**: Saves converted files to device Downloads folder
- **Garmin Integration**: Direct link to Garmin Connect import page
- **Multilingual UI**: Full English and Russian language support
- **MIME Type Compatibility**: Supports various CSV MIME types from different apps

### Technical Specifications
- **Minimum Android Version**: API 21 (Android 5.0)
- **Target Android Version**: API 34 (Android 14)
- **Architecture**: MVVM with Repository pattern
- **Languages**: Kotlin
- **Build**: Minified and optimized with R8/ProGuard
- **Size**: ~2.2 MB (release APK)

### Installation
1. Download the APK file (`app-release.apk`)
2. Enable "Install from unknown sources" in Android settings
3. Install the APK on your Android device
4. Grant storage permissions when prompted

### Usage Instructions

#### For English Users:
1. Export weight data from Tefal Goodvibes app as CSV
2. Share the CSV file and select "Tefal2Garmin Sync"
3. Wait for processing to complete
4. Tap "Save to device" to download the Garmin-compatible file
5. Tap "Open Garmin Connect" to import the data

#### Для русскоязычных пользователей:
1. Экспортируйте данные веса из приложения Tefal Goodvibes в формате CSV
2. Поделитесь CSV файлом и выберите "Tefal2Garmin Sync"
3. Дождитесь завершения обработки
4. Нажмите "Сохранить на устройство" для загрузки файла в формате Garmin
5. Нажмите "Открыть Garmin Connect" для импорта данных

### Supported File Formats
- **Input**: CSV files from Tefal Goodvibes with columns: Date, Weight, BMI, Fat
- **Output**: Garmin Connect Fitbit CSV format with headers: Body, Date, Weight, BMI, Fat
- **Encoding**: UTF-8 (required for input, guaranteed for output)
- **Delimiter**: Comma (,) - standard CSV format

### Known Limitations
- Requires manual export from Tefal Goodvibes app
- No automatic upload to Garmin (opens import page)
- Single file processing (batch processing not supported)
- No cloud storage integration

### Troubleshooting

#### Common Issues:
- **"Unsupported file type"**: Ensure file is valid CSV from Tefal Goodvibes
- **"Invalid encoding"**: Save CSV file as UTF-8 without BOM
- **"Missing headers"**: Check that CSV contains required columns
- **"File save failed"**: Ensure sufficient storage space and permissions

#### Advanced Troubleshooting:
- Check Android logs for detailed error information
- Verify CSV file structure with a text editor
- Ensure Tefal Goodvibes export is in standard format

### Privacy & Security
- No data is transmitted over internet
- All processing happens locally on device
- Files are temporarily stored in app cache only
- No personal data collection or tracking

### Future Enhancements (Roadmap)
- Direct Garmin Connect API integration
- Batch file processing
- Cloud storage support
- Automatic export scheduling
- Additional language support

### Support
For issues or questions:
- Check the troubleshooting guide in the app
- Review the complete documentation
- Report bugs via GitHub issues

### License
This application is provided as-is for personal use.

---

**Release Date**: October 2025
**Build**: v1.0.0
**Platform**: Android 5.0+