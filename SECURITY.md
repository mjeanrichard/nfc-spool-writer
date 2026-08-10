# Security Policy

## Reporting a vulnerability

Please report privately, through GitHub's
[private vulnerability reporting](https://github.com/mjeanrichard/nfc-spool-writer/security/advisories/new)
— the **Report a vulnerability** button under the repository's Security tab. That opens a draft
advisory visible only to you and the maintainer.

Please do not open a public issue for a security problem, and please do not include a real Spoolman
address or anything else from your own network in the report.

This is a single-maintainer hobby project, so expect a first response in days rather than hours. You
will get an acknowledgement, an assessment of whether it is in scope, and a fix or an explanation of
why not. Credit in the advisory unless you would rather not be named.

## Supported versions

The most recent release only. There are no maintenance branches, and nothing is backported — before
1.0 in particular, the answer to "is this fixed in an older version" is to update.

## What is in scope

The parts where a bug could plausibly hurt someone:

- Anything that discloses the configured Spoolman address, or the contents of the inventory, to a
  party other than that server.
- Any request the app makes to a host other than the configured Spoolman server. It should talk to
  exactly one address and nowhere else, read-only.
- Any write to the Spoolman server. The app only ever reads from it.
- Crashes, memory corruption or code execution triggered by a **hostile tag** — a tag crafted to
  break the decoder. Tags come from anywhere, and the read & check mode is explicitly for tags of
  unknown provenance, so it must survive whatever it is handed.
- A tag write that damages a tag beyond recovery — for example one that locks a sector by corrupting
  its access bits.
- Anything in the release pipeline that could get unreviewed code, or a substituted artifact, into a
  signed build.

## What is out of scope

These look like findings and are not. They are deliberate, documented decisions, and reports about
them will be closed with a pointer back here:

- **The AES keys embedded in the app are not secrets.** Key derivation and payload encryption use
  fixed constants inherent to the Creality CFS format, documented publicly in
  [TAG_FORMAT_SPEC.md](TAG_FORMAT_SPEC.md) §§5–6. They are not a security boundary and hiding them
  would protect nothing — the format is already reverse-engineered and published. Obfuscation, NDK
  storage or a keystore for these is explicitly not wanted (`NFR-11`).
- **Cleartext HTTP is permitted on purpose.** Spoolman is self-hosted and in practice runs over plain
  HTTP on a LAN. Android blocks cleartext by default at `targetSdk` 28+, and without the exemption
  the app could not reach a typical instance at all (`REQ-09`, `NFR-10`). The threat model assumes a
  trusted local network; if yours is not, put Spoolman behind HTTPS and use that URL.
- **MIFARE Classic's own cryptography is broken.** CRYPTO1 has been publicly defeated since 2008.
  This app cannot fix that — the tag type is dictated by what CFS readers accept (`HW-01`). Anyone
  with physical access to a tag and commodity hardware can read it. Do not treat a CFS tag as
  carrying anything sensitive; it holds filament metadata.
- **Tags can be read and rewritten by other tools.** The tag format is not access control, and it is
  not intended to be.
- **No authentication to Spoolman.** Spoolman has no login of its own, so there is no credential to
  protect (`NFR-12`). If you front it with a reverse proxy that does authenticate, that is currently
  unsupported rather than broken.
- Findings that require a rooted or already-compromised device, a malicious app with equivalent
  permissions already installed, or physical access to an unlocked phone.
- Automated scanner output submitted without a working proof of concept, and reports about
  dependencies with no reachable path in this app.

## How this project is built

Context for anyone assessing the supply chain:

- `master` is protected: every change arrives through a pull request, force-pushes and deletion are
  blocked, and the full build, unit test and lint pass must be green before a merge.
- The release signing key lives in a protected GitHub environment reachable only from `master` or a
  `v*` tag, and every release build stops for a manual approval before it can decode the key.
- Release tags cannot be moved or deleted once pushed.
- Each release build verifies its own artifacts are actually signed, and publishes the APK and AAB
  from the same verified files attached to the GitHub release.
- Secret scanning with push protection and Dependabot security updates are enabled on the repository.

If you find a gap in any of that, it is in scope — see above.
