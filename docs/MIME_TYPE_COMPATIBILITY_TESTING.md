# MIME Type Compatibility Testing Guide

## Task 20.3: Test compatibility across devices and apps

This guide provides comprehensive testing instructions for verifying MIME type compatibility in the Tefal2Garmin app.

## APK Location
- **Debug APK**: `app/build/outputs/apk/debug/app-debug.apk`
- **Release APK**: `app/build/outputs/apk/release/app-release-unsigned.apk`

## Test Files Created
Located in `test_files/` directory:

1. **`valid_csv_standard.csv`** - Standard CSV with headers and multiple columns
2. **`valid_csv_minimal.csv`** - Minimal CSV with just date and weight columns
3. **`invalid_not_csv.txt`** - Plain text file (should be rejected)
4. **`invalid_too_many_commas.csv`** - CSV with too many columns (should be rejected)

## Testing Scenarios

### 1. File Manager Testing
Test sharing files from different file managers:

**Expected Results:**
- ✅ `valid_csv_standard.csv` → Should be accepted (HIGH confidence)
- ✅ `valid_csv_minimal.csv` → Should be accepted (HIGH confidence)
- ❌ `invalid_not_csv.txt` → Should be rejected with clear error message
- ❌ `invalid_too_many_commas.csv` → Should be rejected (too many columns)

### 2. Email Attachment Testing
Test receiving CSV files via email:

**Steps:**
1. Email the test CSV files to yourself
2. Open email on device
3. Long-press attachment → Share → Select Tefal2Garmin

**Expected Results:**
- Files should behave same as file manager testing
- MIME type may be `application/octet-stream` or `text/plain` (MEDIUM confidence)

### 3. Cloud Storage Testing
Test files from Google Drive, Dropbox, etc.:

**Steps:**
1. Upload test files to cloud storage
2. Open file in cloud app
3. Use share option to send to Tefal2Garmin

**Expected Results:**
- Should work with various MIME types that cloud services assign

### 4. Direct App Sharing Testing
Test sharing from apps that export CSV:

**Steps:**
1. Find apps that export CSV (spreadsheet apps, data export apps)
2. Export CSV and immediately share to Tefal2Garmin

### 5. Browser Download Testing
Test downloaded CSV files:

**Steps:**
1. Create CSV files on a web server or use data URLs
2. Download in browser
3. Open download notification → Share → Tefal2Garmin

### 6. MIME Type Edge Cases

Test these specific MIME types (if you can find apps that use them):

| MIME Type | Expected Behavior | Confidence |
|-----------|------------------|------------|
| `text/csv` | ✅ Accept | HIGH |
| `text/comma-separated-values` | ✅ Accept | HIGH |
| `application/csv` | ✅ Accept | HIGH |
| `application/x-csv` | ✅ Accept | HIGH |
| `text/plain` | ✅ Accept (with content inspection) | MEDIUM |
| `application/vnd.ms-excel` | ✅ Accept (may contain CSV) | MEDIUM |
| `application/octet-stream` | ✅ Accept (may contain CSV) | MEDIUM |
| `text/html` | ✅ Accept (text/* wildcard) | LOW |
| `image/png` | ❌ Reject | NONE |
| `application/pdf` | ❌ Reject | NONE |

## Testing Checklist

### Functional Tests
- [ ] App launches without crashes
- [ ] Initial screen shows correct instructions
- [ ] File sharing triggers processing
- [ ] Progress indicators work
- [ ] Success/error states display correctly
- [ ] Error messages are user-friendly

### MIME Type Tests
- [ ] Standard CSV files accepted
- [ ] Plain text with CSV content accepted
- [ ] Non-CSV files rejected appropriately
- [ ] Unsupported MIME types show clear errors
- [ ] Ambiguous MIME types trigger content inspection

### Compatibility Tests
- [ ] Works with Files app
- [ ] Works with Google Files
- [ ] Works with email attachments
- [ ] Works with cloud storage apps
- [ ] Works with browser downloads

### Error Handling Tests
- [ ] Null/empty intents handled gracefully
- [ ] Unsupported actions rejected
- [ ] Corrupted files handled
- [ ] Large files processed (if applicable)
- [ ] Network timeouts handled (if applicable)

## Manual Testing Steps

1. **Install APK**: `adb install app/build/outputs/apk/debug/app-debug.apk`

2. **Test Each File Source**:
   ```bash
   # Push test files to device
   adb push test_files /sdcard/Download/

   # Or use individual files
   adb push test_files/valid_csv_standard.csv /sdcard/Download/
   ```

3. **Test Sharing**:
   - Open file manager
   - Navigate to Downloads folder
   - Long-press test file
   - Select "Share"
   - Choose "Tefal2Garmin"

4. **Verify Results**:
   - Check app behavior matches expectations
   - Note any error messages
   - Verify processing completes successfully

5. **Log Analysis**:
   ```bash
   # Check device logs for MIME type handling
   adb logcat | grep -i "mimetype\|mime\|csv"
   ```

## Expected Log Output

When processing files, you should see logs like:
```
MIME Type Handling - Action: Intent validation, Type: text/csv, Confidence: HIGH, URI: content://...
Valid intent received with MIME type: text/csv
```

## Completion Criteria

Task 20.3 is complete when:
- [ ] All supported MIME types work correctly
- [ ] Unsupported types are rejected with clear messages
- [ ] Content inspection works for ambiguous types
- [ ] App handles various file sources reliably
- [ ] No crashes occur during testing
- [ ] User experience is smooth and intuitive

## Bug Reporting

If issues are found, document:
1. File type and source
2. MIME type reported
3. Expected vs actual behavior
4. Error messages shown
5. Device and Android version