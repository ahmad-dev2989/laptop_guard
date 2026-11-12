# Laptop Guard

**Laptop Guard** helps keep your laptop bag from being left behind while traveling by pairing your Windows laptop permanently with your Android phone via QR code and monitoring live Bluetooth Low Energy (BLE) signal strength.

> **Status:**
> - **Phase 1 — Windows Laptop App (`laptop-app/`):** Implemented
> - **Phase 2 — Android Mobile App (`android-app/`):** Upcoming

## Repository Structure

```text
laptop_guard/
├── PROTOCOL.md          # Shared QR pairing URI + 23-byte BLE beacon specification
├── assets/              # App icon source and cropped 512x512 square PNG
└── laptop-app/
    ├── LaptopGuard.sln
    ├── LaptopGuard/     # .NET 8 WPF Windows application
    └── LaptopGuard.Tests/
```

## Windows Laptop App Features (`laptop-app/`)

- **Permanent QR Pairing:** Generates and persists a unique `deviceId` and `secret` in `%APPDATA%\LaptopGuard\device-profile.json` and displays a QR code (`laptopguard://pair?...`) plus a manual fallback code (`LG1-...`).
- **BLE Proximity Beacon:** Uses Windows WinRT `BluetoothLEAdvertisementPublisher` to broadcast signed 23-byte Manufacturer Specific Data packets (`Company ID 0xFFFF`, rotated nonce + HMAC-SHA256 tag).
- **Sleep & Bag Protection:** Includes a Keep-Awake option during Travel Mode, one-click **Turn Screen Off Now**, a quick shortcut to Windows Lid-Close Power Settings, optional Windows startup launch (`--minimized`), and system tray background operation.

## Running & Testing the Laptop App

```powershell
# Run unit tests
dotnet test "laptop-app\LaptopGuard.sln"

# Launch the Windows app
dotnet run --project "laptop-app\LaptopGuard\LaptopGuard.csproj"
```

