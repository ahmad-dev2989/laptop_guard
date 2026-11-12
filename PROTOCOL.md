# Laptop Guard Protocol (v1)

This document defines the shared pairing format and BLE advertisement payload used by both the **Windows Laptop App** (`laptop-app/`) and the **Android Mobile App** (`android-app/`).

## 1. Permanent Pairing Format

When the Windows app runs for the first time, it generates and persists a permanent profile (`%APPDATA%\LaptopGuard\device-profile.json`):
- `deviceId`: 8 random bytes (`16` lowercase hex characters)
- `secret`: 16 random bytes (`32` lowercase hex characters)
- `name`: Friendly laptop name (defaults to machine name)

### QR Code URI
```text
laptopguard://pair?v=1&id=<16-hex-deviceId>&key=<32-hex-secret>&name=<url-encoded-name>
```

### Manual Fallback Code
```text
LG1-<16-hex-deviceId>-<32-hex-secret>
```
Parsers strip whitespace/hyphens inside the hex sections if entered manually.

---

## 2. BLE Manufacturer Advertisement Packet (23 bytes)

The laptop broadcasts a standard legacy BLE advertisement (fits within the 31-byte legacy PDU limit alongside OS flags) containing a single **Manufacturer Specific Data** AD section:

- **Company Identifier (`uint16`):** `0xFFFF`
- **Payload (`23 bytes`):**

| Offset | Size | Field | Description |
|---:|---:|---|---|
| `0` | 2 | `magic` | ASCII `"LG"` (`0x4C, 0x47`) |
| `2` | 1 | `version` | Protocol version `0x01` |
| `3` | 8 | `deviceId` | Persistent 8-byte laptop ID |
| `11` | 1 | `statusFlags` | Bit 0 (`0x01`): Travel Mode Active; Bit 1 (`0x02`): Keep-Awake Enabled; Bit 2 (`0x04`): AC Power Connected |
| `12` | 1 | `batteryPct` | `0..100`, or `0xFF` (`255`) if unavailable |
| `13` | 4 | `nonce` | Random 4-byte nonce rotated periodically (e.g. every 20 seconds) |
| `17` | 6 | `authTag` | First 6 bytes of `HMAC-SHA256(key = secret, message = payload[0..17])` |

### Why this layout?
1. **Survives Windows BLE MAC Randomization:** Windows rotates the advertiser's Bluetooth MAC address, so Android identifies the laptop using the `deviceId` + `authTag` inside the payload rather than the MAC address.
2. **Hardware `ScanFilter` Compatible:** Bytes `0..10` (`magic + version + deviceId`) never change for a paired laptop, so Android can set a hardware `ScanFilter` on `CompanyId = 0xFFFF` with an 11-byte prefix mask—waking reliably even with the phone screen off.
3. **Spoof-Resistant Without Clock Sync:** Because `nonce` is sent inside the packet and covered by `HMAC-SHA256`, no clock synchronization between laptop and phone is required.

