---
title: NFC Spool Writer
description: Writes filament data to NFC tags for spools you own.
---

# NFC Spool Writer

An Android app that writes filament information from your own
[Spoolman](https://github.com/Donkie/Spoolman) inventory to NFC tags, in the format the Creality K2,
K2 Plus and K2 Max CFS reads.

## Requirements

- An Android phone with an **NXP NFC chipset** (MIFARE Classic support). Phones with Broadcom or
  Qualcomm NFC controllers can detect these tags but cannot authenticate to them — a hardware
  limitation with no software workaround.
- Blank **MIFARE Classic** tags.
- A **Spoolman** instance running on your own network.

## Privacy

The app collects nothing. It stores one thing — the Spoolman address you type — on your device, and
talks to no server other than that one. The [privacy policy](privacy-policy) sets out the detail.

---

*Spoolman is a separate open-source project, also unaffiliated.*
