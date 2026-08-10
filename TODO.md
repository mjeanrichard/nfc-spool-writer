# TODO

Every open item for this project, in one place. Requirements live in
[REQUIREMENTS.md](REQUIREMENTS.md), architecture and settled decisions in [DESIGN.md](DESIGN.md), and
the tag format in [TAG_FORMAT_SPEC.md](TAG_FORMAT_SPEC.md) — none of those track outstanding work.

## 1. Validation and polish

- [ ] **Error-handling pass across network/NFC/mapping failure paths.** Every failure state
      is typed and unit tested, but none has been provoked on real hardware. Provoke them deliberately:
      stop Spoolman mid-list, point at a wrong port, pull the tag away mid-write, present a foreign-key
      tag, feed a spool whose material has no catalog match. Confirm each produces a message that names
      the problem and a next step.
      *Worth doing carefully:* a cleartext-HTTP block previously surfaced as a plausible-looking
      "could not reach the server", which was true but useless — a failure path can look convincing
      while being wrong about the cause.
- [ ] **Logging.** A thin wrapper for diagnosing field issues after the fact. Only the development
      harness logs anything today.

## 2. Manual hardware validation

### 2.1 Adapter behaviour — confirming the fake assumed correctly

The fake `MifareSession` encodes assumptions about how the real `MifareClassic` class behaves. Each
item names the assumption it tests, so a surprise points at a specific fake fix.

- [ ] **Blank tag is detected as blank.** Fresh factory tag → `read` returns `Blank`.
      *Assumption: sector 1 authenticates with `FF FF FF FF FF FF` on an untouched tag.*
- [ ] **Blank tag write succeeds and verifies.** `write(allowOverwrite = false)` → `Success`.
      *Assumption: the derived key can be installed and immediately re-authenticated in the same
      session. This is the assumption most likely to be wrong — some tags need a reconnect after a
      trailer write.*
- [ ] **Re-read after write returns the written fields.** Tap again → `Written` with the same
      `MappedFields` and reserve.
- [ ] **Access bits survive the trailer write.** Read block 7 after writing a blank tag and confirm
      bytes 6–9 are still `FF 07 80 69` (or whatever the tag shipped with — record the before value).
      *Assumption: writing a trailer does not force access bits to a default. Getting this wrong can
      permanently lock a sector, so check it on a tag you are willing to lose.*
- [ ] **Second write to an already-written tag.** `read` reports `Written`, then
      `write(allowOverwrite = true)` succeeds without touching block 7.
- [ ] **Overwrite protection.** `write(allowOverwrite = false)` on a written tag returns
      `OverwriteRequired` and leaves the tag byte-identical.
- [ ] **7-byte UID tag is rejected** (`HW-02`). Needs a 7-byte-UID Classic 1K clone. Expect
      `IncompatibleUidLength` with nothing written.
- [ ] **Foreign-key tag is rejected.** A tag with a non-default, non-derived sector-1 key →
      `UnknownKeyScheme`, not misread as blank.
- [ ] **Tag pulled away mid-write.** Start a write and remove the phone. Expect a retryable `TagLost`
      with `partiallyWritten = true`, and no crash.
- [ ] **Tag pulled away then rewritten.** After the above, tap again and write fully. Expect `Success`
      — a partially-written tag must be recoverable.
- [ ] **Non-Classic tag** (`HW-06`). Present an NTAG21x. Expect the per-tag rejection
      (`MifareClassic.get` returning null), distinct from the device-level message.

### 2.2 Device compatibility gate

- [ ] **Compatible device.** On the FP4, `DeviceCompatibility.of(...)` is `Compatible`.
- [ ] **Incompatible device**, if a non-NXP NFC phone is available: expect `NoMifareClassicSupport`
      and a clear message, not a confusing authentication failure (`HW-05`). This path is unit tested
      but has never run on real non-NXP hardware.

## 3. Play Store submission
### 3.1 Blocking the closed-testing clock

- [ ] **Recruit 12 closed-testing testers** (3D-printing / Spoolman / Creality communities, friends).
      They only need to opt in and install; a few with NXP-NFC phones give real feedback for the
      production-access form. — STORE_PLAN Phase 0
- [ ] **Enrol in Play App Signing** — makes a lost upload key recoverable, and is what makes storing
      the upload key in a GitHub secret an acceptable risk. — Phase 1
- [ ] **Data Safety, content rating and target audience declarations** in the Console, from the
      verified answers in [STORE_CONTENT.md](store/STORE_CONTENT.md). — Phase 4
- [ ] **Minimal store listing** — short/full description, icon, feature graphic, screenshots; copy
      versioned in `store/STORE_LISTING.md`. — Phase 5

### 3.2 In-app changes required to pass review

- [ ] **Decide on R8** after the first stable release, not before it: `optimization { enable = false }`
      stays as-is for release one. — Phase 1

