#!/usr/bin/env bash
#
# The full local verification pass: debug build, unit tests, lint. This is what should be
# green before a phase in STORE_PLAN.md or an item in TODO.md is called done.
#
# Extra arguments are forwarded to Gradle, e.g. `./scripts/check.sh --rerun-tasks`.
#
set -euo pipefail

exec "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/gradle.sh" \
    :app:assembleDebug \
    :app:testDebugUnitTest \
    :app:lintDebug \
    "$@"
