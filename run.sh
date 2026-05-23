#!/usr/bin/env bash
# FairShare build/install/run helper.
#
# Usage:
#   ./run.sh [debug|release] [--no-install] [--no-launch] [--clean] [--device <serial>]
#
# Examples:
#   ./run.sh                       # builds, installs, launches debug
#   ./run.sh release               # builds, installs, launches release
#   ./run.sh debug --clean         # clean build then run
#   ./run.sh debug --no-launch     # build + install, do not start the app
#   ./run.sh debug --device emulator-5554
set -euo pipefail

APP_ID="com.fairshare"
MAIN_ACTIVITY=".MainActivity"
VARIANT="debug"
DO_INSTALL=1
DO_LAUNCH=1
DO_CLEAN=0
DEVICE_ARG=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        debug|release) VARIANT="$1"; shift ;;
        --no-install)  DO_INSTALL=0;  shift ;;
        --no-launch)   DO_LAUNCH=0;   shift ;;
        --clean)       DO_CLEAN=1;    shift ;;
        --device)      DEVICE_ARG="-s $2"; shift 2 ;;
        -h|--help)
            sed -n '2,15p' "$0"; exit 0 ;;
        *) echo "Unknown arg: $1" >&2; exit 1 ;;
    esac
done

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR"

# ---- locate gradle ----------------------------------------------------------
find_cached_gradle() {
    local dist
    dist=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle 2>/dev/null | head -n1)
    [[ -z "$dist" ]] && dist=$(ls -dt "$HOME"/.gradle/wrapper/dists/gradle-8.*-bin/*/gradle-8.*/bin/gradle 2>/dev/null | head -n1)
    echo "$dist"
}

if [[ -x "./gradlew" ]]; then
    GRADLE="./gradlew"
elif command -v gradle >/dev/null 2>&1; then
    echo "▶ No gradle wrapper found, generating one (gradle wrapper)…"
    gradle wrapper --gradle-version 8.9 >/dev/null
    GRADLE="./gradlew"
else
    CACHED_GRADLE=$(find_cached_gradle)
    if [[ -n "$CACHED_GRADLE" && -x "$CACHED_GRADLE" ]]; then
        echo "▶ Using cached Gradle: $CACHED_GRADLE"
        echo "▶ Generating wrapper for the project…"
        "$CACHED_GRADLE" wrapper --gradle-version 8.9 >/dev/null
        GRADLE="./gradlew"
    else
        echo "❌ Neither ./gradlew, 'gradle' on PATH, nor a cached Gradle distribution found." >&2
        echo "   Install Gradle (brew install gradle) or open the project once in Android Studio." >&2
        exit 1
    fi
fi

# ---- locate adb -------------------------------------------------------------
if command -v adb >/dev/null 2>&1; then
    ADB="adb"
elif [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/platform-tools/adb" ]]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
elif [[ -n "${ANDROID_SDK_ROOT:-}" && -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]]; then
    ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
else
    echo "❌ adb not found in PATH/ANDROID_HOME/ANDROID_SDK_ROOT." >&2
    exit 1
fi

# ---- variant config ---------------------------------------------------------
case "$VARIANT" in
    debug)
        ASSEMBLE_TASK="assembleDebug"
        INSTALL_TASK="installDebug"
        APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
        ;;
    release)
        ASSEMBLE_TASK="assembleRelease"
        INSTALL_TASK="installRelease"
        APK_PATH="app/build/outputs/apk/release/app-release.apk"
        ;;
esac

echo "▶ Variant : $VARIANT"

# ---- clean ------------------------------------------------------------------
if [[ $DO_CLEAN -eq 1 ]]; then
    echo "▶ Clean…"
    "$GRADLE" clean
fi

# ---- build ------------------------------------------------------------------
echo "▶ Build : $ASSEMBLE_TASK"
"$GRADLE" "$ASSEMBLE_TASK"

if [[ ! -f "$APK_PATH" ]]; then
    echo "❌ APK introuvable: $APK_PATH" >&2
    exit 1
fi
echo "✓ APK: $APK_PATH ($(du -h "$APK_PATH" | cut -f1))"

# ---- install ----------------------------------------------------------------
if [[ $DO_INSTALL -eq 1 ]]; then
    # ensure a device is connected
    if ! $ADB $DEVICE_ARG get-state >/dev/null 2>&1; then
        echo "❌ Aucun appareil/émulateur connecté. Branche un device ou démarre un émulateur." >&2
        exit 1
    fi
    echo "▶ Install via gradle ($INSTALL_TASK)…"
    "$GRADLE" "$INSTALL_TASK"
fi

# ---- launch -----------------------------------------------------------------
if [[ $DO_LAUNCH -eq 1 ]]; then
    echo "▶ Launch $APP_ID/$MAIN_ACTIVITY"
    $ADB $DEVICE_ARG shell am start -n "$APP_ID/$MAIN_ACTIVITY" >/dev/null
    echo "✓ App lancée."
    # follow logcat for this app
    echo "▶ Logcat (Ctrl+C pour quitter) :"
    PID=""
    for _ in 1 2 3 4 5; do
        PID=$($ADB $DEVICE_ARG shell pidof "$APP_ID" 2>/dev/null | tr -d '\r')
        [[ -n "$PID" ]] && break
        sleep 0.3
    done
    if [[ -n "$PID" ]]; then
        exec $ADB $DEVICE_ARG logcat --pid="$PID"
    else
        exec $ADB $DEVICE_ARG logcat "$APP_ID:V" "*:S"
    fi
fi
