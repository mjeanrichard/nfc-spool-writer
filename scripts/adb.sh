#!/usr/bin/env bash
#
# Forwarder to the Android SDK's adb, which is not on PATH on this machine. Single place
# that knows where adb lives; every other script goes through here.
#
#   ./scripts/adb.sh devices -l
#   ./scripts/adb.sh shell pm list features
#   ./scripts/adb.sh logcat -d -b crash
#
# `--which` prints the resolved path instead of running adb, so a caller that needs to
# invoke it repeatedly can resolve it once.
#
set -euo pipefail

find_adb() {
    if command -v adb >/dev/null 2>&1; then
        command -v adb
        return
    fi
    local candidate
    for candidate in \
        "${ANDROID_HOME:-}/platform-tools/adb.exe" \
        "${ANDROID_SDK_ROOT:-}/platform-tools/adb.exe" \
        "${LOCALAPPDATA:-}/Android/Sdk/platform-tools/adb.exe" \
        "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe"
    do
        if [[ -n "$candidate" && -x "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return
        fi
    done
    echo "error: could not find adb. Set ANDROID_HOME or add platform-tools to PATH." >&2
    exit 1
}

adb="$(find_adb)"

if [[ "${1:-}" == "--which" ]]; then
    printf '%s\n' "$adb"
    exit 0
fi

exec "$adb" "$@"
