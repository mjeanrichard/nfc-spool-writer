#!/usr/bin/env bash
#
# Thin forwarder to the Gradle wrapper. The Gradle project root is `src/`, not the repo
# root, so every invocation needs `-p src`; this encodes that once and forwards whatever
# tasks and flags you pass.
#
# Works from any working directory:
#   ./scripts/gradle.sh :app:assembleDebug
#   ./scripts/gradle.sh :app:testDebugUnitTest --tests '*TagCodec*'
#   ./scripts/gradle.sh tasks
#
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

exec "$repo_root/src/gradlew" -p "$repo_root/src" --console=plain "$@"
