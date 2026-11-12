namespace LaptopGuard.Models;

/// <summary>
/// Persistent pairing identity and travel behavior preferences for this laptop.
/// </summary>
public sealed class DeviceProfile
{
    public int Version { get; init; } = 1;
    public string DeviceIdHex { get; init; } = string.Empty;
    public string SecretHex { get; init; } = string.Empty;
    public string DeviceName { get; set; } = Environment.MachineName;
    public DateTimeOffset CreatedUtc { get; init; } = DateTimeOffset.UtcNow;
    public bool KeepAwakeDuringTravel { get; set; } = true;
    public bool MinimizeToTrayOnClose { get; set; } = true;
    public bool AutoStartOnPhoneConnect { get; set; } = true;

    public byte[] GetDeviceIdBytes() => Convert.FromHexString(DeviceIdHex);

    public byte[] GetSecretBytes() => Convert.FromHexString(SecretHex);
}

