# Play Store listing copy

Kept in the repo so wording changes are reviewable in a diff rather than living only in the Console.
British spelling throughout, matching the app's own strings (`Colour`, `recognises`) and the
**English (United Kingdom)** default language set at app creation.

Policy note: nothing here uses *bypass, crack, clone, unlock, hack* or *spoof*. The app is described
as writing filament data for spools the user already owns, which is the defensible framing for
Risk 1 (Device and Network Abuse / Intellectual Property) in [STORE_PLAN.md](../STORE_PLAN.md).

---

## App name (≤30 chars)

```
NFC Spool Writer
```

## Short description (≤80 chars — 73 used)

```
Write Spoolman filament data to CFS-readable NFC tags for spools you own.
```

## Full description (≤4000 chars)

```
Write the filament data from your own Spoolman inventory to NFC tags, so your printer's CFS recognises a spool the moment you load it.

HOW IT WORKS

1. Point the app at your Spoolman server.
2. Pick a spool from your inventory — search by material, vendor or location.
3. Review exactly what will be written, including any assumptions the app had to make to fill a field.
4. Hold a blank tag against your phone. The app writes it, then reads it back to verify.

A genuine spool carries two tags holding the same data, so the printer can read one whichever way the spool sits. To match that, the app can write a second tag with identical data without making you choose the spool again.

If a tag already has data on it, the app tells you what it contains and asks before overwriting.

WHAT YOU NEED

Please check all four before installing. Without them the app cannot do anything useful:

• A phone with an NXP NFC chipset. MIFARE Classic is a proprietary NXP protocol, so phones with Broadcom or Qualcomm NFC controllers can detect these tags but cannot authenticate to them. That is a hardware limitation with no software workaround. Google Play filters out incompatible devices automatically, which is why the supported-device list for this app is unusually short.

• Blank MIFARE Classic tags.

• A Spoolman instance reachable on your network. Spoolman is a separate open-source filament inventory that you run yourself. This app ships with no server address and has no server of its own — you enter yours in Settings. Spoolman has no login of its own, so no credentials are needed.

• Android 10 or later.

PRIVACY

No accounts. No analytics. No crash reporting. No advertising. No third-party SDKs of any kind.

The app stores exactly one thing: the Spoolman address you type into Settings. It sends requests to that address and nowhere else, and those requests only ever read — nothing is written back to your server. Nothing about your filament, your tags or your device is sent to the developer or to anyone else.

It requests two Android permissions: NFC, and internet access to reach the server address you supply.

COMPATIBILITY

Works with the Creality K2, K2 Plus and K2 Max CFS.

This app is not affiliated with, endorsed by, or sponsored by Creality. Spoolman is a separate open-source project, also unaffiliated. All product names are the property of their respective owners.

Open source: github.com/mjeanrichard/nfc-spool-writer
```

---

## Notes on choices

- **The requirements section is deliberately blunt and placed high.** The app is inert without an
  NXP phone, tags and a server. Users who discover that after installing leave one-star reviews;
  users who read it first do not install. The device filter already hides most incompatible phones,
  but it cannot filter for "owns MIFARE Classic tags" or "runs Spoolman".
- **The unusually small device count is explained rather than hidden**, so it reads as deliberate
  engineering rather than a broken listing.
- **Read-and-check mode is not mentioned.** It is still in development (`ui/read/`) and does not
  compile yet. Add a line to HOW IT WORKS if it ships in the first release; do not advertise it
  before then.
- **The privacy paragraph doubles as review evidence.** It matches
  [store/console-answers.md](console-answers.md) and [docs/privacy-policy.md](../docs/privacy-policy.md)
  exactly; a reviewer cross-checking the three should find no daylight between them.
