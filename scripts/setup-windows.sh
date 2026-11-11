#!/usr/bin/env bash
# Windows setup for the example app.
#
# The Android CMake failure on Windows ("manifest 'build.ninja' still dirty
# after 100 tries") comes from CMake 3.22.1 shipping ninja 1.10.2, which loops
# forever regenerating build.ninja for every native module. Point AGP at
# CMake 3.31+ (ninja 1.12) and the whole arm64/armv7 build works.
#
# Usage:  bash scripts/setup-windows.sh
set -euo pipefail

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CMAKE_VERSION="${HBOX_CMAKE_VERSION:-3.31.6}"
ANDROID_SDK="${ANDROID_HOME:-C:/Android/Sdk}"
SDKMANAGER="$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager.bat"
ANDROID_DIR="$PROJECT/apps/example/android"

echo "project: $PROJECT"
echo "sdk:     $ANDROID_SDK"

# 1. CMake 3.31+ (newer ninja) for the generated Android project.
if [ ! -x "$ANDROID_SDK/cmake/$CMAKE_VERSION/bin/ninja.exe" ]; then
  echo "installing cmake;$CMAKE_VERSION"
  yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true
  "$SDKMANAGER" --install "cmake;$CMAKE_VERSION"
fi

if [ -f "$ANDROID_DIR/local.properties" ]; then
  sed -i '/^cmake\.dir=/d' "$ANDROID_DIR/local.properties"
  printf 'cmake.dir=%s/cmake/%s\n' "$ANDROID_SDK" "$CMAKE_VERSION" >> "$ANDROID_DIR/local.properties"
  echo "cmake.dir -> $ANDROID_SDK/cmake/$CMAKE_VERSION"
else
  echo "apps/example/android missing - run: cd apps/example && npx expo prebuild -p android"
fi

# 2. Ship phone ABIs only; x86/x86_64 emulator libs double the APK size.
if [ -f "$ANDROID_DIR/gradle.properties" ]; then
  sed -i 's/^reactNativeArchitectures=.*/reactNativeArchitectures=armeabi-v7a,arm64-v8a/' \
    "$ANDROID_DIR/gradle.properties"
  echo "reactNativeArchitectures -> armeabi-v7a,arm64-v8a"
fi

pnpm install
echo "done"
