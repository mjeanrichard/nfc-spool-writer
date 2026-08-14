# Creality CFS RFID Tag Format — Specification

A complete, standalone specification of the tag data format, key derivation, and encryption used by this
project. Implementation needs nothing beyond this document: algorithms are described in prose and tables,
and every worked example below is independently computed and reproducible.

The format is not documented by Creality. It was originally reverse-engineered by the community, and the
description here has since been corrected against **real hardware** — genuine Creality tags read from
real spools, and app-written tags accepted by a real K2/CFS unit. Where this document disagrees with a
community source, this document reflects hardware observation.

§14 states exactly which parts are hardware-confirmed and which are still assumptions.

---

## 1. Physical Medium: MIFARE Classic 1K

A MIFARE Classic 1K tag provides 1024 bytes organized as **16 sectors**, each of **4 blocks** of
**16 bytes**:

- Blocks are numbered 0–63 across the whole tag. Sector *n* contains blocks `4n` through `4n+3`.
- The **last block of every sector is a "trailer"**, not general storage. Its 16 bytes are fixed-format:

  | Bytes | Meaning |
  |---|---|
  | 0–5 | Key A |
  | 6–8 | Access bits |
  | 9 | General Purpose Byte (GPB) |
  | 10–15 | Key B |

  The access bits control what Key A and Key B may each do for that sector's data blocks. **This format
  never redefines access bits** — it leaves whatever is already on the tag untouched and only ever
  overwrites the Key A / Key B fields (§8).

- **Sector 0, block 0** is the manufacturer block holding the UID. Read-only, never written.
- Reading or writing any data block requires **authenticating** to its sector with Key A or Key B. A
  factory tag's sectors are all on the well-known **default key**: six `0xFF` bytes, valid as both Key A
  and Key B. This is a standard published MIFARE default, not specific to this project.

## 2. Which Sectors This Format Uses

| Sector | Blocks | Content | Key used |
|---|---|---|---|
| Sector 1 | 4, 5, 6 (data) + 7 (trailer) | Primary payload — **encrypted** | Per-tag derived key (§5) |
| Sector 2 | 8, 9, 10 (data) + 11 (trailer) | Secondary payload — **plaintext** | MIFARE default key (`FF`×6) |

No other sector (0, 3–15) is read, written, or relevant.

## 3. Tag UID Requirement

The tag must have a **4-byte UID** — the standard MIFARE Classic length. Tags with 7-byte "double size"
UIDs, found on some Classic 1K clones, are incompatible and must be rejected before any write is
attempted: the key derivation in §5 only works with 4 bytes.

## 4. Two Independent Layers of AES

Two **separate AES-128-ECB operations with two different fixed keys**, serving entirely different
purposes. They are easy to conflate and are not interchangeable:

1. **Sector-key derivation** (§5) — turns the tag's UID into a *per-tag* 6-byte MIFARE sector key. Its
   only job is to gate *authentication* to sector 1. It has nothing to do with the content stored there.
2. **Payload content cipher** (§6) — encrypts the 48 bytes actually stored in sector 1, using one fixed
   key identical for every tag.

Both use **AES-128 in ECB mode with no padding**. No padding is needed because the inputs are always
exact multiples of the 16-byte block size: a 16-byte tiled UID in step 1, a 48-byte payload in step 2.

## 5. Sector-Key Derivation

Turns a tag's 4-byte UID into a 6-byte MIFARE sector key deterministically, so any device implementing
the same algorithm computes the same key without storing or transmitting it — the UID is freely readable
without authentication, so it is sufficient input.

**Fixed key "K1"**, used only for this step:

- Hex: `71 33 62 75 5E 74 31 6E 71 66 5A 28 70 66 24 31`
- ASCII: `q3bu^t1nqfZ(pf$1`

**Algorithm:**

1. Take the 4-byte UID `U0 U1 U2 U3`.
2. Build a 16-byte block by repeating it four times: `U0 U1 U2 U3 U0 U1 U2 U3 U0 U1 U2 U3 U0 U1 U2 U3`.
3. Encrypt that block with AES-128-ECB (no padding) using K1.
4. Take the **first 6 bytes** of the ciphertext. That is the derived sector key.

