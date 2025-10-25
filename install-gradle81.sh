#!/bin/bash

echo "Creating Gradle 8.1.0 installation from scratch..."

# Install Gradle 8.1.0 directly
echo "Step 1: Installing Gradle 8.1.0..."
brew install gradle@8.1.0

echo "Step 2: Setting up Gradle environment..."
export GRADLE_HOME=/usr/local/gradle-8.1.0
export PATH=$GRADLE_HOME/bin:$PATH

echo "Step 3: Verifying Gradle installation..."
/usr/local/gradle-8.1.0/bin/gradle -version

echo "Step 4: Generating project wrapper..."
# Generate new gradle wrapper
mkdir -p gradle/wrapper
/usr/local/gradle-8.1.0/bin/gradle wrapper

echo "Step 5: Setting permissions..."
chmod +x gradlew
chmod +x gradle/wrapper/gradle-wrapper.jar

echo "✅ Gradle 8.1.0 setup complete!"
echo ""
echo "Project is ready with Gradle 8.1.0"
echo ""
echo "You can now test the build:"
echo "  Build: ./gradlew build"
echo "  Install Debug: ./gradlew installDebug"