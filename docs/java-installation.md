# Java Installation Required

## Current Status
- System Java not found at standard locations
- Homebrew Java not installed
- Gradle wrapper cannot locate Java Runtime

## Solution Options

### Option 1: Install via Homebrew (Recommended)
```bash
brew install openjdk@17
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

### Option 2: Download and Install Manually
1. Download from: https://adoptium.net/temurin/releases/
2. Extract to `/Library/Java/JavaVirtualMachines/`
3. Set JAVA_HOME in shell profile

### Option 3: Use Android Studio Bundled JDK
- Install Android Studio
- Use embedded JDK from Android Studio installation

## Verification Commands
```bash
java -version
echo $JAVA_HOME
./gradlew build
```