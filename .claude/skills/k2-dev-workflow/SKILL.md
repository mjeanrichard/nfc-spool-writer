---
name: dev-workflow
description: How to build, test, lint, install on the phone, read logs, and handle version control for this project. Use whenever running Gradle, running unit tests or lint, deploying/sideloading the app to a device, launching it, checking connected devices, reading logcat, verifying an NFC change on real hardware, or about to run any git command (including deleting, moving or renaming tracked files). Read this BEFORE typing any gradlew, adb or git command — gradlew and adb need wrappers that live in scripts/, and git is off-limits entirely.
---

# Software Engeneering skills for NFC Spool Writer

## Comments
Do NOT create comments in code that refelect historlcal facts. Comments should reflect the current state of the code and explain why it is that way, not how it used to be. Only comment what is not obvious when reading the code. If it is not obvious from the code what it does, refactor the code to make it more self-explanatory.

## Tests
Create UnitTests to ensure a 100% line and branch coverage of all Kotlin code. Use mutation testing to ensure that the tests are effective. Use the `scripts/check.sh` script to run all tests and lint checks.

Build mocks for things that are not available during unit testing, such as Android classes that require a device (such as NFC).

## UI
Strife for an easy to use UI that allows the user to complete their tasks with minimal effort. Use the Material Design guidelines to ensure a consistent look and feel across the app.
Ensure that the app feels like a native Android app, and not a web app. Use the Compose UI framework to build the UI.

## Version control

**Never run git. The user handles all version control themselves.** This is absolute: no `commit`,
no `add`, no `rm`, no `mv`, no branch or stash operations — and no read-only ones either, so no
`status`, `diff`, `log` or `show`. There is no exception for "just checking" and none for a command
the user approved once before; approval does not carry to the next call.

When a change needs a file deleted, moved or renamed, **do it on the filesystem** — `rm`, `mv`, or
the Write/Edit tools — and leave the result in the working tree. Do not reach for `git rm` or
`git mv` because the file is tracked; the user stages and commits, and an unstaged deletion is
exactly what they want to see.

State plainly in your summary which files you deleted or moved, so the user knows what to stage.

# NFC Spool Writer — build & deploy workflow

**Always go through `scripts/`. Never invoke `gradlew` or `adb` directly.** The wrappers encode two
facts about this machine and repo that a direct command gets wrong, and they are allowlisted in
`.claude/settings.local.json`, so they run without a permission prompt while direct calls interrupt
the user.

These are bash scripts — run them with the **Bash** tool, not PowerShell.

**Type the path exactly as the table below shows it: `scripts/check.sh`, bare.** The scripts are
executable and carry their own shebang, so no interpreter prefix is needed. Writing
`bash scripts/check.sh` or `sh scripts/check.sh` does the same thing but misses the allowlist entry,
which turns every invocation into a permission prompt for the user. Do not prefix with `cd` either —
the Bash tool already starts in the repo root, and the scripts resolve their own location regardless.

## The commands

| Task | Command |
|---|---|
| Build the debug APK | `scripts/gradle.sh :app:assembleDebug` |
| Unit tests | `scripts/gradle.sh :app:testDebugUnitTest` |
| One test class | `scripts/gradle.sh :app:testDebugUnitTest --tests '*TagCodec*'` |
| **Full verification pass** | `scripts/check.sh` |
| **Build + install + launch on phone** | `scripts/install.sh` |
| Is a phone connected? | `scripts/install.sh --devices` |
| Anything else adb | `scripts/adb.sh <args>` |

`scripts/check.sh` runs `assembleDebug` + `testDebugUnitTest` + `lintDebug`. That is the bar for
calling a TODO.md item done — note it includes **lint**, which the two Gradle tasks alone do not.

## Verifying on hardware

Unit tests cannot reach the NFC path — `MifareClassicSession` wraps a final Android class tied to a
live `Tag`, and there are no instrumented or Compose UI tests in this project by design. Anything
touching tags therefore needs a real device:

1. `scripts/install.sh` — builds, installs, launches.
2. Exercise the change; for raw byte-level work use the **debug tag harness**, reachable from
   Settings, which shows trailer bits and hex dumps the product screens deliberately hide.
3. `scripts/adb.sh logcat -s TagHarness` — the harness mirrors every result to logcat, and that is
   the only way to capture a full diagnostic dump, since the on-screen copy is wider than the screen.
4. `scripts/adb.sh logcat -d -b crash` for a crash.

Outstanding manual checks live in `TODO.md` §2. When a check needs a tag this project
does not have, record it as **untested** — never as assumed-passing.
