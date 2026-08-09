#!/usr/bin/env bash
#
# Builds the signed release artifacts, plus lint on the release variant:
#
#   * the Android App Bundle, for the Play upload;
#   * an APK, which is what a GitHub release attaches — an .aab cannot be installed by hand.
#
# Signing credentials come from either source, checked in this order by app/build.gradle.kts:
#
#   1. ANDROID_KEYSTORE_FILE / ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS /
#      ANDROID_KEY_PASSWORD — what CI sets, from GitHub secrets.
#   2. src/keystore.properties — gitignored, pointing at a keystore kept OUTSIDE the repo.
#
# Neither present means the bundle would be silently unsigned, so this refuses to build rather than
# producing an artifact that only fails later, at upload time.
#
#   ./scripts/release.sh                # bundle + apk + lint
#   ./scripts/release.sh --rerun-tasks  # extra flags are forwarded to Gradle
#
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
keystore_properties="$repo_root/src/keystore.properties"

env_signing_present=false
if [[ -n "${ANDROID_KEYSTORE_FILE:-}" && -n "${ANDROID_KEYSTORE_PASSWORD:-}" \
   && -n "${ANDROID_KEY_ALIAS:-}" && -n "${ANDROID_KEY_PASSWORD:-}" ]]; then
    env_signing_present=true
fi

if [[ "$env_signing_present" == false && ! -f "$keystore_properties" ]]; then
    cat >&2 <<EOF
error: no signing credentials, so the bundle could not be signed.

Set the ANDROID_KEYSTORE_FILE / ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS /
ANDROID_KEY_PASSWORD environment variables, or create $keystore_properties.

To create the upload key (keep the .jks outside the repo, and back it up):

  keytool -genkeypair -v \\
      -keystore "\$HOME/keys/nfc-spool-writer-upload.jks" \\
      -alias upload -keyalg RSA -keysize 2048 -validity 10000

Then copy src/keystore.properties.example to src/keystore.properties and fill it in.
EOF
    exit 1
fi

exec "$repo_root/scripts/gradle.sh" \
    :app:bundleRelease \
    :app:assembleRelease \
    :app:lintRelease \
    "$@"
