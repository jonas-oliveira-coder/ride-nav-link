$ErrorActionPreference = "Stop"

$SCRIPT_DIR = $PSScriptRoot
$PROJECT_DIR = Join-Path $SCRIPT_DIR "ride_nav_link"

# Automatically detect Android SDK on Windows if present in user environment
$SDK_DIR = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$BUILD_TOOLS_DIR = Get-ChildItem (Join-Path $SDK_DIR "build-tools") -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
$PLATFORM_JAR = Get-ChildItem (Join-Path $SDK_DIR "platforms") -Filter "android.jar" -Recurse -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName

# Tool paths (prioritizes environment variables or standard install paths)
$BUILD_TOOLS = if ($BUILD_TOOLS_DIR) { $BUILD_TOOLS_DIR } else { "C:\Android\Sdk\build-tools\34.0.0" }
$PLATFORM = if ($PLATFORM_JAR) { $PLATFORM_JAR } else { "C:\Android\Sdk\platforms\android-34\android.jar" }
$KOTLINC = if ($env:KOTLINC) { $env:KOTLINC } else { "C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\bin\kotlinc.bat" }
$KOTLIN_LIB = if ($env:KOTLIN_LIB) { $env:KOTLIN_LIB } else { "C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc\lib\kotlin-stdlib.jar" }
$KEYSTORE = "$env:USERPROFILE\.android\debug.keystore"

if (-not (Test-Path $env:JAVA_HOME)) {
    $STUDIO_JBR = "C:\Program Files\Android\Android Studio\jbr"
    if (Test-Path $STUDIO_JBR) { $env:JAVA_HOME = $STUDIO_JBR }
}
$env:PATH = "$env:JAVA_HOME\bin;$BUILD_TOOLS;$env:PATH"

Set-Location $PROJECT_DIR
if (Test-Path "build") {
    Remove-Item -Recurse -Force "build"
}
New-Item -ItemType Directory -Path "build\gen", "build\obj", "build\dex", "build\apk" -Force | Out-Null

Write-Host "1. Compiling resources with aapt2..."
& "$BUILD_TOOLS\aapt2.exe" compile --dir app/src/main/res -o build/res.zip
& "$BUILD_TOOLS\aapt2.exe" link -I "$PLATFORM" `
    --manifest app/src/main/AndroidManifest.xml `
    -o build/apk/app.apk `
    -I "$KOTLIN_LIB" `
    --java build/gen `
    build/res.zip --auto-add-overlay

Write-Host "2. Compiling Kotlin with kotlinc..."
$sources = (Get-ChildItem -Path "app\src\main\java", "build\gen" -Recurse | Where-Object { $_.Extension -in ".kt", ".java" }).FullName
& $KOTLINC -cp "$PLATFORM;$KOTLIN_LIB" -d build/obj $sources
if ($LASTEXITCODE -ne 0) { throw "Kotlin compilation failed" }

Write-Host "3. Generating classes.dex with d8..."
& "$env:JAVA_HOME\bin\jar.exe" cf build/app.jar -C build/obj .
if ($LASTEXITCODE -ne 0) { throw "Failed to package classes into app.jar" }
& "$BUILD_TOOLS\d8.bat" --lib "$PLATFORM" --output build/dex build/app.jar "$KOTLIN_LIB"
if ($LASTEXITCODE -ne 0) { throw "d8 dexing failed" }

Write-Host "4. Packaging APK..."
Set-Location "build\dex"
& "$BUILD_TOOLS\aapt.exe" add ..\apk\app.apk classes.dex
Set-Location $PROJECT_DIR

Write-Host "5. Aligning and Signing APK..."
& "$BUILD_TOOLS\zipalign.exe" -f -p 4 build/apk/app.apk build/RideNavLink.apk
& "$BUILD_TOOLS\apksigner.bat" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android build/RideNavLink.apk
if ($LASTEXITCODE -ne 0) { throw "APK signing failed" }

Write-Host "SUCCESS! APK generated at: $PROJECT_DIR\build\RideNavLink.apk"
