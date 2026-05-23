#!/usr/bin/env bash
# Grabs a screenshot from the connected Android device and saves it as a PNG.
#
# Usage:
#   ./screenshot.sh                                  # → screenshots/screen-YYYYMMDD-HHMMSS.png
#   ./screenshot.sh app/docs/bug-receipts/bug-01-merged-lines/screen.png
#   ./screenshot.sh --device emulator-5554 out.png
#   ./screenshot.sh --open out.png                   # open the file once captured
set -euo pipefail

DEVICE_ARG=""
OPEN_AFTER=0
OUTPUT=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --device) DEVICE_ARG="-s $2"; shift 2 ;;
        --open)   OPEN_AFTER=1; shift ;;
        -h|--help)
            sed -n '2,9p' "$0"; exit 0 ;;
        *) OUTPUT="$1"; shift ;;
    esac
done

# Default destination
if [[ -z "$OUTPUT" ]]; then
    mkdir -p screenshots
    OUTPUT="screenshots/screen-$(date +%Y%m%d-%H%M%S).png"
fi

# Ensure parent directory exists
mkdir -p "$(dirname "$OUTPUT")"

# Locate adb (same logic as run.sh)
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

if ! $ADB $DEVICE_ARG get-state >/dev/null 2>&1; then
    echo "❌ No device connected. Run '$ADB devices' to check." >&2
    exit 1
fi

# `adb exec-out screencap -p` streams the PNG bytes directly to stdout — no
# temp file on the device required, and binary-safe on macOS/Linux.
$ADB $DEVICE_ARG exec-out screencap -p > "$OUTPUT"

if [[ ! -s "$OUTPUT" ]]; then
    echo "❌ Capture failed (output is empty)." >&2
    rm -f "$OUTPUT"
    exit 1
fi

SIZE=$(du -h "$OUTPUT" | cut -f1)
echo "✓ Capture: $OUTPUT ($SIZE)"

if [[ $OPEN_AFTER -eq 1 ]]; then
    if [[ "$OSTYPE" == "darwin"* ]]; then open "$OUTPUT"; else xdg-open "$OUTPUT"; fi
fi
