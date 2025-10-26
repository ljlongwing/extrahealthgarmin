# Common Garmin Connect Import Errors and Troubleshooting

## Overview

This document outlines common errors users encounter when importing weight and health data into Garmin Connect, specifically focusing on CSV file imports from the Tefal-to-Garmin Sync utility.

## Common Import Error Messages

### 1. "An error occurred with your upload. Please try again."
**Likely Causes:**
- Temporary server connectivity issues
- File format incompatibility
- Corrupted CSV data
- Session timeout during upload

**Solutions:**
- Wait a few minutes and retry the upload
- Verify your internet connection is stable
- Check that the CSV file hasn't been modified after export
- Try uploading a smaller batch of data (split into multiple files)

### 2. "The file format is not supported"
**Likely Causes:**
- File saved as .xlsx instead of .csv
- CSV file opened and resaved in Excel (changes formatting)
- Incorrect delimiter (semicolon instead of comma)
- Missing required columns

**Solutions:**
- Ensure file extension is .csv
- Use a text editor (Notepad, VS Code) to verify file content
- Check that headers are: Date,Weight,BMI,Fat
- Verify all numeric fields use '.' as decimal separator

### 3. "Required fields are missing"
**Likely Causes:**
- Empty rows in the CSV file
- Missing header row
- Inconsistent column names

**Solutions:**
- Verify the first row contains exactly: Date,Weight,BMI,Fat
- Remove any completely empty rows
- Check for extra spaces in header names
- Ensure each data row has values for all required fields

### 4. "Date format is invalid"
**Likely Causes:**
- Dates not in YYYY-MM-DD format
- Month names in Russian instead of numbers
- Inconsistent date formats across rows

**Solutions:**
- All dates must be in YYYY-MM-DD format (e.g., 2024-01-15)
- Do not use month abbreviations (янв, фев, etc.)
- Check for leading zeros in months and days (01, 02, etc.)

### 5. "Invalid number format"
**Likely Causes:**
- Using comma as decimal separator (European format)
- Empty weight/BMI/fat values
- Text characters in numeric fields

**Solutions:**
- All numeric fields must use '.' as decimal separator
- Use '.' instead of ',' for decimals (e.g., 155.5, not 155,5)
- Ensure no empty values in numeric fields
- Remove any text or special characters from numeric columns

## Best Practices for CSV File Preparation

### ⚠️ WARNING: Do NOT Open CSV Files in Excel
Excel automatically changes CSV formatting which can cause import failures:
- Changes decimal separators
- Alters date formats
- Adds extra formatting
- May convert numbers to scientific notation

**Alternative Tools:**
- Use Notepad (Windows)
- Use TextEdit (Mac)
- Use VS Code or similar code editors
- Use dedicated CSV viewers

### File Preparation Checklist

Before uploading to Garmin Connect:

- [ ] File saved as .csv (not .xlsx)
- [ ] File contains headers: Date,Weight,BMI,Fat
- [ ] All dates are in YYYY-MM-DD format
- [ ] All numbers use '.' as decimal separator
- [ ] No empty rows between data rows
- [ ] No extra spaces in headers or data
- [ ] File size under 5MB for reliable uploads

### Upload Process Tips

1. **Use a stable internet connection**
   - WiFi is generally more reliable than mobile data
   - Avoid uploading during peak usage times

2. **Upload in smaller batches**
   - If you have more than 100 records, consider splitting into multiple files
   - Garmin may have limits on large file uploads

3. **Verify data before upload**
   - Open the CSV file in a text editor to verify format
   - Check the first few rows match your expectations

4. **Retry strategy**
   - If upload fails, wait 2-3 minutes before retrying
   - Clear browser cache if multiple failures occur
   - Try using a different browser (Chrome, Firefox, Safari)

## Known Garmin Connect Issues

### TCX File Upload May Still Work
Sometimes CSV uploads fail but TCX (Training Center) files succeed:
- Our app generates CSV format as required
- If you consistently get CSV errors, try manually converting to TCX
- This is a known Garmin Connect limitation

### Batch Size Recommendations
- **Optimal**: 50-100 records per file
- **Maximum reliable**: 200 records per file
- **Avoid**: More than 500 records in single file

### Browser Compatibility
- **Most reliable**: Chrome, Firefox
- **Sometimes works**: Safari, Edge
- **Not recommended**: Internet Explorer (older versions)

## Contact Support

If you continue to experience issues:
1. Check the Garmin Connect status page for known outages
2. Try the Garmin Support forums
3. Contact Garmin Customer Support with:
   - Error message you received
   - File format you're using
   - Browser and device information
   - Steps you've already tried

## Quick Reference

| Error | Quick Fix |
|--------|-----------|
| "Upload error" | Wait 2-3 min, retry |
| "Not supported" | Check file is .csv, not opened in Excel |
| "Missing fields" | Verify headers: Date,Weight,BMI,Fat |
| "Invalid date" | Use YYYY-MM-DD format |
| "Invalid number" | Use '.' decimal, not ',' |