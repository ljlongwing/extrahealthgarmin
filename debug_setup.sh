#!/bin/bash

# Extra Health Garmin Debug Setup Script
# Builds, installs and launches the debug APK on a connected device/emulator.

APP_ID="com.zamotkin.extrahealthgarmin"
APK="app/build/outputs/apk/debug/app-debug.apk"

echo "=== Extra Health Garmin Debug Setup ==="
echo

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

print_status() {
    echo -e "${GREEN}✓${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
}

print_error() {
    echo -e "${RED}✗${NC} $1"
}

print_info() {
    echo -e "${BLUE}ℹ${NC} $1"
}

# Check if we're in project root
if [ ! -f "app/build.gradle" ]; then
    print_error "Run this script from the project root directory"
    exit 1
fi

# Build debug APK (always fresh, so the installed build matches the sources)
echo
print_info "Building debug APK..."
if ./gradlew assembleDebug --quiet; then
    print_status "Debug APK built successfully"
else
    print_error "Failed to build debug APK"
    exit 1
fi

# Check if ADB is available
if ! command -v adb &> /dev/null; then
    print_error "ADB not found. Please install Android SDK platform tools."
    exit 1
fi

# Check for connected devices/emulators
DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | wc -l | tr -d ' ')
if [ "$DEVICES" -eq 0 ]; then
    print_error "No Android devices/emulators found."
    echo
    print_info "Please:"
    echo "1. Start an Android emulator in Android Studio"
    echo "2. Or connect a physical Android device with USB debugging enabled"
    echo
    print_info "To check connected devices: adb devices"
    exit 1
fi

print_status "Found $DEVICES device(s)/emulator(s)"

# Install debug APK
echo
print_info "Installing debug APK..."
if adb install -r "$APK"; then
    print_status "Debug APK installed successfully"
else
    print_error "Failed to install debug APK"
    exit 1
fi

# Launch the app
echo
print_info "Launching Extra Health Garmin..."
if adb shell am start -n "${APP_ID}/${APP_ID}.MainActivity" > /dev/null 2>&1; then
    print_status "App launched"
else
    print_warning "Could not launch the app automatically — start it from the launcher"
fi

echo
print_status "Debug setup completed!"
echo
print_info "Useful commands:"
echo "• View logs: adb logcat -s MainActivity MainViewModel SyncWorker"
echo "• Reinstall: adb install -r $APK"
echo "• Clear data: adb shell pm clear $APP_ID"
