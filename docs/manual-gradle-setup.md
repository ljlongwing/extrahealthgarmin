# Manual Gradle Wrapper Setup

## Issue
- macOS security restrictions block curl/wget for SSL downloads
- Python urllib has SSL certificate verification issues
- Need manual download approach

## Solution 1: Safari Download
1. Open Safari browser
2. Navigate to: https://services.gradle.org/distributions/gradle-8.7-bin.zip
3. Download file to Downloads folder
4. Move file to project root
5. Run extraction commands

## Solution 2: Use Android Studio
1. Open Android Studio
2. Create any temporary project (File > New > Empty Activity)
3. Android Studio will download and setup proper Gradle wrapper
4. Copy wrapper files from temporary project to main project

## Solution 3: Transfer from Another Machine
If you have access to another computer:
1. Download on that machine
2. Transfer via USB drive or network share
3. Copy to project directory

## Current Status
- Java 17 installed at `/opt/homebrew/opt/openjdk@17/`
- Environment variables: `JAVA_HOME` and `PATH` set
- Only need fresh Gradle wrapper files

## Manual Extraction Commands
Once you have `gradle-8.7-bin.zip` in project root:

```bash
# Extract the archive
unzip -q gradle-8.7-bin.zip

# Copy wrapper files
cp gradle-8.7/gradle/wrapper/* gradle/wrapper/

# Set executable permissions
chmod +x gradlew
```

## Verification
```bash
# Test Java version
java -version

# Test Gradle
./gradlew --version
```