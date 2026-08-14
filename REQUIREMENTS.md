# NFC Spool Writer — Requirements

## 1. Purpose

An Android app that writes RFID tags compatible with the **Creality K2/K2 Plus/K2 Max CFS**
(Creality Filament System) filament-detection reader, using filament spool data pulled from
**Spoolman**. Tags written by the app must be readable by genuine Creality K2/CFS hardware.

### 1.1 Requirement identifiers

Every requirement, decision and open item carries a stable identifier of the form `<PREFIX>-<NN>`,
with sub-requirements as `-<NN>.<M>`. Two prefixes live outside this document:

| Prefix | Covers | Where |
| --- | --- | --- |
| `HW` | Tag, write-hardware and phone NFC chipset constraints | §3, §3.1 |
| `REQ` | Functional requirements — Spoolman integration, write flow | §4, §5 |
| `UI` | App scope and user-facing screens | §6 |
| `NFR` | Non-functional requirements, including security and testability | §7 |
| `DEC` | Settled decisions | [DESIGN.md](DESIGN.md) §2 |

Identifiers are **stable and never reused**: a requirement that is dropped leaves its number
retired rather than reassigned, and requirements keep their ID when they move between sections — or
between documents. Section numbers, by contrast, are positional — cite the ID when referring to a
requirement from code comments, commits or other documents.

Companion documents: [DESIGN.md](DESIGN.md) (architecture and settled decisions),
[TAG_FORMAT_SPEC.md](TAG_FORMAT_SPEC.md) (tag payload format).

## 2. Background / Prior Art

The Creality CFS RFID format is proprietary and undocumented by Creality. It has been
reverse-engineered by the community; this project's own understanding, validated against real
hardware, lives in [TAG_FORMAT_SPEC.md](TAG_FORMAT_SPEC.md).

Reference projects, useful for cross-checking but **not authoritative** — where they disagree with
TAG_FORMAT_SPEC.md, that document reflects hardware observation and wins:

