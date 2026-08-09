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
      harness logs anything today. Still no persistent write-history *feature* (`REQ-16`).
- [ ] **Broader end-to-end validation** across several spools: different materials, vendors and weights,
      and at least one spool whose material has **no exact catalog match**, to exercise the fallback path
      against a printer. Only one material (HIPS, an exact match) has been validated so far.

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

Rationale, specs, risks and the full Console answers are in [STORE_PLAN.md](STORE_PLAN.md); this is
the outstanding-action list. The 14-day closed-testing window is the critical path and cannot start
until a build is uploaded, so the testing-track items come first.

### 3.1 Blocking the closed-testing clock

- [ ] **Recruit 12 closed-testing testers** (3D-printing / Spoolman / Creality communities, friends).
      They only need to opt in and install; a few with NXP-NFC phones give real feedback for the
      production-access form. — STORE_PLAN Phase 0
- [ ] **Enrol in Play App Signing** — makes a lost upload key recoverable, and is what makes storing
      the upload key in a GitHub secret an acceptable risk. — Phase 1
- [ ] **Off-machine backup of the upload keystore, before the first upload.** — Phase 1
- [ ] **Enable GitHub Pages** from `docs/` on the default branch, then confirm
      `https://mjeanrichard.github.io/nfc-spool-writer/privacy-policy` loads in a private window.
      — Phase 3
- [ ] **Data Safety, content rating and target audience declarations** in the Console, from the
      verified answers in [STORE_CONTENT.md](STORE_CONTENT.md). — Phase 4
- [ ] **Minimal store listing** — short/full description, icon, feature graphic, screenshots; copy
      versioned in `STORE_LISTING.md`. — Phase 5

### 3.2 In-app changes required to pass review

- [ ] **Launcher icon and branding.** Real adaptive icon (spool + NFC wave), keeping the `<monochrome>`
      layer and the 66dp safe zone; regenerate the legacy `.webp` at all five densities; export the
      512×512 store PNG. While in there, delete the unused `purple_*` / `teal_*` template entries from
      `res/values/colors.xml`. — Phase 2b
- [ ] **Remove the tag harness from release builds.** Enable `buildConfig = true`, gate the Settings
      button and the navigation route behind `BuildConfig.DEBUG`. A reviewer will otherwise find it,
      and it exposes raw hex and trailer bits. — Phase 2c
- [ ] **Replace the template backup rules.** `backup_rules.xml` and `data_extraction_rules.xml` are
      unmodified templates, one still containing a literal `TODO:`. Write explicit rules that include
      the `settings` DataStore. — Phase 2d
- [ ] **Non-affiliation disclaimer as an About line on the Settings screen.** — Phase 2a
- [ ] **Decide on R8** after the first stable release, not before it: `optimization { enable = false }`
      stays as-is for release one. — Phase 1

### 3.3 Release sequence

- [ ] Internal testing track → verify the signed AAB installs and runs from Play.
- [ ] Closed testing track with the 12 testers → 14 continuous days.
- [ ] Apply for production access, then production release.
- [ ] Verify the *release* build on-device: new name and icon, **no harness button in Settings**, and
      a full write-and-verify against a real tag.
- [ ] Confirm Play accepts an upload targeting API 37 with this AGP; fall back to 36 if not. — Risk 6

### 3.4 Documentation follow-up

- [ ] **Reconcile the docs with store distribution** (STORE_PLAN Phase 7). `NFR-06` still says
      "sideload / personal APK for v1 — no Play Store listing, no store-listing assets and no
      privacy-policy doc needed for v1", which contradicts the store track; `docs/privacy-policy.md`,
      `STORE_LISTING.md`, `STORE_CONTENT.md` and the `assets/` artwork now exist. Record the app
      rename and the new `applicationId` there too.
