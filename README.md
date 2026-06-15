# Business Card Scanner

[![Build](https://github.com/merloko/business-card-scanner/actions/workflows/build.yml/badge.svg)](https://github.com/merloko/business-card-scanner/actions/workflows/build.yml)

An offline-first Android app for scanning, storing, and managing business cards — no account, no cloud, no data ever leaves your device.

---

## Features

- **Scan cards** — capture the front and back with your camera or import from the gallery
- **On-device OCR** — powered by ML Kit with bundled models for English, Chinese (Simplified & Traditional), and Japanese; works fully offline
- **Parser debug panel** — see exactly how each OCR line was classified (name, company, phone, email, etc.) and copy the raw text to correct mistakes
- **Search & filter** — find contacts by name, company, or tag; filter by tag chips in the toolbar
- **Contact management** — view, edit, delete; find and merge duplicates
- **Interactions log** — record calls, meetings, emails, and notes against a contact with timestamps
- **Meeting Recorder** — live audio recording with on-device speech-to-text transcript and key-action extraction
- **Follow-up reminders** — schedule notifications to follow up with a contact
- **My Digital Card** — create your own card, generate a QR code, share via NFC tag write
- **Event mode** — batch-tag every card scanned at an event with a single tap
- **Import** — vCard (.vcf) and CSV
- **Export / Backup** — CSV export, full ZIP backup (contacts + scanned card images + raw OCR text), legacy JSON backup also supported for restore
- **Home screen widget** — shows contact count with quick-scan and view-all buttons
- **Theme** — system default, light, or dark

---

## Privacy

- **No INTERNET permission** — the app cannot make network requests
- All OCR and ML processing happens on-device using bundled models
- Contact data is stored only in a local SQLite database in the app's private storage
- Camera, microphone, and NFC are used only for on-device features, never to transmit data
- Backups and exports are always user-initiated and go directly to your chosen app (Files, Drive, email, etc.)
- No analytics, crash reporters, or third-party SDKs that phone home

---

## Requirements

- Android 10+ (API 29)
- Camera required for scanning
- NFC optional — for writing your digital card to NFC tags
- Microphone optional — for the meeting recorder

---

## Building

**Prerequisites:** JDK 17, Android SDK (API 34 platform + build-tools)

```bash
git clone git@github.com:merloko/business-card-scanner.git
cd business-card-scanner
./gradlew assembleDebug          # debug APK → app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # unit tests
```

### Release build

```bash
export KEYSTORE_PASSWORD=<your-keystore-password>
export KEY_ALIAS=<your-key-alias>
export KEY_PASSWORD=<your-key-password>
./gradlew assembleRelease
```

---

## Libraries

| Library | Licence |
|---------|---------|
| [CameraX](https://developer.android.com/training/camerax) | Apache 2.0 |
| [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition) | Google APIs Terms |
| [Room](https://developer.android.com/jetpack/androidx/releases/room) | Apache 2.0 |
| [Glide](https://github.com/bumptech/glide) | Apache 2.0 |
| [ZXing](https://github.com/zxing/zxing) | Apache 2.0 |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Apache 2.0 |
| [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) | Apache 2.0 |

---

## Contributing

Pull requests are welcome. Please keep changes offline-first — no cloud SDKs, no telemetry, no INTERNET permission.

---

## Licence

[GNU Lesser General Public Licence v3.0](LICENSE)
