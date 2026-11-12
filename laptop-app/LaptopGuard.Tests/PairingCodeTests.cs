using LaptopGuard.Models;
using LaptopGuard.Protocol;
using Xunit;

namespace LaptopGuard.Tests;

public sealed class PairingCodeTests
{
    private static DeviceProfile SampleProfile(string name = "Ahmad Lenovo") => new()
    {
        Version = 1,
        DeviceIdHex = "0123456789abcdef",
        SecretHex = "00112233445566778899aabbccddeeff",
        DeviceName = name
    };

    [Fact]
    public void BuildUri_And_TryParse_PreservesFields()
    {
        var profile = SampleProfile("Lenovo ThinkBook #1");
        var uri = PairingCode.BuildUri(profile);

        var ok = PairingCode.TryParse(uri, out var parsed);
        Assert.True(ok);
        Assert.Equal(1, parsed.Version);
        Assert.Equal(profile.DeviceIdHex, parsed.DeviceIdHex);
        Assert.Equal(profile.SecretHex, parsed.SecretHex);
        Assert.Equal("Lenovo ThinkBook #1", parsed.DeviceName);
    }

    [Fact]
    public void BuildManualCode_And_TryParse_IgnoresCaseAndWhitespace()
    {
        var profile = SampleProfile();
        var manual = PairingCode.BuildManualCode(profile);
        var messy = $"  {manual.ToLowerInvariant().Replace("-", " - ")}  ";

        var ok = PairingCode.TryParse(messy, out var parsed);
        Assert.True(ok);
        Assert.Equal(profile.DeviceIdHex, parsed.DeviceIdHex);
        Assert.Equal(profile.SecretHex, parsed.SecretHex);
    }

    [Theory]
    [InlineData("")]
    [InlineData("https://example.com/pair")]
    [InlineData("laptopguard://pair?v=2&id=0123456789abcdef&key=00112233445566778899aabbccddeeff")]
    [InlineData("laptopguard://pair?v=1&id=short&key=00112233445566778899aabbccddeeff")]
    [InlineData("LG1-1234")]
    [InlineData("LG2-0123456789abcdef-00112233445566778899aabbccddeeff")]
    public void TryParse_RejectsMalformedInputs(string badInput)
    {
        Assert.False(PairingCode.TryParse(badInput, out _));
    }
}
