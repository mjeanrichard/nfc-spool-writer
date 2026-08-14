# NFC Spool Writer — Design

Companion to [REQUIREMENTS.md](REQUIREMENTS.md), which this document assumes as read: requirements
say *what* the app must do, this says *how* it is built and *why* the non-obvious choices were made.
Tech-stack choices below (Kotlin, Compose, sideload-first distribution) are also reflected there.

The `DEC-nn` identifiers in §2 follow the scheme defined in [REQUIREMENTS.md §1.1](REQUIREMENTS.md);
they are stable and travel with the decision, so they stay valid even though the text moved here.

## 1. Architecture Overview

Single-module Android app (Kotlin + Jetpack Compose). No multi-module split, no DI framework
(Hilt/Koin) — the app is small enough that a hand-rolled `AppContainer` (a few `by lazy`
constructor-wired singletons) is simpler to read and modify than framework machinery. Revisit only
if the codebase actually grows past what that comfortably supports.

```
app/
├── ui/
│   ├── settings/        SettingsScreen + ViewModel        (Spoolman URL config)
│   ├── spoollist/       SpoolListScreen + ViewModel        (browse/search Spoolman)
│   ├── confirm/         ConfirmScreen + ViewModel          (review mapped fields)
│   ├── write/           WriteScreen + ViewModel            (NFC tap/progress/result)
│   ├── read/            ReadTagScreen + ViewModel          (read & check an existing tag)
│   ├── navigation/       Compose Navigation graph
│   ├── nfc/              NfcReaderEffect (reader-mode lifecycle)
│   └── debug/            Tag harness (development only)
├── domain/
│   ├── model/           MappedFields, MaterialEntry, Spool, WeightBucket, ...
│   ├── mapping/          FieldMappingService, MaterialMatcher
│   └── tagcodec/         TagCodec (structured fields <-> 96-char payload string)
├── data/
│   ├── spoolman/         SpoolmanApiClient (Ktor), DTOs, repository
│   ├── materials/         MaterialCatalog (bundled materials.json) + loader
│   ├── nfc/               KeyDerivation, PayloadCipher, MifareTagReaderWriter
│   └── settings/          DataStore-backed settings repository
└── AppContainer.kt      Manual DI wiring
```

### 1.1 Data flow (the write path)

```
SpoolListScreen (Spoolman search/list)
    │  user picks a spool
    ▼
FieldMappingService.map(spool) -> MappedFields  (best-effort auto-map + fallback defaults)
    │
    ▼
ConfirmScreen  (shows mapped fields and every approximation made; user confirms)
    │  user confirms
    ▼
WriteScreen
    │  1. wait for tag tap (NFC reader mode)
    │  2. KeyDerivation.deriveKey(tag.uid) -> sector-1 key
    │  3. TagCodec.encode(MappedFields) -> 96-char string
    │  4. PayloadCipher.encrypt(bytes 0-47) for sector 1; bytes 48-95 stay plaintext
    │  5. MifareTagReaderWriter.write(...)  (installs sector-1 trailer key on first write,
    │     detects "already written" -> triggers overwrite confirm dialog before writing)
    │  6. Read back + TagCodec.decode + compare -> verify
    │  7. success / retry-with-guidance / failure state
```

### 1.2 Key modules and what they own

