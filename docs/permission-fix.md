# Permission Fix for Gradle Build on macOS

## Problem
- `permission denied` when running script without sudo
- `chmod +x` not sufficient for executable scripts on macOS

## Solutions

### Solution 1: Use Gradle Directly
```bash
# Build without wrapper script
/opt/homebrew/opt/openjdk@17/bin/java -cp "$(find . -name 'gradle-wrapper*.jar' 2>/dev/null)" org.gradle.tooling.api.GradleLauncher \
  --stacktrace \
  build \
  --no-daemon
```

### Solution 2: Fix Directory Permissions
```bash
# Give user ownership
sudo chown -R $(whoami):staff .

# Fix execute permissions
sudo chmod -R +x .

# Then run script normally
./recover-build.sh
```

### Solution 3: Build in Temporary Directory
```bash
# Build in /tmp where permissions are open
cd /tmp
/opt/homebrew/opt/openjdk@17/bin/java -cp /Users/dz/WebstormProjects/tefal2garmin/gradle/wrapper/gradle-wrapper.jar \
  org.gradle.tooling.api.GradleLauncher \
  --stacktrace \
  build \
  --no-daemon
```

### Solution 4: Use Android Studio Gradle
```bash
# Use Android Studio's bundled Gradle
/Applications/Android\ Studio.app/Contents/gradle/gradle \
  --stacktrace \
  build \
  --no-daemon
```

## Current Status
- Java 17 installed correctly
- Gradle tooling API available
- Issue is macOS permission model
- Project files are correctly structured

## Recommended Fix
Use Solution 1 (direct Gradle execution) for immediate testing:
```bash
/opt/homebrew/opt/openjdk@17/bin/java -cp "$(find . -name 'gradle-wrapper*.jar' 2>/dev/null)" org.gradle.tooling.api.GradleLauncher \
  --stacktrace \
  build \
  --no-daemon
```