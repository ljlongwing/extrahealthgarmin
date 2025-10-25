# Java Installation Issues and Resolution

## Problem
- Gradle wrapper jar file is corrupted or missing
- Direct execution of Java works but gradlew cannot find the wrapper classes

## Resolution Steps

### 1. Download Fresh Gradle Wrapper
```bash
# Create temporary directory
cd /tmp
curl -L https://services.gradle.org/distributions/gradle-8.1-bin.zip -o gradle.zip
unzip -o gradle-8.1.zip
```

### 2. Copy Wrapper Files
```bash
# Copy the fresh wrapper to project
cp gradle-8.1/gradle/wrapper/gradle-wrapper.jar /Users/dz/WebstormProjects/tefal2garmin/gradle/wrapper/
cp gradle-8.1/gradle/wrapper/gradle-wrapper.properties /Users/dz/WebstormProjects/tefal2garmin/gradle/wrapper/
```

### 3. Set Permissions
```bash
chmod +x /Users/dz/WebstormProjects/tefal2garmin/gradlew
```

### 4. Test Build
```bash
cd /Users/dz/WebstormProjects/tefal2garmin
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"
./gradlew build
```

## Current Status
- ✅ Java 17 installed via Homebrew at /opt/homebrew/opt/openjdk@17/
- ✅ Direct Java execution works
- ❌ Gradle wrapper corrupted/not working
- 🔄 Need to replace wrapper files

## Alternative Solution
Use Android Studio's Gradle wrapper or build directly with installed Java:
```bash
/opt/homebrew/opt/openjdk@17/bin/java -cp "$(find . -name "gradle-wrapper.jar" | head -1)" org.gradle.wrapper.GradleWrapperMain build
```