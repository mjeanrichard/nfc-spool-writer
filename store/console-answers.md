# Play Console — App content declarations

Answers for every section of **App content**, with the code evidence behind each one. Kept in the
repo so a future change that invalidates an answer shows up in review rather than silently making a
filed declaration untrue.

Verified against the working tree on 2026-08-07.

---

## Evidence base

| Claim | How it was verified |
|---|---|
| Two permissions only, no `AD_ID` | [AndroidManifest.xml:5-6](../src/app/src/main/AndroidManifest.xml#L5-L6) — `NFC`, `INTERNET` |
| No analytics, ads, crash reporting or tracking SDK | Full dependency list in [build.gradle.kts](../src/app/build.gradle.kts) is AndroidX + Ktor + kotlinx only |
| One outbound call site, GET-only | [SpoolmanApiClient.kt:105](../src/app/src/main/java/ch/jeanrichard/nfcspoolwriter/data/spoolman/SpoolmanApiClient.kt#L105) — `httpClient.get(url)`; endpoints are `GET /api/v1/health`, `/api/v1/spool`, `/api/v1/spool/{id}` |
| Destination host is user-supplied, no developer backend | [SettingsRepository.kt](../src/app/src/main/java/ch/jeanrichard/nfcspoolwriter/data/settings/SettingsRepository.kt) — no default URL is shipped |
| One persisted value, local only | `stringPreferencesKey("spoolman_base_url")` is the only DataStore key; no file, DB or SharedPreferences writes anywhere |
| No accounts or credentials | Spoolman has no auth of its own; the app has no login screen |

---

## Privacy policy

- **URL** → `https://mjeanrichard.github.io/nfc-spool-writer/privacy-policy`

  Source: [docs/privacy-policy.md](../docs/privacy-policy.md), served by GitHub Pages from the
  `docs/` folder on the default branch. **Confirm the exact URL resolves in a private browser window
  before filing it** — Pages needs enabling in repository settings first, and the extensionless form
  depends on how Pages serves the built file.

## App access

**Select: "All or some functionality is restricted."**

There are no accounts, so "available without special access" is tempting and is what the app's
*authentication* model suggests. Choose "restricted" anyway: it is the only branch that gives a free
text field, and a reviewer who cannot see the app work will otherwise record it as broken. The
restriction is real — it is hardware and network, not credentials — and the instructions say so.

Instructions to paste:

> This app has no accounts, logins, subscriptions or paywalls. No credentials are needed.
>
> It cannot, however, demonstrate its main function on a standard review device, for two reasons:
>
> 1. HARDWARE. Writing tags requires a phone with an NXP NFC chipset (MIFARE Classic support) and
>    physical MIFARE Classic tags. Phones with Broadcom or Qualcomm NFC controllers can detect such a
>    tag but cannot authenticate to it — this is a hardware limitation with no software workaround.
>    The app declares `<uses-feature android:name="com.nxp.mifare" android:required="true" />`, which
>    is why its supported-device count is unusually low. That is deliberate.
>
> 2. NETWORK. The filament list is read from Spoolman, an open-source inventory server
>    (https://github.com/Donkie/Spoolman) that the user runs on their own network. The app ships with
>    no server address and has no developer-operated backend; the user types their own address in
>    Settings.
>
> Without both, the app opens with an empty list and an explanatory message pointing to Settings.
> That is the designed behaviour for an unconfigured install, not a crash.
>
> A demo video showing a complete write against a real tag and a real Spoolman server: [URL]

## Ads

- **Does your app contain ads?** → **No.**

## Content ratings

- **Category** → **Utility, Productivity, Communication or Other.**
- Every questionnaire item (violence, sexuality, language, controlled substances, gambling,
  user-generated content, user interaction, location sharing, personal info sharing) → **No.**
- Expected outcome: **Everyone / PEGI 3.**

## Target audience and content

- **Target age groups** → **18 and over**, only.
  Selecting any group under 13 pulls the app into the Families policy, with its extra requirements
  and review scrutiny. There is nothing here aimed at children, and the app needs a 3D printer and a
  self-hosted server, so 18+ is honest rather than merely convenient.
- **Could your app be unintentionally appealing to children?** → **No.**
- **Store presence / designed for families** → **No.**

## Data safety

- **Does your app collect or share any of the required user data types?** → **No.**

  This is the answer that collapses the entire follow-up tree, so it is worth being precise about
  why it is correct. Play defines *collection* as transmitting data off the device to the developer
  or a third party. The app does neither:
  - The only stored value is the Spoolman address the user types. It never leaves the device except
    as the destination of the user's own requests.
  - Requests go solely to that user-supplied host and are read-only `GET`s.
  - Nothing about scanned or written tags is stored or transmitted.
  - There is no developer server to send anything to.

- **Advertising ID** → **not used.** The `AD_ID` permission is absent from the manifest.

**Android Backup is not a "No" breaker.** `allowBackup="true"` means the Spoolman URL can ride along
in the user's own Google device backup. That is an OS-level user backup, not developer data
collection, and does not change this answer. See
[backup_rules.xml](../src/app/src/main/res/xml/backup_rules.xml).

## Remaining sections

| Section | Answer |
|---|---|
| News app | No |
| COVID-19 contact tracing or status | No |
| Government app | No |
| Financial features | No — none of them |
| Health apps | No |
| Account deletion | N/A — the app has no accounts |

---

## Cross-check before submitting

The answers above are true of the working tree as verified. Re-check if any of these change:

- A crash-reporting or analytics SDK is added → Data safety is no longer "No".
- A non-`GET` request or a developer-operated endpoint appears → Data safety changes.
- A second DataStore key holding anything user-identifying is added.
- The `com.nxp.mifare` requirement is relaxed → the App access text stops being accurate.
