# Gradle Wrapper Recovery Guide

## Problem Analysis
- `gradle-wrapper.jar` file is missing or corrupted
- `gradle wrapper` command not found in PATH
- Java is working correctly (/opt/homebrew/opt/openjdk@17/bin/java)

## Root Cause
The issue is a common Gradle setup problem where:
1. The wrapper files exist but are broken/incomplete
2. The `gradlew` script can't find the `gradle` command properly
3. Java itself is working fine

## Solution: Manual Gradle Execution

### Step 1: Build Project Directly
```bash
# Set environment variables
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"

# Build using direct Gradle command
/opt/homebrew/opt/openjdk@17/bin/java -cp "$(find . -name 'gradle-wrapper*.jar' 2>/dev/null)" org.gradle.tooling.api.GradleLauncher \
  --stacktrace \
  build \
  --no-daemon
```

### Step 2: Alternative: Use Gradle from Homebrew
```bash
# Install Gradle via Homebrew (if not already installed)
brew install gradle

# Then use gradle command directly
gradle build --stacktrace
```

### Step 3: Test the Build
```bash
# Test if build works
/opt/homebrew/opt/openjdk@17/bin/java -version

# Try building again
/opt/homebrew/opt/openjdk@17/bin/java -cp "$(find . -name 'gradle-wrapper*.jar' 2>/dev/null)" org.gradle.tooling.api.GradleLauncher \
  --stacktrace \
  build
```

## Quick Recovery Script
Save this as `recover-build.sh` and run:

```bash
#!/bin/bash
echo "Recovering Android project build..."

export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="/opt/homebrew/opt/openjdk@17/bin:$PATH"

echo "Building project directly..."
/opt/homebrew/opt/openjdk@17/bin/java -cp "$(find . -name 'gradle-wrapper*.jar' 2>/dev/null)" org.gradle.tooling.api.GradleLauncher \
  --stacktrace \
  build \
  --no-daemon

if [ $? -eq 0 ]; then
    echo "✅ Build successful!"
    echo "You can now install APK with:"
    echo "  ./gradlew installDebug"
else
    echo "❌ Build failed. Check error messages above."
fi
```

## Next Steps
1. Run the recovery script to test if the build works
2. If successful, continue with development
3. If not, consider using Android Studio's built-in Gradle or installing Gradle via Homebrew