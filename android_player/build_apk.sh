#!/usr/bin/env bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$PROJECT_DIR"

echo "==> Building FHS Player APK..."

# Auto-detect ANDROID_JAR
if [ -n "$ANDROID_JAR" ] && [ -f "$ANDROID_JAR" ]; then
    echo "Using ANDROID_JAR=$ANDROID_JAR"
elif [ -n "$ANDROID_HOME" ] && [ -f "$ANDROID_HOME/platforms/android-34/android.jar" ]; then
    ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
elif [ -n "$ANDROID_SDK_ROOT" ] && [ -f "$ANDROID_SDK_ROOT/platforms/android-34/android.jar" ]; then
    ANDROID_JAR="$ANDROID_SDK_ROOT/platforms/android-34/android.jar"
elif [ -f "/data/data/com.termux/files/usr/share/java/android.jar" ]; then
    ANDROID_JAR="/data/data/com.termux/files/usr/share/java/android.jar"
elif [ -n "$PREFIX" ] && [ -f "$PREFIX/share/java/android.jar" ]; then
    ANDROID_JAR="$PREFIX/share/java/android.jar"
else
    echo "Error: android.jar not found. Please set ANDROID_JAR environment variable."
    exit 1
fi

# Auto-detect FRAMEWORK_RES or fallback to android.jar
if [ -z "$FRAMEWORK_RES" ] || [ ! -f "$FRAMEWORK_RES" ]; then
    if [ -f "/system/framework/framework-res.apk" ]; then
        FRAMEWORK_RES="/system/framework/framework-res.apk"
    else
        FRAMEWORK_RES="$ANDROID_JAR"
    fi
fi

rm -rf bin/* gen/*
mkdir -p bin gen

echo "==> [1/6] Generating R.java with aapt..."
aapt package -f -m -J gen/ -M AndroidManifest.xml -S res/ -I "$FRAMEWORK_RES"

echo "==> [2/6] Compiling Java sources..."
javac -source 1.8 -target 1.8 \
  -cp "$ANDROID_JAR" \
  -d bin/ \
  gen/com/haptic/fhsplayer/R.java \
  src/com/haptic/fhsplayer/MainActivity.java

echo "==> [3/6] Dexing bytecode with dx/d8..."
if command -v dx >/dev/null 2>&1; then
    dx --dex --output=bin/classes.dex bin/
elif command -v d8 >/dev/null 2>&1; then
    d8 --output bin/classes.zip bin/com/haptic/fhsplayer/*.class
    unzip -p bin/classes.zip classes.dex > bin/classes.dex
    rm -f bin/classes.zip
else
    echo "Error: Neither dx nor d8 found."
    exit 1
fi

echo "==> [4/6] Packaging APK with assets & resources..."
aapt package -f \
  -M AndroidManifest.xml \
  -S res/ \
  -A assets/ \
  -I "$FRAMEWORK_RES" \
  -F bin/app.unsigned.apk

echo "==> [5/6] Adding classes.dex to APK..."
(cd bin && aapt add app.unsigned.apk classes.dex)

echo "==> [6/6] Signing APK with apksigner..."
if [ ! -f "debug.keystore" ]; then
    keytool -genkey -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi

apksigner sign \
  --ks debug.keystore \
  --ks-pass pass:android \
  --ks-key-alias androiddebugkey \
  --key-pass pass:android \
  --out bin/FHSPlayer.apk \
  bin/app.unsigned.apk

echo "==> Verifying APK signature..."
apksigner verify bin/FHSPlayer.apk

echo "✅ Build Successful: $PROJECT_DIR/bin/FHSPlayer.apk"
ls -lh bin/FHSPlayer.apk
