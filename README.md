# Business Card Scanner

An offline-first Android app for scanning, storing, and searching business cards — no account, no cloud, no data leaves your device.

## Features

- **Scan & OCR** — capture cards with your camera; ML Kit extracts names, companies, phones, emails, and addresses on-device
- **CJK support** — recognises Chinese, Japanese, and Korean business cards
- **Contact management** — search, edit, tag, and merge duplicate contacts
- **My Digital Card** — store your own card, share it as a vCard, QR code, or write it to an NFC tag
- **Import / Export** — import vCard (.vcf) and CSV files; export all contacts as a vCard bundle
- **Backup & Restore** — JSON backup for full local restore
- **Meeting Recorder** — record and transcribe meeting notes and attach them to a contact
- **Reminders** — set follow-up reminders per contact
- **Home screen widget** — shows contact count with quick-scan and view-all buttons
- **Dark mode** — follows system theme or can be set manually

## Privacy

All processing is done entirely on-device.

- No internet permission — the app cannot make network requests
- No account or sign-in required
- No analytics, crash reporters, or third-party SDKs that phone home
- Contact data is stored in a local Room database in the app's private storage
- Camera, microphone, and NFC are used only for on-device features, never to transmit data
- Backups and exports are always user-initiated and go directly to your chosen app

## Requirements

- Android 10 (API 29) or higher
- Camera for scanning
- NFC (optional) for writing cards to NFC tags
- Microphone (optional) for meeting recorder

## Building

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer, **or** JDK 17 + Android SDK
- Android SDK with build-tools 34 and platform 34

### Debug build

```bash
git clone https://github.com/merloko/business-card-scanner.git
cd business-card-scanner
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/
```

### Release build

Set the following environment variables before building:

```bash
export KEYSTORE_PASSWORD=<your keystore password>
export KEY_ALIAS=<your key alias>
export KEY_PASSWORD=<your key password>
./gradlew assembleRelease
```

### Run tests

```bash
./gradlew test
```

## Libraries

| Library | License |
|---|---|
| [CameraX](https://developer.android.com/training/camerax) | Apache 2.0 |
| [ML Kit Text Recognition](https://developers.google.com/ml-kit/vision/text-recognition) | Google APIs Terms |
| [Room](https://developer.android.com/jetpack/androidx/releases/room) | Apache 2.0 |
| [Glide](https://github.com/bumptech/glide) | Apache 2.0 |
| [ZXing](https://github.com/zxing/zxing) | Apache 2.0 |
| [Material Components for Android](https://github.com/material-components/material-components-android) | Apache 2.0 |

