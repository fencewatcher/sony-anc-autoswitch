#!/usr/bin/env bash
# Local APK build. Mirrors CI's toolchain so local and CI builds agree:
# CI uses temurin JDK 17, so we do too — this box's default JDK 26 is too new
# for Kotlin 1.9.22 and would produce different output than CI.
set -euo pipefail
export PATH="/home/linuxbrew/.linuxbrew/bin:$PATH"
export JAVA_HOME="/home/linuxbrew/.linuxbrew/opt/openjdk@17"
export ANDROID_SDK_ROOT="/home/linuxbrew/.linuxbrew/share/android-commandlinetools"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
cd "$(dirname "$0")"
exec ./gradlew --console=plain "$@"