This key is used as **Key A** for sector 1, and is written into the sector-1 trailer as **both Key A and
Key B** when a tag is first secured (§8).

### Worked example

Given UID `11 22 33 44`:

| Step | Value (hex) |
|---|---|
| Tiled 16-byte block | `11 22 33 44 11 22 33 44 11 22 33 44 11 22 33 44` |
| AES-128-ECB(K1, block) | `16 78 50 90 54 8E 2C 65 4D 8C AA 04 96 35 5D BD` |
| Derived sector key (first 6 bytes) | `16 78 50 90 54 8E` |

Any correct implementation must produce `16 78 50 90 54 8E` for that UID. This makes a good first unit
test: no hardware, no tag, just AES-128-ECB.

## 6. Payload Content Cipher

Encrypts the 48 bytes written to sector 1's data blocks (4, 5, 6), independently of and in addition to
the sector-key gate in §5.

**Fixed key "K2"** — different from K1, used only for this step:

- Hex: `48 40 43 46 6B 52 6E 7A 40 4B 41 74 42 4A 70 32`
- ASCII: `H@CFkRnz@KAtBJp2`

- **Encrypting (write):** take the 48-byte sector-1 plaintext and encrypt it in one AES-128-ECB
  (no padding) operation with K2. ECB has no chaining, so this is equivalent to three independent
  16-byte block encryptions — but call the AES primitive once on all 48 bytes rather than splitting
  blocks by hand.
- **Decrypting (read):** the exact inverse with the same key.

Sector 2's 48 bytes are **never encrypted** — written and read as plain bytes.

### Worked example