### 3.3 Release sequence

- [ ] Internal testing track → verify the signed AAB installs and runs from Play.
- [ ] Closed testing track with the 12 testers → 14 continuous days.
- [ ] Apply for production access, then production release.
- [ ] Verify the *release* build on-device: new name and icon, **no harness button in Settings**, and
      a full write-and-verify against a real tag.
- [ ] Confirm Play accepts an upload targeting API 37 with this AGP; fall back to 36 if not. — Risk 6

## 4. Repository security hardening

From a read-only audit of the GitHub settings on 2026-08-10. What was already in place stays out of
this list: secret scanning with push protection, Dependabot security updates, read-only default
workflow permissions, no webhooks or deploy keys, and no credential file ever committed. Everything
below is an open gap. Items marked *[GitHub]* are settings changes; the rest are changes in this
repository.

### 4.2 Branch protection and supply chain

- [x] **Add a ruleset on `master`.** *Done 2026-08-10* — ruleset "Protect master" on
      `~DEFAULT_BRANCH`: blocks deletion and force-push, requires a pull request (0 approvals — a sole
      dev cannot approve their own PR, so any higher number would be unmergeable), and requires the
      `check` status from the github-actions app.
      **Direct pushes to `master` are now rejected; every change goes through a PR.** The required
      context is `check`, the job id — *not* `Check`, the workflow name, and not `check / check`,
      which is what the reusable call inside Release reports.
      *Escape hatch:* there are no bypass actors, so a broken `check` workflow cannot be fixed through
      a PR that runs it. Set the ruleset's enforcement to `disabled` for as long as the fix takes.
- [ ] **Require signed commits**, once signing is set up locally. Deliberately left out of the ruleset
      above: every commit in the repo is currently `verified=false / unsigned`, so turning it on would
      reject all pushes until an SSH or GPG signing key is configured and registered.
- [ ] **Pin every Action to a full commit SHA.** *[GitHub +
      [release.yml](.github/workflows/release.yml), [check.yml](.github/workflows/check.yml)]*
      `checkout@v4`, `setup-java@v4`, `setup-gradle@v4` and `upload-artifact@v4` are mutable tags; a
      compromised tag on any of them executes in the job holding the decoded keystore. Consider
      enabling required SHA pinning repo-wide afterwards.
- [x] **Add `.github/dependabot.yml` for the `gradle` and `github-actions` ecosystems.** *Done
      2026-08-10* — weekly, grouped (androidx / kotlin / ktor / test, AGP deliberately alone), with a
      7-day cooldown and 30 for majors. Renovate was considered and rejected for now: its one real
      advantage here is updating the Gradle wrapper, which is not worth granting a third-party app
      write access to a repository whose `master` is an allowed ref for the signing environment.
- [ ] **Bump the Gradle wrapper by hand, periodically** (currently 9.5.0). Nothing automated watches
      it — Dependabot matches Maven coordinates and the wrapper is a URL plus a committed jar.
      Expect to notice it as an AGP pull request failing on a minimum-Gradle-version error.
      From `src/`, run twice: `./gradlew wrapper --gradle-version X --gradle-distribution-sha256-sum
      <sha>` (sum from `services.gradle.org/distributions/gradle-X-bin.zip.sha256`).
      *Pass the checksum flag explicitly* — omitting it can drop `distributionSha256Sum` and silently
      undo the pin. CI would not fail; the verification would simply stop happening.

### 4.3 Scanning, disclosure and hygiene

- [ ] **Enable CodeQL default setup** for `actions` and `java-kotlin`. *[GitHub]* Free on a public
      repo, and the `actions` pack targets exactly the workflow-injection class this repo has
      something worth losing to.
- [ ] **Enable secret scanning non-provider patterns and validity checks.** *[GitHub]* Both are off;
      non-provider patterns catch generic private keys, which is the relevant shape here.
- [ ] **Disable the unused wiki.** *[GitHub]* Docs live in [docs/](docs/) and on Pages, leaving the
      wiki as a public write surface nobody reads.
- [ ] **Add `SECURITY.md`** — a public repo with no disclosure channel.
- [ ] **Consider narrowing the three enabled merge methods to squash-only.** *[GitHub]* All of
      squash, merge and rebase are still allowed. Cosmetic rather than a security matter — it only
      decides what `master`'s history looks like now that every change arrives as a PR.
      *(Delete-branch-on-merge: done 2026-08-10.)*
- [ ] **Confirm account 2FA is on**, and that fork PR workflows still require approval (Settings →
      Actions → General). *Neither could be read via the API* — the token lacks the scope for 2FA and
      the fork-PR endpoint returned 404 — so these are unverified rather than known-bad. Fork PRs do
      execute `scripts/check.sh` on a runner, which is fine while approval is required.
