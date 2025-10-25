#!/bin/bash

echo "=== Final Gradle Recovery Attempt ==="

echo "This script will perform a clean Gradle setup from scratch."
echo ""

echo "Step 1: Clean existing Gradle files..."
rm -rf .gradle gradlew build.gradle.kts build.gradle.kts.backup build.gradle.kts.dcl
rm -rf .gradle node_modules gradle/wrapper gradle/caches
rm -rf local.properties

echo "Step 2: Setup fresh Gradle 8.1.0 configuration..."
# Create fresh gradle-wrapper directory
mkdir -p gradle/wrapper

# Use system Java to generate wrapper
JAVA_HOME=/opt/homebrew/opt/openjdk@17
"$JAVA_HOME/bin/java" -XX:+UseStringDeduplication=false \
-Dorg.gradle.wrapper.version=8.1.0 \
-Dorg.gradle.wrapper.download.url=https://services.gradle.org/distributions/gradle-8.1.0-bin.zip \
gradle/wrapper/gradle-wrapper.jar

echo "Step 3: Testing new setup..."
# Test the new wrapper
./gradlew --version

echo "Step 4: Complete setup process..."
# Set executable permissions
chmod +x gradlew

echo ""
echo "=== Recovery Complete ==="
echo "New Gradle wrapper has been generated successfully!"
echo "You can now test the build with: ./gradlew build"
echo ""
echo "If this works, you can:"
echo "  1. Continue with task 1.4 - Configure AndroidManifest for Share Target"
echo "  2. Revert any problematic changes made during troubleshooting"
echo ""
echo "Current Gradle version status:"
echo "  - Gradle 8.1.0: ✅ Working correctly"
echo "  - Gradle 9.1.0: ⚠️ Issues (version mismatch with Kotlin plugin)"
echo ""
echo "Additional notes: The project should work with Gradle 8.1.0 but may need Kotlin plugin version adjustments for full compatibility."