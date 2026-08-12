# AGENTS.md — NFC Spool Writer

Working agreements for any agent working in this repo. Read the version control and
build & deploy sections **before** typing a `git`, `gradlew` or `adb` command: `gradlew` and `adb`
need the wrappers in `scripts/`, and `git` is off-limits entirely.

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

When the user does explicitly ask for a commit, **the message is a single line** — a subject and
nothing else. No body paragraphs, no bullet list of what changed, however interesting the rationale
is. Rationale belongs in a code comment or the PR body.

## Build & deploy

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

### The commands

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

## Tests

Create unit tests to ensure 100% line and branch coverage of all Kotlin code. Use mutation testing to
ensure that the tests are effective. Use `scripts/check.sh` to run all tests and lint checks.

Build mocks for things that are not available during unit testing, such as Android classes that
require a device (such as NFC).

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

## Comments

Do NOT write comments that record historical facts. Comments should reflect the current state of the
code and explain why it is that way, not how it used to be. Only comment what is not obvious when
reading the code. If it is not obvious from the code what it does, refactor the code to make it more
self-explanatory.

## UI

Strive for an easy-to-use UI that lets the user complete their tasks with minimal effort. Follow the
Material Design guidelines for a consistent look and feel across the app. Ensure the app feels like a
native Android app, not a web app — build the UI with Compose.
