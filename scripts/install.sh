#!/usr/bin/env bash
#
# Builds the debug APK, installs it on the connected device, and launches it.
#
# Requires a phone with USB debugging enabled and authorized — check with:
#   ./scripts/install.sh --devices
#
# The debug build is signed with the local debug keystore, which is fine for sideloading
# during development. For a signed release build use ./scripts/release.sh.
#
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
adb="$("$script_dir/adb.sh" --which)"

if [[ "${1:-}" == "--devices" ]]; then
    exec "$adb" devices -l
fi

# A device in state "unauthorized" means the RSA prompt on the phone was not accepted yet.
if ! "$adb" devices | grep -qE '\sdevice$'; then
    echo "error: no authorized device connected. Current state:" >&2
    "$adb" devices -l >&2
    echo "" >&2
    echo "Enable USB debugging on the phone and accept the 'Allow USB debugging?' prompt." >&2
    exit 1
fi

# Build, then install via adb rather than Gradle's :installDebug. Gradle's installer goes through
# ddmlib, which times out and fails opaquely if the phone is slow or the screen has locked; `adb
# install` is more forgiving and reports errors that actually name the problem.
"$script_dir/gradle.sh" :app:assembleDebug "$@"

apk="$(cd -- "$script_dir/.." && pwd)/src/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$apk" ]]; then
    echo "error: expected APK not found at $apk" >&2
    exit 1
fi

"$adb" install -r "$apk"

# The debug variant carries the ".debug" applicationIdSuffix so it installs next to a store build,
# but the activity class still lives in the unsuffixed namespace — hence the fully qualified name
# rather than the "/.MainActivity" shorthand, which adb would resolve against the package id.
"$adb" shell am start -n ch.jeanrichard.nfcspoolwriter.debug/ch.jeanrichard.nfcspoolwriter.MainActivity
