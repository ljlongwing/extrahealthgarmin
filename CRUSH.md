# CRUSH.md - Tefal to Garmin Sync Utility

## Build & Test Commands

```bash
# Build project (when Android project exists)
./gradlew build

# Run unit tests (when implemented)
./gradlew test

# Run single test class
./gradlew test --tests "com.example.parse.CsvParserTest"

# Run specific test method
./gradlew test --tests "com.example.parse.CsvParserTest.testMultilineRecords"

# Debug build
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Check code style (when implemented)
./gradlew ktlintCheck
```

## Code Style Guidelines

### Imports
- Use AndroidX libraries over legacy Android support libraries
- Group imports: standard Kotlin, Android, project, third-party
- No wildcard imports (`import *`)

### Naming
- Classes: PascalCase (e.g., `CsvParser`, `MainActivity`)
- Functions/variables: camelCase (e.g., `parseDate`, `fileName`)
- Constants: UPPER_SNAKE_CASE (e.g., `MIME_TYPE_CSV`)
- Private properties: prefix with underscore (e.g., `_parser`)

### File Structure
- Activities: `ui/MainActivity.kt`
- Utilities: `utils/CsvParser.kt`, `utils/DateUtils.kt`
- Data models: `data/WeightRecord.kt`
- Tests: `test/CsvParserTest.kt`

### Error Handling
- Use Result<T> sealed class for success/failure states
- Log errors with Log.e(TAG, message)
- Show user-friendly messages via Toast or Snackbar
- Never expose stack traces to users

### Date/Number Formatting
- Always output dates in YYYY-MM-DD format
- Use '.' as decimal separator for numbers
- Handle Russian month abbreviations: янв, фев, мар, апр, май, июн, июл, авг, сен, окт, ноя, дек

### CSV Processing
- Support UTF-8 encoding without BOM
- Handle multiline records in quotes
- Use comma as delimiter (not semicolon)
- Trim whitespace from fields

### Security
- Never store credentials in SharedPreferences
- Use Android Keystore for sensitive data
- Network requests must be HTTPS only

### UI Guidelines
- Follow Material Design 3 components
- Support both English and Russian via string resources
- Use ViewBinding for type-safe view access
- Show progress indicators for long operations

### Performance
- Process files in chunks to avoid memory issues
- Use Buffered streams for file I/O
- Target <2 seconds for 5MB files on mid-range devices