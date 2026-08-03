---
title: Privacy Policy — NFC Spool Writer
description: Privacy policy for the NFC Spool Writer Android app.
---

# Privacy Policy

**App:** NFC Spool Writer (`ch.jeanrichard.nfcspoolwriter`)
**Effective date:** 7 August 2026
**Last updated:** 7 August 2026

## Summary

NFC Spool Writer does not collect, transmit, or share any personal data. There is no account, no
developer-operated server, no analytics, and no advertising. Everything the app stores stays on your
device.

## What the app stores

The app stores exactly one piece of information: **the address of the Spoolman server you type into
Settings**.

It is held in the app's own private storage on your device. It is never sent to the developer or to
any third party. It is used for one purpose — knowing where to send your own requests.

If you have Android's built-in backup enabled, this setting may be included in *your* Google account
backup, so that it is restored when you set up a new phone. That is a function of the Android
operating system acting on your behalf; the developer has no access to it and receives nothing.

You can erase this setting at any time by clearing the app's storage or uninstalling the app.

## Network access

The app makes network requests to **one destination only: the Spoolman server address you supply**.
Spoolman is a separate, open-source filament inventory application that you run yourself, typically
on your own local network.

- The app ships with no server address and no default server.
- There is no backend operated by the developer. No data is sent anywhere else, ever.
- All requests are **read-only**. The app only retrieves information; it never sends your filament
  data, or anything else, to any server.
- The requests are limited to checking that the server is reachable and reading your filament list.

Because the server is yours, any data handling on it is governed by your own setup, not by this app.

## NFC and tags

The app writes filament information to NFC tags that you physically hold against your phone.

- NFC is only active while a screen that has asked you to tap a tag is open.
- Tag contents are used to complete the operation you requested and are then discarded.
- Nothing read from or written to a tag is stored by the app or transmitted anywhere.

## What the app does not do

- No analytics, telemetry, or usage tracking.
- No crash or error reporting to the developer.
- No advertising, and no advertising identifier. The app does not request the `AD_ID` permission.
- No third-party SDKs of any kind.
- No accounts, sign-in, or credentials.
- No access to contacts, location, photos, files, camera, or microphone. The app requests only two
  Android permissions: `NFC` and `INTERNET`.

## Children

The app is a utility for owners of specific 3D-printing hardware. It is not directed at children and
is not designed for or marketed to them.

## Changes to this policy

If this policy changes, the updated version will be published at this address and the "Last updated"
date above will change. Material changes will also be noted in the app's release notes.

## Contact

Questions about this policy or the app:

**apps@jean-richard.ch**

Source code: [github.com/mjeanrichard/nfc-spool-writer](https://github.com/mjeanrichard/nfc-spool-writer)

---

*NFC Spool Writer is not affiliated with, endorsed by, or sponsored by Creality. Spoolman is a
separate open-source project, also unaffiliated.*
