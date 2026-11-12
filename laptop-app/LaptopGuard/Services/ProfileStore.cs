using System.IO;
using System.Security.Cryptography;
using System.Text.Json;
using LaptopGuard.Models;
using LaptopGuard.Protocol;

namespace LaptopGuard.Services;

/// <summary>
/// Persists and loads the laptop's permanent pairing identity and user preferences.
/// </summary>
public sealed class ProfileStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true
    };

    private readonly string _filePath;

    public ProfileStore(string? directoryPath = null)
    {
        var baseDir = directoryPath ?? Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "LaptopGuard");
        _filePath = Path.Combine(baseDir, "device-profile.json");
    }

    public string FilePath => _filePath;

    /// <summary>
    /// Loads the existing profile or creates and persists a fresh permanent pairing identity.
    /// </summary>
    public DeviceProfile LoadOrCreate()
    {
        if (File.Exists(_filePath))
        {
            try
            {
                var json = File.ReadAllText(_filePath);
                var loaded = JsonSerializer.Deserialize<DeviceProfile>(json, JsonOptions);
                if (IsValid(loaded))
                {
                    return loaded!;
                }
            }
            catch
            {
                // Corrupt profile falls through to safe regeneration below.
            }
        }

        var created = CreateFreshProfile();
        Save(created);
        return created;
    }

    /// <summary>
    /// Generates a brand-new random device ID and secret key (invalidates prior mobile pairings).
    /// </summary>
    public DeviceProfile CreateFreshProfile(string? deviceName = null, bool keepAwake = true, bool minimizeToTray = true)
    {
        var deviceId = RandomNumberGenerator.GetBytes(BeaconPacket.DeviceIdLength);
        var secret = RandomNumberGenerator.GetBytes(BeaconPacket.SecretLength);

        return new DeviceProfile
        {
            Version = 1,
            DeviceIdHex = Convert.ToHexString(deviceId).ToLowerInvariant(),
            SecretHex = Convert.ToHexString(secret).ToLowerInvariant(),
            DeviceName = PairingCode.SanitizeName(deviceName ?? Environment.MachineName),
            CreatedUtc = DateTimeOffset.UtcNow,
            KeepAwakeDuringTravel = keepAwake,
            MinimizeToTrayOnClose = minimizeToTray
        };
    }

    /// <summary>
    /// Atomically saves the profile to disk.
    /// </summary>
    public void Save(DeviceProfile profile)
    {
        var directory = Path.GetDirectoryName(_filePath)!;
        Directory.CreateDirectory(directory);

        var tempPath = _filePath + ".tmp";
        var json = JsonSerializer.Serialize(profile, JsonOptions);
        File.WriteAllText(tempPath, json);
        File.Move(tempPath, _filePath, overwrite: true);
    }

    private static bool IsValid(DeviceProfile? profile)
    {
        if (profile is null || profile.Version != 1)
        {
            return false;
        }

        return profile.DeviceIdHex.Length == BeaconPacket.DeviceIdLength * 2 &&
               profile.SecretHex.Length == BeaconPacket.SecretLength * 2 &&
               PairingCode.TryParse(PairingCode.BuildUri(profile), out _);
    }
}