- **`data/nfc/KeyDerivation`** — pure function implementing [TAG_FORMAT_SPEC.md](TAG_FORMAT_SPEC.md)
  §5: tile 4-byte UID to 16 bytes, AES-128-ECB with the fixed key, take first 6 bytes. Fully unit
  testable without hardware (known UID → known key vectors, per the spec's worked example).
- **`data/nfc/PayloadCipher`** — pure function implementing [TAG_FORMAT_SPEC.md](TAG_FORMAT_SPEC.md)
  §6: AES-128-ECB encrypt/decrypt of the 48-byte sector-1 block with the second fixed key. Also
  unit testable.
- **`domain/tagcodec/TagCodec`** — pure encode/decode between `MappedFields` and the 96-char
  string per the byte layout in [TAG_FORMAT_SPEC.md](TAG_FORMAT_SPEC.md) §9, including the serial-number/reserve
  Spoolman-ID logic. Pure, unit testable, no Android dependencies.
- **`data/nfc/MifareSession`** — a thin interface (`connect()`, `authenticateSectorWithKeyA(sector,
  key)`, `readBlock(block)`, `writeBlock(block, data)`, `close()`) wrapping the handful of
  `android.nfc.tech.MifareClassic` calls we actually use. `MifareClassicSession` is the real
  implementation (untestable-by-JVM-unit-test, since it wraps a final Android framework class tied
  to a live `Tag`). This interface exists **specifically so `MifareTagReaderWriter` doesn't have
  to be** — see below.
- **`data/nfc/MifareTagReaderWriter`** — all the orchestration logic: authenticate sector 1
  (derived key) + sector 2 (default key), read/write the right blocks, install the sector-1
  trailer key on first write, detect already-written tags, retry-on-failure, verify-after-write by
  re-reading and comparing. It depends only on `MifareSession` (the interface) plus `KeyDerivation`
  + `PayloadCipher` + `TagCodec`, so it's fully unit testable against a fake `MifareSession` that
  simulates a blank tag, an already-written tag, an auth failure, a write that fails then succeeds
  on retry, etc. — every branch of the retry/overwrite/verify logic gets a test without touching
  real hardware. Only the thin `MifareClassicSession` adapter itself is unverified by automated
  tests.
- **`domain/mapping/FieldMappingService`** — Spoolman `Spool`/`Filament`/`Vendor` → `MappedFields`.
  Owns the weight-bucket rounding, the material→filament-ID lookup (via `MaterialCatalog`), and
  vendor-ID defaulting. Pure, unit testable with fixture Spoolman payloads.
- **`data/materials/MaterialCatalog`** — the bundled `materials.json` catalog in memory; lookup by ID,
  by generic material type, and by exact catalog name. `domain/mapping/MaterialMatcher` owns the
  documented fallback chain and fails closed when nothing matches (`DEC-04`).
- **`data/spoolman/SpoolmanApiClient`** — thin HTTP client for `GET /api/v1/spool` (search/list,
  using `filament.material`, `filament.vendor.name`, `location`, etc. as needed) and
  `GET /api/v1/spool/{id}`. No auth headers needed against Spoolman itself — confirmed from its
  source, it has no built-in API authentication. (If a user's instance sits behind a reverse proxy
  requiring auth, that's explicitly out of scope per `DEC-06`, unless it becomes a real blocker.)

### 1.3 Libraries

- **Compose + Navigation-Compose** — UI.
- **Kotlin Coroutines/Flow** — async NFC callbacks, network calls, UI state.
- **Ktor client (OkHttp engine)** — Spoolman HTTP calls.
- **kotlinx.serialization** — JSON (de)serialization for both Spoolman DTOs and the bundled
  material catalog.
- **Jetpack DataStore (Preferences)** — settings storage (Spoolman URL). No Room/SQLite needed —
  there's no local write-history log and no offline spool cache in v1 (per `REQ-08`, `REQ-16`).
- **JUnit + kotlinx-coroutines-test** — unit tests, including ViewModel state-flow tests.
- **MockK** — mocks `SpoolmanRepository` in ViewModel tests, so no interface or `open` modifier exists
  purely to serve tests. `MifareSession` has a hand-written in-memory fake instead, since it needs
  stateful behaviour and fault injection.
- **Ktor `MockEngine`** — fakes the HTTP layer for `SpoolmanApiClient` tests (canned JSON
  responses, error statuses, malformed payloads) without a real Spoolman instance.
- No Espresso/instrumented UI tests — per explicit instruction, UI tests are not needed. Compose
  screens stay thin (state in, events out) precisely so nothing important is *only* verifiable
  through the UI layer; the NFC-hardware path also can't be meaningfully automated regardless, so
  both get manual checks on a device instead.

## 2. Decisions

Settled choices that are not obvious from the code, with the reasoning that would otherwise be lost.

- `DEC-01` — **Serial number and reserve fields carry the Spoolman spool ID.** The serial holds it
  zero-padded to 6 digits; the reserve repeats it in its first 6 characters and zero-fills the remaining
  8. This deviates from genuine tags, which hold a per-spool non-zero byte at payload offset 40 whose
  meaning is unidentified.
- `DEC-02` — **The batch-number and date-code fields are written as the constants `AB1` and
  `24027`.** With the supplier ID they concatenate to `AB1240276A21`, exactly the prefix every
  community implementation writes and printers are reported to accept. Neither is constant on
  genuine tags, which carry varying values, so both must be **read** permissively and preserved —
  only the write side pins them. The field boundaries these constants fill are specified in
  TAG_FORMAT_SPEC.md §9 and enforced on `MappedFields`.
- `DEC-03` — **Every spool is written with Creality's supplier ID (`6A21`)**, whoever made the
  filament. It is the only value observed on genuine tags and the only one with evidence of being
  accepted; no registered ID exists for anyone else; and whether the field affects printer behaviour is
  unknown. A printer has accepted it on a third-party (eSUN) spool. Reversing this is a one-line change
  in `FieldMappingService`.
- `DEC-04` — **Unmappable materials fail closed.** A wrong material ID makes the printer apply wrong
  nozzle and bed temperatures with no warning, so a material with no exact match and no defensible
  same-family substitute is reported to the user rather than guessed at. The bundled catalog
  (`assets/materials.json`, 52 entries, 27 of them `Generic`) is broad enough that exact type matches
  are the common case and fallbacks the exception.
- `DEC-05` — **The weight bucket encodes the spool's nominal full weight**, not its remaining weight
  — the bucket describes the spool's size, which does not change as filament is consumed.
- `DEC-06` — **Spoolman needs no credentials.** Spoolman has no built-in authentication, so no token
  handling exists. An instance behind a reverse proxy with its own auth is out of scope; the app detects
  a 401/403 and says so explicitly, since that is the only thing such a response can mean.
- `DEC-07` — **Spool ID 1 is written, not remapped.** Jacobean's firmware treats a serial of `1` as
  "no ID" and skips the Spoolman lookup, so such a tag never auto-selects (TAG_FORMAT_SPEC.md §9).
  Substituting another ID would put a value on the tag that does not exist in the user's Spoolman, which
  is worse than the quirk; blocking the write would be wrong for firmwares without it. So the mapping
  emits a warning on the confirm screen and writes the ID unchanged. Spoolman cannot renumber a spool —
  the ID is its database key, not an editable property — so the remedy is the user's to choose: add the
  spool again as a new record, or select the filament by hand at the printer.
