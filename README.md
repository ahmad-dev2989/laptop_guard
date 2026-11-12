# Laptop Guard

**Laptop Guard** helps keep your laptop bag from being left behind while traveling by pairing your Windows laptop permanently with your Android phone via QR code and monitoring live Bluetooth Low Energy (BLE) signal strength—ringing a loud alarm even when your phone is on Silent.

> **Status:**
> - **Phase 1 — Windows Laptop App (`laptop-app/`):** Implemented & packaged (`dist/laptop guard.setup.exe`)
> - **Phase 2 — Android Mobile App (`android-app/`):** Implemented & packaged (`dist/laptop guard mobile.apk`)

## Standalone Setup Files (`dist/`)

Both setup files are fully self-contained and bundle all required runtimes and libraries:

- **Windows Laptop Setup:** `dist/laptop guard.setup.exe` — Installs Laptop Guard cleanly on Windows 10/11 (x64) with Start Menu/Desktop shortcuts and an uninstaller, with no `.NET` runtime installation required.
- **Android Mobile Setup:** `dist/laptop guard mobile.apk` — Installs Laptop Guard on Android 8.0+ (API 26–35) with Jetpack Compose UI, CameraX, and the bundled offline ML Kit QR scanner included inside the single APK.

## Repository Structure

```text
laptop_guard/
├── PROTOCOL.md                     # Shared QR pairing URI + 23-byte BLE beacon specification
├── assets/                         # App icon source and cropped 512x512 square PNG
├── dist/                           # Standalone installers (laptop guard.setup.exe & laptop guard mobile.apk)
├── scripts/
│   └── build-installers.ps1        # Runs tests and builds both standalone installers
├── laptop-app/
│   ├── LaptopGuard.sln
│   ├── LaptopGuard/                # .NET 8 WPF Windows application
│   ├── LaptopGuard.Tests/          # xUnit tests
│   └── installer/LaptopGuard.iss   # Inno Setup 6 installer script
└── android-app/
    ├── app/                        # Kotlin + Jetpack Compose Android application & JUnit tests
    ├── gradlew / gradlew.bat
    └── build.gradle.kts
```

## Rebuilding Everything

```powershell
# Run all Windows & Android tests and regenerate both setup files in dist\
powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\build-installers.ps1"
```
