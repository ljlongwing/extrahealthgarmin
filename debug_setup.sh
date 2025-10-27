#!/bin/bash

# Tefal2Garmin Debug Setup Script
# Настраивает отладку в эмуляторе Android

echo "=== Tefal2Garmin Debug Setup ==="
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

# Check if debug APK exists
if [ ! -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
    print_warning "Debug APK not found. Building..."
    ./gradlew assembleDebug --quiet
    if [ $? -ne 0 ]; then
        print_error "Failed to build debug APK"
        exit 1
    fi
    print_status "Debug APK built successfully"
fi

# Check if ADB is available
if ! command -v adb &> /dev/null; then
    print_error "ADB not found. Please install Android SDK platform tools."
    exit 1
fi

# Check for connected devices/emulators
DEVICES=$(adb devices | grep -v "List" | grep -v "^$" | wc -l)
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
if adb install -r app/build/outputs/apk/debug/app-debug.apk; then
    print_status "Debug APK installed successfully"
else
    print_error "Failed to install debug APK"
    exit 1
fi

# Push test file
echo
print_info "Copying test file to device..."
if adb push test_files/Goodvibes-Dmitry-20251026231908-data.csv /sdcard/Download/; then
    print_status "Test file copied to Downloads"
else
    print_warning "Failed to copy test file (may already exist)"
fi

# Trigger media scanner
echo
print_info "Updating media scanner..."
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/Goodvibes-Dmitry-20251026231908-data.csv > /dev/null 2>&1

echo
print_status "Debug setup completed!"
echo
print_info "Next steps:"
echo "1. Open Android Studio"
echo "2. Open this project: File → Open → tefal2garmin"
echo "3. Set breakpoint in MainActivity.kt line 197"
echo "4. Run → Debug 'app'"
echo "5. Select your emulator/device"
echo "6. Test file sharing from Downloads folder"
echo
print_info "Useful commands:"
echo "• View logs: adb logcat | grep -i tefal2garmin"
echo "• Reinstall: adb install -r app/build/outputs/apk/debug/app-debug.apk"
echo "• Clear data: adb shell pm clear com.example.tefal2garmin"
echo
print_info "See DEBUGGING_GUIDE.md for detailed instructions"