#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/ride_nav_link"

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BUILD_TOOLS="${BUILD_TOOLS:-$(find "$SDK_DIR/build-tools" -maxdepth 1 -mindepth 1 | sort -V | tail -n 1)}"
PLATFORM="${PLATFORM:-$(find "$SDK_DIR/platforms" -name "android.jar" | sort -V | tail -n 1)}"
KOTLINC="${KOTLINC:-kotlinc}"
KOTLIN_LIB="${KOTLIN_LIB:-$(dirname "$(which kotlinc 2>/dev/null || echo ".")")/../lib/kotlin-stdlib.jar}"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"

cd "$PROJECT_DIR"
rm -rf build
mkdir -p build/gen build/obj build/dex build/apk

echo "1. Compilando Recursos..."
$BUILD_TOOLS/aapt2 compile --dir app/src/main/res -o build/res.zip
$BUILD_TOOLS/aapt2 link -I "$PLATFORM" \
    --manifest app/src/main/AndroidManifest.xml \
    -o build/apk/app.apk \
    -I "$KOTLIN_LIB" \
    --java build/gen \
    build/res.zip --auto-add-overlay

echo "2. Compilando Kotlin..."
SOURCES=$(find app/src/main/java build/gen -name "*.kt" -o -name "*.java")
$KOTLINC -cp "$PLATFORM:$KOTLIN_LIB" -d build/obj $SOURCES

echo "3. Dexing..."
CLASS_FILES=$(find build/obj -name "*.class")
$BUILD_TOOLS/d8 --lib "$PLATFORM" --output build/dex $CLASS_FILES "$KOTLIN_LIB"

echo "4. Empacotando APK..."
cd build/dex
$BUILD_TOOLS/aapt add ../apk/app.apk classes.dex
cd "$PROJECT_DIR"

echo "5. Alinhando e Assinando..."
$BUILD_TOOLS/zipalign -f -p 4 build/apk/app.apk build/RideNavLink.apk
$BUILD_TOOLS/apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android build/RideNavLink.apk

echo "Pronto! $PROJECT_DIR/build/RideNavLink.apk"