Given the 48-character plaintext `AB1240276A21010010FF0000033000004200004200000000` (its field
structure is §9's worked example; here it is just an arbitrary 48-byte input), AES-128-ECB(K2, ·)
produces:

```
57 F2 5B 78 07 6D 4C 17 97 B1 BE 35 CA 26 95 40
11 A0 4C 3E 0D 0B DF 51 73 4B 01 1C 49 C7 A9 AB
8B B6 FB 2E F6 77 26 58 4C 4E F2 27 E4 EB 89 7C
```

The three lines correspond to blocks 4, 5 and 6. Decrypting reproduces the input exactly.

## 7. Overall Payload Structure

The complete tag content is a **96-character string**, never binary-packed — every field is literal
ASCII digits and letters, not raw numeric values.

1. Assembled from the fields in §9, concatenated in fixed order.
2. Right-padded with ASCII spaces to exactly 96 characters. §9's fields fill only the first 48
   characters, so the second half is entirely padding.
3. Encoded as bytes — 96 bytes total.
4. Split into two 48-byte halves:
   - **Part 1** = bytes 0–47 → encrypted per §6 → sector 1, blocks 4, 5, 6 in order.
   - **Part 2** = bytes 48–95 → plaintext → sector 2, blocks 8, 9, 10 in order.

Sector 2 carries no known field. Genuine tags leave all 48 bytes zero; community implementations put a
printer model name there and printers accept either, so it is best read as unstructured filler. This
project writes spaces and ignores whatever it reads.

**Use a byte↔text encoding that round-trips arbitrary bytes** — ISO-8859-1 (Latin-1), not US-ASCII. Real
tags contain bytes ≥ `0x80` (§9, reserve field), and decoding those as US-ASCII substitutes U+FFFD and
destroys the value irrecoverably. Re-encoding then yields `0x3F` (`?`), which looks like real data. This
matters for correctness, not just diagnostics: the write path verifies by comparing a re-read payload
against what was intended, and a lossy decode can make differing bytes compare equal.

## 8. Sector Authentication & Key Installation

**Sector 1** is authenticated with **Key A**, and there are three possible outcomes:

- **Already secured by this format** — Key A is the §5-derived key for this tag's UID. Authentication
  succeeds; the sector can be read and written.
- **Blank / never written** — sector 1 is still on the factory default key. The derived key fails; the
  default key succeeds. This is how a reader **detects whether a tag has already been written**: try the
  derived key first (success ⇒ already written, may need overwrite confirmation), then the default key
  (success ⇒ blank, safe to initialize).
- **Neither key authenticates** — the tag uses some other key scheme. Treat as an error, never as
  "blank" or "ours".

**Authentication is not reliable on a single attempt.** A genuine tag has been observed reporting that
neither key authenticates on one tap and succeeding on the next, with nothing changed. Implementations
must therefore **retry the whole probe before concluding "neither key"**, and must **reconnect the tag
between attempts** — a failed authentication can leave a MIFARE Classic tag refusing further attempts
until the connection is reset. Without the retry, a perfectly good tag is intermittently reported as
using an unrelated key scheme, which is a terminal verdict.

**First write to a blank tag:** after writing the encrypted payload to blocks 4–6, rewrite the sector-1
trailer (block 7) to install the derived key as both Key A and Key B:

- Bytes 0–5 (Key A) ← derived key
- Bytes 10–15 (Key B) ← derived key
- Bytes 6–9 (access bits + GPB) ← **left exactly as read from the tag's existing trailer**

After the trailer write, the sector's key has changed underneath the session — re-authenticate with the
derived key before any further access to sector 1.

**Subsequent writes to an already-secured tag:** authenticate with the derived key; no trailer rewrite is
needed.

**Sector 2** is always authenticated with the default key, and its trailer is never modified.

## 9. Field Layout (within the 96-character string)

Offsets are 0-indexed, end-exclusive (`[start, end)`).

| Offset | Length | Field | Encoding |
|---|---|---|---|
| `[0, 3)` | 3 | Batch number | 3 ASCII characters. **Varies between tags** (`3A9`, `2C5` observed on genuine tags). Not meaningful to this project, which writes a constant. |
| `[3, 8)` | 5 | Date code | 5 ASCII characters, reported to be `YYMDD`. **Preserved verbatim on read, never parsed** — see below. |
| `[8, 12)` | 4 | Supplier ID | 4 ASCII characters. `6A21` for Creality. **No field equals `0276`**, despite that value being widely quoted as Creality's vendor code — see below. |
| `[12, 17)` | 5 | Material ID | 5 ASCII digits selecting a material profile (temperatures, cooling) from the printer's material database. `01001` = Creality "Hyper PLA"; `00001` = Generic PLA. There is no structural prefix digit. |
| `[17, 24)` | 7 | Colour | The literal digit `0`, then 6 hex digits `RRGGBB`. Treat as case-insensitive hex. |
| `[24, 28)` | 4 | Filament length code | 4 ASCII digits from a fixed set of weight buckets (below) — not grams, not millimetres. |
| `[28, 34)` | 6 | Serial number | 6 ASCII digits, zero-padded. Not unique in practice: genuine tags from different spools have both been observed carrying `000001`. **No known firmware reads this field** — see below. This project writes the Spoolman spool ID here anyway, mirroring the reserve. |
| `[34, 40)` | 6 | Reserve — spool ID | 6 ASCII digits, zero-padded. **This is the field a printer resolves a Spoolman spool from**, and it is the only field this project relies on being read. `000000` and `000001` mean "no ID" — see below. Genuine tags carry `000000`. |
| `[40, 48)` | 8 | Reserve — trailing | **Not all zeros on genuine tags** — see below. Outside the 40-character record consumers parse, so nothing is known to read it. This project writes 8 zeros. Readers must surface it verbatim, including non-printable bytes. |
| `[48, 96)` | 48 | Padding | No known field. See §7 — write spaces, ignore on read. |

### Weight-bucket codes (`[24, 28)`)

Only these five codes are valid; there is no arbitrary-gram encoding. Any real weight must be rounded to
a bucket. They encode the spool's **nominal full weight**, not remaining filament.

| Code | Nominal spool weight |
|---|---|
| `0082` | 250 g |
| `0165` | 500 g |
| `0198` | 600 g |
| `0247` | 750 g |
| `0330` | 1000 g |

These are not gram values; they appear to be nominal filament length in metres for 1.75 mm filament at
typical PLA density, but the derivation is irrelevant — treat them as five opaque lookup codes.

### The reserve is what carries the spool ID, not the serial

`[34, 40)` — not the serial number — is the field a printer looks a Spoolman spool up by.
[Jacobean's K2 Plus firmware documents this explicitly](https://jacob10383.github.io/k2-plus-custom-firmware/rfid/):
a tag is resolved either by putting the Spoolman spool ID in `reserve`, in which case the material and
colour fields on the tag are ignored entirely, or by leaving `reserve` at `000000` and having the
printer match the material/colour fields against its own catalog. If `reserve` holds an integer greater
than `1`, the Spoolman path wins even when the material field is also set. The serial number takes no
part in resolution.

This project writes the ID into **both** fields (DESIGN.md `DEC-01`). The reserve copy is the one that
functions; the serial copy is a project convention, and no code may treat it as a second opinion about
which spool a tag names.

**`0` and `1` mean "no ID".** The firmware treats both reserve values as absent and skips the Spoolman
lookup: such a tag reads correctly, but the printer neither looks the spool up nor selects it
automatically. That is consistent with `000000` being what genuine Creality tags carry in the reserve
and `000001` what they carry in the serial when neither means anything (above) — the firmware cannot
distinguish a real spool ID 1 from a placeholder.

Writing it is still correct — the ID is the user's data, and other firmwares do not share the quirk — so
this project writes the value and warns on the confirm screen instead of remapping it. Only the value `1`
is reachable, Spoolman never issuing `0`.

This also fixes what the "change the spool ID only" overwrite (REQUIREMENTS.md `REQ-16`) touches:
`[34, 40)` and nothing else. Rewriting the serial too would gain nothing, since nothing reads it, and
would cost the tag a field of its own; leaving `[34, 40)` alone would leave the tag naming the previous
spool to the printer.

### The date code `[3, 8)`

Reported as `YYMDD`, but **that reading does not parse against observed tags**: both a genuine tag
(`25027`) and the community reference value (`24027`) hold `0` in the month position, which is invalid
1-indexed. It parses as a 0-indexed month (`0` = January) or as `YY` + day-of-year, both yielding
27 January — and the available samples cannot distinguish those.

Writing a wrong date is worse than writing a proven constant, so this project preserves the field on read
and writes a fixed value. Settling the encoding needs a tag from a spool whose manufacturing date is
known independently.

### Why `0276` is not a field

Under this partition no field equals `0276`. That value appears at `[5, 9)` only because the date field
ends `027` and the supplier ID begins `6`. Two spools delivered together shared date `25027` and differed
only in batch, which is why `0276` appeared on both and reinforced the misreading.

Confirming the `[8, 12)` boundary needs a genuine tag from a spool with a **different manufacturing
date**: if its `[3, 8)` differs in the last three characters, `[5, 9)` cannot be `0276`.

### Reserve byte 40

Genuine tags hold `000000` in the reserve's spool-ID half, then **one non-zero byte at offset 40**, then
seven `0x00` — so everything of unknown purpose sits in `[40, 48)`, past the end of the 40-character
record a printer parses. Observed:

| Tag UID | payload `[0,40)` | byte 40 |
|---|---|---|
| `B3 E9 8C 94` | `3A9250276A21010010C12E1F0165000001000000` | `0x76` |
| `DB 76 83 FB` | `2C5250276A21010010FFFFFF0165000001000000` | `0xC7` |

Established facts:

- It **varies between spools**, so it is not padding — it carries information.
- It is **not derived from the UID**: a spool's two tags have different UIDs and both read `0xC7`. So it
  is a function of the payload or of the product, and is computable in principle.
- **A printer does not validate it.** A tag written with `0x30` (`'0'`) there was accepted. This is why
  community implementations that zero the field work.
- Its algorithm is **unidentified**. Two competing readings fit equally well — a checksum over some range
  of the payload, or a product/SKU code. The decisive test is two spools of the *same* material and
  colour but *different* batch: a checksum would differ, a product code would not.

Identifying it needs more **distinct** payloads. Two samples give only 16 bits of constraint, which is
far too few to pin a CRC — an exhaustive CRC-8 sweep over ~3.1M parameterisations leaves ~62 fits where
chance alone predicts ~48, so such a search is pure noise at this sample size. A third distinct payload
drops the chance-expectation to ~0.19. Further tags from an already-sampled spool add nothing, since a
spool's two tags are byte-identical.

Nothing depends on this: the printer ignores the byte.

## 10. Full Worked Example

Spoolman spool ID `42`, material `01001`, colour `#FF0000`, 1000 g.

| Field | Offset | Value |
|---|---|---|
| Batch number | `[0,3)` | `AB1` |
| Date code | `[3,8)` | `24027` |
| Supplier ID | `[8,12)` | `6A21` |
| Material ID | `[12,17)` | `01001` |
| Colour | `[17,24)` | `0` + `FF0000` |
| Length code | `[24,28)` | `0330` |
| Serial number | `[28,34)` | `000042` |
| Reserve — spool ID | `[34,40)` | `000042` |
| Reserve — trailing | `[40,48)` | `00000000` |
| Padding | `[48,96)` | 48 spaces |

**Part 1** (48 chars → sector 1, encrypted):

```
AB1240276A21010010FF0000033000004200004200000000
```

**Part 2** (48 chars → sector 2, plaintext): 48 spaces.

**Part 1 ciphertext**, AES-128-ECB(K2, part1), to blocks 4/5/6:

```
Block 4: 57 F2 5B 78 07 6D 4C 17 97 B1 BE 35 CA 26 95 40
Block 5: 11 A0 4C 3E 0D 0B DF 51 73 4B 01 1C 49 C7 A9 AB
Block 6: 8B B6 FB 2E F6 77 26 58 4C 4E F2 27 E4 EB 89 7C
```

If this tag were blank beforehand with UID `11 22 33 44`, its derived sector key would be
`16 78 50 90 54 8E` (§5), and after writing blocks 4–6 the sector-1 trailer would be rewritten with that
value as both Key A and Key B, preserving the existing access bits and GPB.

## 11. Read (Decode) Algorithm

1. Read the tag's UID — no authentication needed.
2. If the UID is not 4 bytes, reject the tag as incompatible (§3).
3. Derive the expected sector key from the UID (§5).
4. Probe sector 1: try the derived key, then the default key, **retrying the whole probe with a
   reconnect** before concluding neither works (§8).
   - **Derived key works** → already written by this format. Read blocks 4, 5, 6; concatenate to 48
     bytes; decrypt with K2 (§6) → part 1.
   - **Default key works** → blank tag. There is no part 1 to decode.
   - **Neither** → unrelated key scheme. Report an error; do not interpret as blank.
5. Authenticate sector 2 with the default key; read blocks 8, 9, 10; concatenate to 48 bytes — already
   plaintext → part 2.
6. Concatenate part 1 + part 2 into the 96-character string and slice per §9. Part 2 holds no fields;
   whatever it contains is ignored.

## 12. Write (Encode) Algorithm

1. Assemble the 96-character string per §9, right-padding with spaces to 96.
2. Split into part 1 (first 48) and part 2 (last 48).
3. Encrypt part 1 with K2 (§6) → 48 bytes of ciphertext.
4. Read the UID; reject a non-4-byte UID; derive the sector key (§5).
5. Probe tag state exactly as in read step 4, including the retry. Anything other than "already secured"
   or "blank" is an error — abort without writing.
6. Write the ciphertext to blocks 4, 5, 6.
7. If the tag was blank, rewrite the sector-1 trailer (block 7) to install the derived key as both Key A
   and Key B, preserving access bits and GPB (§8), then re-authenticate sector 1 with the derived key.
   If it was already secured, skip this.
8. Authenticate sector 2 with the default key; write part 2 as plain bytes to blocks 8, 9, 10. Sector 2's
   trailer is never modified.
9. **Read everything back (§11) and compare against what was intended** before reporting success.
   Comparing the whole 96-character payload is simplest and strictest, and catches a corrupted reserve
   field that a field-by-field comparison of user-visible values would miss.

## 13. Reproducible Test Vectors

For unit tests needing no hardware — just AES-128-ECB with no padding:

| Input | Operation | Expected output |
|---|---|---|
| UID `11 22 33 44`, tiled to `11 22 33 44 11 22 33 44 11 22 33 44 11 22 33 44` | AES-128-ECB encrypt with K1 (§5) | `16 78 50 90 54 8E 2C 65 4D 8C AA 04 96 35 5D BD`; derived key = first 6 bytes `16 78 50 90 54 8E` |
| ASCII `AB1240276A21010010FF0000033000004200004200000000` (48 bytes) | AES-128-ECB encrypt with K2 (§6) | `57 F2 5B 78 07 6D 4C 17 97 B1 BE 35 CA 26 95 40 11 A0 4C 3E 0D 0B DF 51 73 4B 01 1C 49 C7 A9 AB 8B B6 FB 2E F6 77 26 58 4C 4E F2 27 E4 EB 89 7C` |
| The ciphertext above | AES-128-ECB decrypt with K2 | Recovers the original 48-byte string exactly |

A genuine-tag payload also makes a valuable regression fixture, since it exercises the cases a
community-derived reading gets wrong — a non-constant batch/date, a high byte in the reserve, and an
all-NUL sector 2:

```
3A925 0276-region... → batch 3A9 | date 25027 | supplier 6A21 | material 01001
colour 0C12E1F | weight 0165 | serial 000001 | reserve "000000" + 0x76 + 0x00×7
sector 2: 0x00 × 48
```

## 14. Validation Status

**Confirmed against genuine Creality tags:**

- The §5 key derivation, on two different tags — a genuine tag authenticates with the key derived from
  its own UID.
- The §8 trailer layout: Key B is the derived key, and the access bits/GPB match what blank tags ship
  with, confirming that preserving bytes 6–9 is correct.
- §2 sector usage, and the §6 payload cipher.
- Field offsets `[17, 34)` — colour, weight bucket and serial land exactly where §9 says.
- Colour encoding, against ground truth: a spool known to be white read `0FFFFFF`, a red one `0C12E1F`.
- Material ID `01001` = Creality "Hyper PLA", against ground truth.
- Weight bucket `0165` = 500 g, against physical 0.5 kg spools.
- That a spool's two tags are byte-identical across the whole payload despite different UIDs.

**Confirmed against a real K2/CFS printer:** a tag written by this app is accepted, including with all of
the following simultaneously differing from a genuine tag — reserve carrying a spool ID, byte 40 as
`'0'`, constant batch and date codes, Creality's supplier ID on a third-party spool, and a sector 2 that
is not all-NUL. That accepted tag carried `k2` in sector 2, where this project writes only spaces — the
one byte difference between the confirmed sequence and what ships.

**Documented by a third-party firmware, not tested here:** that the spool ID is resolved from the
reserve field and the serial ignored, that reserve `0`/`1` mean "no ID", and that a `reserve` greater
than `1` overrides the material/colour fields — all from
[Jacobean's K2 Plus firmware docs](https://jacob10383.github.io/k2-plus-custom-firmware/rfid/) (§9).
Stock Creality firmware may differ; writing both fields costs nothing and covers either.

**Not confirmed:**

- That the printer *displays* every field correctly. Only acceptance is established.
- Whether the printer writes anything back to a tag it has used. Jacobean's firmware documents that it
  does not; stock firmware is untested.
- Whether the printer reads either of a spool's two tags.
- Weight buckets other than `0165`; those four are taken on trust from the reverse-engineered table.
- The `[8, 12)` supplier-field boundary, and the `YYMDD` date encoding (§9).

## 15. Out of Scope Here

- The material catalog itself (which IDs map to which materials) — bundled data, see
  `assets/materials.json`.
- Spoolman-side field mapping: weight rounding, material fallback, supplier-ID choice — see
  REQUIREMENTS.md §4 and §8.
