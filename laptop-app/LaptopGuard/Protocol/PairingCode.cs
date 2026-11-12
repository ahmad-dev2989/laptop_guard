using System.Text.RegularExpressions;
using LaptopGuard.Models;

namespace LaptopGuard.Protocol;

public readonly record struct ParsedPairing(
    int Version,
    string DeviceIdHex,
    string SecretHex,
    string DeviceName);

/// <summary>
/// Encodes and parses QR pairing URIs and manual fallback pairing codes.
/// </summary>
public static partial class PairingCode
{
    public const string Scheme = "laptopguard";
    public const string Host = "pair";

    /// <summary>
    /// Builds the compact QR code URI scanned by the mobile app.
    /// </summary>
    public static string BuildUri(DeviceProfile profile)
    {
        var safeName = SanitizeName(profile.DeviceName);
        return $"{Scheme}://{Host}?v={profile.Version}&id={profile.DeviceIdHex.ToLowerInvariant()}&key={profile.SecretHex.ToLowerInvariant()}&name={Uri.EscapeDataString(safeName)}";
    }

    /// <summary>
    /// Builds a copy-friendly manual pairing code for entry without a camera.
    /// </summary>
    public static string BuildManualCode(DeviceProfile profile) =>
        $"LG1-{profile.DeviceIdHex.ToUpperInvariant()}-{profile.SecretHex.ToUpperInvariant()}";

    /// <summary>
    /// Parses either a QR pairing URI or an LG1-... manual fallback code.
    /// </summary>
    public static bool TryParse(string? rawInput, out ParsedPairing parsed)
    {
        parsed = default;
        if (string.IsNullOrWhiteSpace(rawInput))
        {
            return false;
        }

        var trimmed = rawInput.Trim();
        if (trimmed.StartsWith($"{Scheme}://", StringComparison.OrdinalIgnoreCase))
        {
            return TryParseUri(trimmed, out parsed);
        }

        return TryParseManualCode(trimmed, out parsed);
    }

    private static bool TryParseUri(string rawUri, out ParsedPairing parsed)
    {
        parsed = default;
        if (!Uri.TryCreate(rawUri, UriKind.Absolute, out var uri) ||
            !string.Equals(uri.Scheme, Scheme, StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(uri.Host, Host, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }

        var query = ParseQueryString(uri.Query);
        if (!query.TryGetValue("v", out var versionStr) ||
            !int.TryParse(versionStr, out var version) ||
            version != 1)
        {
            return false;
        }

        if (!query.TryGetValue("id", out var idHex) ||
            !query.TryGetValue("key", out var secretHex) ||
            !IsValidHex(idHex, BeaconPacket.DeviceIdLength * 2) ||
            !IsValidHex(secretHex, BeaconPacket.SecretLength * 2))
        {
            return false;
        }

        var name = query.TryGetValue("name", out var rawName) && !string.IsNullOrWhiteSpace(rawName)
            ? SanitizeName(rawName)
            : "My Laptop";

        parsed = new ParsedPairing(
            Version: 1,
            DeviceIdHex: idHex.ToLowerInvariant(),
            SecretHex: secretHex.ToLowerInvariant(),
            DeviceName: name);
        return true;
    }

    private static bool TryParseManualCode(string code, out ParsedPairing parsed)
    {
        parsed = default;
        var compact = WhitespaceAndDashesRegex().Replace(code, string.Empty).ToUpperInvariant();
        if (!compact.StartsWith("LG1", StringComparison.Ordinal))
        {
            return false;
        }

        var hexPart = compact[3..];
        const int expectedLen = (BeaconPacket.DeviceIdLength + BeaconPacket.SecretLength) * 2;
        if (hexPart.Length != expectedLen || !IsValidHex(hexPart, expectedLen))
        {
            return false;
        }

        var idHex = hexPart[..(BeaconPacket.DeviceIdLength * 2)].ToLowerInvariant();
        var secretHex = hexPart[(BeaconPacket.DeviceIdLength * 2)..].ToLowerInvariant();

        parsed = new ParsedPairing(1, idHex, secretHex, "My Laptop");
        return true;
    }

    private static Dictionary<string, string> ParseQueryString(string query)
    {
        var result = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        var span = query.TrimStart('?');
        foreach (var part in span.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var eqIndex = part.IndexOf('=');
            if (eqIndex <= 0)
            {
                continue;
            }

            var key = Uri.UnescapeDataString(part[..eqIndex]);
            var val = Uri.UnescapeDataString(part[(eqIndex + 1)..]);
            result[key] = val;
        }

        return result;
    }

    private static bool IsValidHex(string value, int expectedLength)
    {
        if (value.Length != expectedLength)
        {
            return false;
        }

        foreach (var ch in value)
        {
            var isHex = (ch is >= '0' and <= '9') || (ch is >= 'a' and <= 'f') || (ch is >= 'A' and <= 'F');
            if (!isHex)
            {
                return false;
            }
        }

        return true;
    }

    public static string SanitizeName(string? name)
    {
        var cleaned = (name ?? string.Empty).Trim();
        if (string.IsNullOrEmpty(cleaned))
        {
            return "My Laptop";
        }

        return cleaned.Length > 32 ? cleaned[..32] : cleaned;
    }

    [GeneratedRegex(@"[\s-]+")]
    private static partial Regex WhitespaceAndDashesRegex();
}