- [DnG-Crafts/K2-RFID](https://github.com/DnG-Crafts/K2-RFID) — original reverse-engineering, plus an
  Android app ("SpoolID"), Arduino/ESP32 and Windows tools. Its `db/k2.json` material catalog is
  incomplete (stops at ID `00007`); the bundled catalog in this project is fuller.
- [flamebarke/creality_rfid](https://github.com/flamebarke/creality_rfid) — Python 3 port.
- [soylentOrange/K2-RFID](https://github.com/soylentOrange/K2-RFID) — ESP32 web-app implementation.
- [sybethiesant/CFSWriter](https://github.com/sybethiesant/CFSWriter) — Android app writing CFS tags
  for third-party spools with OCR label scanning.
- [SimplyPrint's Creality CFS material standard writeup](https://help.simplyprint.io/en/article/the-creality-material-standard-nfcrfid-for-the-creality-cfs-1crrofa/)


## 3. Tag / Hardware Requirements

**The complete specification of the tag payload format, key derivation, and encryption lives in
[TAG_FORMAT_SPEC.md](TAG_FORMAT_SPEC.md)**, with test vectors. This section states only the top-level
requirements.

- `HW-01` — **Tag type:** MIFARE Classic 1K only. NTAG21x, Ultralight and similar are not
  compatible with CFS readers.
- `HW-02` — **UID constraint:** tags must have a **4-byte UID**. Longer UIDs (7-byte "double size",
  found on some Classic 1K clones) must be rejected as incompatible — the key derivation only works
  with 4 bytes.
- `HW-03` — **Write hardware:** the Android device's built-in NFC radio; no external reader/writer.
- `HW-04` — **Two sectors are used:** one holds an AES-encrypted primary payload gated by a per-tag
  derived key, the other a plaintext secondary payload gated by the standard MIFARE default key.

### 3.1 Phone NFC chipset — hard constraint

MIFARE Classic is a proprietary NXP protocol rather than an NFC Forum standard, so Android's
`MifareClassic` API only functions on devices with an **NXP NFC controller**. Phones with Broadcom or
Qualcomm chipsets enumerate a Classic tag but can never authenticate a sector. This cannot be worked
around in software.

`android.hardware.nfc` is declared by *every* NFC-equipped phone and so does **not** distinguish these
devices. The meaningful signal is the **`com.nxp.mifare`** system feature.

- `HW-05` — **Startup runtime check:** verify
  `PackageManager.hasSystemFeature("com.nxp.mifare")` and, if absent, tell the user plainly that the
  device cannot write MIFARE Classic tags, rather than letting them reach the write flow and hit an
  opaque authentication failure. Mandatory, not defensive polish — see `NFR-03` and `NFR-08` for
  why the manifest declaration does not cover it.
- `HW-06` — **Per-tag check:** the feature flag describes the *phone*, not the tag. Before
  writing, confirm the scanned tag actually exposes the `MifareClassic` technology, and reject it with
  its own distinct message.

## 4. Data Source — Spoolman Integration

- `REQ-01` — The app pulls filament/spool data from
  [Spoolman](https://github.com/Donkie/Spoolman), a self-hosted filament inventory manager, via its
  REST API.
- `REQ-02` — **Connection setup:** the user configures the Spoolman server URL once in a settings
  screen. Single instance for v1 — no multi-server switching.
- `REQ-03` — **Spool selection workflow:** browse/search the live spool list, select one, confirm
  the mapped values, then tap the phone to the tags.
- `REQ-04` — **Field mapping:** Spoolman's data model does not map 1:1 onto Creality's fixed
  encodings (weight buckets, material IDs, batch codes). The app **best-effort auto-maps** Spoolman
  fields to the nearest valid Creality encoding and applies documented defaults for anything Spoolman
  doesn't provide. Every approximation is surfaced on the confirm screen.
- `REQ-05` — **Confirm-before-write:** the user sees the mapped values before anything is written.
  This is the guard against a bad auto-mapping being burned onto a tag and only discovered at the
  printer.
- `REQ-06` — **Reachability check with retry:** whenever the app needs Spoolman (spool list, confirm, write) it
  must establish that the configured server actually answers, and when it doesn't, say so in terms the
  user can act on — **unreachable host** (wrong address/port, server down, off the LAN) must be
  distinguishable from *not configured yet*, from an HTTP error the server itself returned, and from a
  cleartext-blocked connection. Every such failure offers an explicit **retry** action; recovery must
  not require restarting the app or re-entering the URL.
- `REQ-07` — **Refreshable spool list:** the spool list must be re-loadable on demand from the list
  screen — the Spoolman inventory changes while the app is open (spools added, weights updated), and
  the list is otherwise only loaded when the screen is first created. Refresh must be available in the
  normal populated state, not only after an error, and must keep the current search query.
- `REQ-08` — **Connectivity:** a live network connection to Spoolman is required whenever the app is
  in use. No offline caching of the spool list.
- `REQ-09` — **Cleartext HTTP must be permitted** (`network_security_config.xml`). Spoolman is self-hosted and in
  practice runs over plain HTTP on a LAN, while Android blocks cleartext by default at `targetSdk` 28+.
  Without this the app cannot reach a typical instance at all, and the failure is indistinguishable from
  an unreachable server.

## 5. Write Flow & Error Handling

- `REQ-10` — **Selecting data:** browse/search the Spoolman spool list → pick a spool → review the
  mapped values.
- `REQ-11` — **Writing:** the user taps the phone to a tag; the app authenticates with the derived
  key, writes the payload, then **reads the tag back and compares** before reporting success.
  Write-and-verify, never write-only.
- `REQ-12` — **One tag per write, repeatable on demand.** A write targets a single tag and is complete once that
  tag is written and verified. The app does **not** expect or require a second tag per spool: genuine
  Creality spools carry two tags with identical payloads (TAG_FORMAT_SPEC.md §9), but a self-tagged
  spool may carry one, two, or none, so how many tags a spool gets is the user's choice, not something
  the app tracks progress against. After a successful write the app offers **"write another tag"**,
  which re-arms for a fresh tap and writes **the same mapped data** without re-selecting the spool or
  re-confirming. A tag already written in this session is recognised by UID and reported as such rather
  than silently rewritten.
- `REQ-13` — **Overwrite protection:** if a tag already contains data, the app detects this and
  **prompts for confirmation** before overwriting. Tags are not write-once, but accidental overwrites
  must be prevented. Confirming requires a fresh tap, since the tag connection cannot be held open
  across a dialog.
- `REQ-16` — **Changing only the spool ID.** The overwrite prompt offers a second way to say yes:
  leave the tag exactly as it is and change **only** the Spoolman spool ID. A tag written by Creality
  carries a real batch number, date code and reserve bytes that describe the physical spool and cannot
  be recreated once replaced, so re-pointing such a tag at a different Spoolman spool must not cost the
  user that data. Every byte outside the serial-number field is preserved, including the reserve — the
  duplicate ID it holds under `DEC-01` is this project's own convention, not the format's, and refreshing
  it would defeat the purpose. The option is offered only when the tag's existing content parses: there
  is nothing worth keeping around a tag that does not, and the app says so rather than half-repairing it.
- `REQ-14` — **Write failures** (e.g. tag moved away mid-write): auto-retry a few times, then
  surface a clear message with guidance rather than aborting on the first failure. If retries are
  exhausted, warn that the tag may be left partially written and should be rewritten in full before use.
- `REQ-15` — **Authentication is not reliable on a single attempt.** A genuine tag has been observed
  reporting that neither key authenticates on one tap and succeeding on the next. The key probe
  therefore retries, reconnecting between attempts, before concluding a tag uses an unrelated key
  scheme — that conclusion is terminal and would otherwise wrongly reject a good tag.

## 6. App Scope & UI

- `UI-01` — **Style:** a fuller app rather than a single-screen tool — a settings screen plus a
  multi-step write flow (browse → select → confirm → write → verify).
- `UI-02` — **Read & check mode.** A user-facing mode that reads an existing tag and reports what is
  on it, without writing anything. It must:
  - `UI-02.1` — decode the payload and show the mapped values in the same human terms as the confirm
    screen (material, colour, weight, spool ID, batch/date, supplier) rather than a hex dump;
  - `UI-02.2` — **check** the tag rather than merely dumping it — state whether it is blank, written
    by this app, written with an unrelated key scheme, or written but undecodable/corrupt, and whether
    the two payload copies (sectors) agree;
  - `UI-02.3` — name the specific incompatibility for a tag that cannot be used at all (7-byte UID,
    non-Classic tag), reusing the write flow's messages;
  - `UI-02.4` — be reachable from the main flow without going through spool selection, since its
    purpose is answering "what is on this tag?" for a tag of unknown provenance.
  - `UI-02.5` — be a read-only path: it must never authenticate-and-write, and must never install a
    key on a tag it inspects.
- `UI-03` — **No tag-triggered launch.** Presenting a tag must not launch the app or bring it to the foreground.
  NFC is only ever picked up by reader mode inside a screen that has explicitly asked for a tap (write,
  read & check, harness). Tapping a spool while doing something else on the phone is routine, and an
  app that jumps to the front for it is a nuisance — worse here, since the app's foreground screen at
  that moment could be a write flow armed for a different spool.
- `UI-04` — **Raw tag tooling:** a development harness for reading and diagnosing raw tag contents exists. It is
  not a v1 user-facing requirement, but is retained because it is the only way to validate tag
  behaviour on real hardware, which unit tests cannot reach. The user-facing read
  & check mode above does not replace it: the harness shows trailer/access bits and raw hex that the
  read mode deliberately hides.

## 7. Non-Functional Requirements

### 7.1 App
- `NFR-01` — **Platform:** Android only.
- `NFR-02` — **Minimum SDK / target:** Android 10 (API 29) and above — `minSdk = 29`, `targetSdk` =
  latest stable at build time.
- `NFR-03` — **Device compatibility:** requires NFC hardware *and* an NXP-based NFC controller (§3.1). Both are
  declared as required `uses-feature` entries — `android.hardware.nfc` and `com.nxp.mifare` — so a store
  listing would be filtered to devices that can actually run the app. **These declarations are a
  store-side filter only:** `PackageManager` does not enforce `uses-feature` at install time, so a
  sideloaded APK installs on an incompatible phone regardless. That is precisely why the startup runtime
  check `HW-05` is required rather than optional — the manifest protects store users, the runtime
  check protects sideload users, and `NFR-06` means the app now has both.
- `NFR-04` — **Connectivity:** always-online; no offline mode.
- `NFR-05` — **Localization:** English only for v1. Screen strings are centralized in `strings.xml`;
  error and failure messages are currently Kotlin string literals, so localising would mean moving those
  to string resources with format args.
- `NFR-06` — **Distribution:** Google Play, with a sideloadable APK attached to each GitHub release
  for testers outside the Play tracks. This supersedes the original sideload-only scope; the
  `uses-feature` declarations anticipated the change, so it cost no manifest work. Consequences that
  are now requirements rather than optional extras: store-listing assets (`assets/`), listing copy
  (`store/STORE_LISTING.md`), Console declarations (`store/STORE_CONTENT.md`) and a publicly hosted
  privacy policy (`docs/privacy-policy.md`, served from GitHub Pages at
  `https://mjeanrichard.github.io/nfc-spool-writer/privacy-policy`).
  **Identity, fixed:** the app is **NFC Spool Writer**, applicationId `ch.jeanrichard.nfcspoolwriter`.
  The name can change later; the applicationId cannot — it becomes the store URL and is frozen
  permanently by the first upload.
  **Signing:** an upload keystore held outside the repo, mirrored into GitHub secrets so CI produces
  the upload artifacts. Under Play App Signing, Google holds the app signing key, which is what makes
  a lost *upload* key recoverable — that enrolment is what turns the keystore from a single point of
  failure into a replaceable credential. The APK attached to a GitHub release is signed with the
  upload key, not the app signing key, so a sideloaded install cannot be upgraded in place by the
  Play build.
- `NFR-07` — **Language/tooling:** Kotlin, Jetpack Compose (see [DESIGN.md](DESIGN.md) for
  architecture).
- `NFR-08` — **Required hardware:** declare `<uses-feature android:name="android.hardware.nfc" android:required="true">`
  and `<uses-feature android:name="com.nxp.mifare" android:required="true">`, so the device-compatibility
  signal Play filters on is correct. Written before Play distribution was in scope, on the argument
  that it was right manifest hygiene and would matter if distribution ever changed — which it since
  has (`NFR-06`), at no manifest cost. These two entries carry that signal
  on their own; **no `TECH_DISCOVERED` intent filter or tech-list is declared**, because the app must
  not be launched by a tag tap (`UI-03`) and tags are only ever read through reader mode.
  The `com.nxp.mifare` entry is the one that actually excludes phones whose NFC chipset can't do
  MIFARE Classic at all; since `uses-feature` is a store-side filter that `PackageManager` ignores,
  it must be paired with the startup capability check `HW-05`.
- `NFR-09` — **Permissions:** `NFC`, `INTERNET`. Nothing else — no storage/camera permissions needed
  for this scope (no OCR, no history log, no offline cache to persist).
- `NFR-10` — **Cleartext HTTP:** permitted via `network_security_config.xml`, because Spoolman is
  typically plain HTTP on a LAN and Android blocks cleartext by default at `targetSdk` 28+ (`REQ-09`).

### 7.2 Security
- `NFR-11` — The AES keys used for tag key-derivation and payload encryption are **not
  secrets** — they're fixed constants inherent to the Creality format (see
  [TAG_FORMAT_SPEC.md](TAG_FORMAT_SPEC.md) §§5-6), embedded directly in the app. This is not a
  security boundary; don't over-invest in hiding them (e.g. no need for NDK/obfuscation just for this).
- `NFR-12` — The Spoolman server URL is the only credential-adjacent setting; store it via
  DataStore. Since Spoolman itself has no auth, there's no token to protect. If a future
  reverse-proxy-auth case is added, store any secret via `EncryptedSharedPreferences`/DataStore+Tink
  rather than plain DataStore.
- `NFR-13` — No analytics/telemetry/crash-reporting SDKs — this is a personal tool talking to a
  self-hosted server; don't add a third-party data path without it being explicitly requested.

### 7.3 Performance
- `NFR-14` — **Performance:** no specific latency budget beyond "feels responsive" — NFC MIFARE
  Classic read/write of ~10 blocks is inherently fast (sub-second per block typically); the practical
  bottleneck is the user holding the phone steady against the tag, not app logic.

### 7.4 Testability
The guiding rule is *unit test everything where it's possible to, skip UI tests entirely*.
- `NFR-15` — **Pure logic** (key derivation, payload cipher, tag codec, field mapping,
  weight-bucket rounding, material fallback) — plain JVM unit tests, no framework dependency at all.
- `NFR-16` — **NFC orchestration** (`MifareTagReaderWriter`) — the Android-specific bits are
  isolated behind the `MifareSession` interface (DESIGN.md §1.2) so the retry/overwrite/verify logic is
  unit tested against a fake session, not real hardware. Only the thin `MifareClassicSession` adapter
  is untested.
- `NFR-17` — **Networking** (`SpoolmanApiClient` / repository) — unit tested against Ktor's
  `MockEngine`: successful list/get, empty results, non-2xx errors, malformed JSON, unreachable host.
- `NFR-18` — **ViewModels** — unit tested with fake repositories (`MockK` or hand-written
  fakes) and `kotlinx-coroutines-test`, asserting the state transitions (loading → success/error,
  selection, confirm, write progress) independent of any Compose rendering. This is the piece most
  easily skipped by accident; treat ViewModel test coverage as equally mandatory as the domain-layer
  coverage above.
- `NFR-19` — **Compose UI** — explicitly not automated-tested. Screens are kept thin (render
  ViewModel state, forward events) so there's nothing UI-only worth testing; verified manually instead.
- `NFR-20` — **`MifareClassicSession` adapter and full hardware round-trips** — can't be unit
  tested by definition (real NFC hardware + a real tag), covered by manual checks on a device.

### 7.5 Maintainability
- `NFR-21` — Minimal dependency surface, no premature abstraction —
  e.g. no repository-interface-plus-fake-impl scaffolding for Spoolman until there's a second
  real implementation to justify it.
