#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/ride_nav_link"

SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BUILD_TOOLS="${BUILD_TOOLS:-$(find "$SDK_DIR/build-tools" -maxdepth 1 -mindepth 1 2>/dev/null | sort -V | tail -n 1)}"
PLATFORM="${PLATFORM:-$(find "$SDK_DIR/platforms" -name "android.jar" 2>/dev/null | sort -V | tail -n 1)}"
KOTLINC="${KOTLINC:-kotlinc}"
KOTLIN_LIB="${KOTLIN_LIB:-$(dirname "$(which kotlinc 2>/dev/null || echo ".")")/../lib/kotlin-stdlib.jar}"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
STORE_PASS="${STORE_PASS:-android}"
KEY_PASS="${KEY_PASS:-android}"

echo "Using BUILD_TOOLS: $BUILD_TOOLS"
echo "Using PLATFORM: $PLATFORM"
echo "Using KOTLINC: $(which $KOTLINC || echo "$KOTLINC")"
echo "Using KOTLIN_LIB: $KOTLIN_LIB"

cd "$PROJECT_DIR"
rm -rf build
mkdir -p build/gen build/obj build/dex build/apk

echo "1. Compiling resources..."
$BUILD_TOOLS/aapt2 compile --dir app/src/main/res -o build/res.zip
$BUILD_TOOLS/aapt2 link -I "$PLATFORM" \
    --manifest app/src/main/AndroidManifest.xml \
    -o build/apk/app.apk \
    -I "$KOTLIN_LIB" \
    --java build/gen \
    build/res.zip --auto-add-overlay

echo "2. Compiling Kotlin..."
SOURCES=$(find app/src/main/java build/gen -name "*.kt" -o -name "*.java")
$KOTLINC -cp "$PLATFORM:$KOTLIN_LIB" -d build/obj $SOURCES

echo "3. Dexing..."
JAR_BIN="${JAVA_HOME:+$JAVA_HOME/bin/jar}"
if [ -n "$JAR_BIN" ] && [ -x "$JAR_BIN" ]; then
    "$JAR_BIN" cf build/app.jar -C build/obj .
else
    jar cf build/app.jar -C build/obj .
fi
"$BUILD_TOOLS/d8" --lib "$PLATFORM" --output build/dex build/app.jar "$KOTLIN_LIB"

echo "4. Packaging APK..."
cd build/dex
if [ -x "$BUILD_TOOLS/aapt" ]; then
    "$BUILD_TOOLS/aapt" add ../apk/app.apk classes.dex
else
    zip -j ../apk/app.apk classes.dex
fi
cd "$PROJECT_DIR"

echo "5. Aligning and signing..."
"$BUILD_TOOLS/zipalign" -f -p 4 build/apk/app.apk build/RideNavLink.apk
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:$KEY_PASS" \
    build/RideNavLink.apk

echo "Done! $PROJECT_DIR/build/RideNavLink.apk"
