using System.Security.Cryptography;

namespace LaptopGuard.Protocol;

[Flags]
public enum BeaconStatusFlags : byte
{
    None = 0,
    TravelActive = 1 << 0,
    KeepAwakeEnabled = 1 << 1,
    AcPowerConnected = 1 << 2
}

public readonly record struct ParsedBeacon(
    byte Version,
    byte[] DeviceId,
    BeaconStatusFlags Flags,
    byte BatteryPercent,
    byte[] Nonce,
    byte[] AuthTag);

/// <summary>
/// Builds and verifies the 23-byte BLE Manufacturer Specific Data payload defined in PROTOCOL.md.
/// </summary>
public static class BeaconPacket
{
    public const ushort CompanyId = 0xFFFF;
    public const byte ProtocolVersion = 0x01;
    public const byte Magic0 = (byte)'L';
    public const byte Magic1 = (byte)'G';

    public const int DeviceIdLength = 8;
    public const int SecretLength = 16;
    public const int NonceLength = 4;
    public const int AuthTagLength = 6;
    public const int SignedBodyLength = 17; // 2 magic + 1 ver + 8 id + 1 flags + 1 battery + 4 nonce
    public const int PayloadLength = SignedBodyLength + AuthTagLength; // 23 bytes

    /// <summary>
    /// Constructs a signed 23-byte beacon payload for BLE advertising.
    /// </summary>
    public static byte[] BuildPayload(
        ReadOnlySpan<byte> deviceId,
        ReadOnlySpan<byte> secret,
        BeaconStatusFlags flags,
        byte batteryPercent,
        ReadOnlySpan<byte> nonce)
    {
        if (deviceId.Length != DeviceIdLength)
        {
            throw new ArgumentException($"Device ID must be {DeviceIdLength} bytes.", nameof(deviceId));
        }

        if (secret.Length != SecretLength)
        {
            throw new ArgumentException($"Secret must be {SecretLength} bytes.", nameof(secret));
        }

        if (nonce.Length != NonceLength)
        {
            throw new ArgumentException($"Nonce must be {NonceLength} bytes.", nameof(nonce));
        }

        var payload = new byte[PayloadLength];
        payload[0] = Magic0;
        payload[1] = Magic1;
        payload[2] = ProtocolVersion;
        deviceId.CopyTo(payload.AsSpan(3, DeviceIdLength));
        payload[11] = (byte)flags;
        payload[12] = batteryPercent;
        nonce.CopyTo(payload.AsSpan(13, NonceLength));

        Span<byte> mac = stackalloc byte[32];
        HMACSHA256.HashData(secret, payload.AsSpan(0, SignedBodyLength), mac);
        mac[..AuthTagLength].CopyTo(payload.AsSpan(SignedBodyLength, AuthTagLength));

        return payload;
    }

    /// <summary>
    /// Validates magic bytes, version, device ID, and HMAC-SHA256 tag in constant time.
    /// </summary>
    public static bool TryVerifyAndParse(
        ReadOnlySpan<byte> payload,
        ReadOnlySpan<byte> expectedDeviceId,
        ReadOnlySpan<byte> secret,
        out ParsedBeacon parsed)
    {
        parsed = default;

        if (payload.Length != PayloadLength ||
            expectedDeviceId.Length != DeviceIdLength ||
            secret.Length != SecretLength)
        {
            return false;
        }

        if (payload[0] != Magic0 || payload[1] != Magic1 || payload[2] != ProtocolVersion)
        {
            return false;
        }

        var payloadDeviceId = payload.Slice(3, DeviceIdLength);
        if (!CryptographicOperations.FixedTimeEquals(payloadDeviceId, expectedDeviceId))
        {
            return false;
        }

        Span<byte> expectedMac = stackalloc byte[32];
        HMACSHA256.HashData(secret, payload[..SignedBodyLength], expectedMac);

        var actualTag = payload.Slice(SignedBodyLength, AuthTagLength);
        if (!CryptographicOperations.FixedTimeEquals(actualTag, expectedMac[..AuthTagLength]))
        {
            return false;
        }

        parsed = new ParsedBeacon(
            Version: payload[2],
            DeviceId: payloadDeviceId.ToArray(),
            Flags: (BeaconStatusFlags)payload[11],
            BatteryPercent: payload[12],
            Nonce: payload.Slice(13, NonceLength).ToArray(),
            AuthTag: actualTag.ToArray());

        return true;
    }
}
