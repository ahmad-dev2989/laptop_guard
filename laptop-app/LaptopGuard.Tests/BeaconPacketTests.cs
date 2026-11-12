using System.Security.Cryptography;
using LaptopGuard.Protocol;
using Xunit;

namespace LaptopGuard.Tests;

public sealed class BeaconPacketTests
{
    [Fact]
    public void BuildAndVerify_RoundTrip_Succeeds()
    {
        var deviceId = RandomNumberGenerator.GetBytes(BeaconPacket.DeviceIdLength);
        var secret = RandomNumberGenerator.GetBytes(BeaconPacket.SecretLength);
        var nonce = new byte[] { 0x12, 0x34, 0x56, 0x78 };
        var flags = BeaconStatusFlags.TravelActive | BeaconStatusFlags.KeepAwakeEnabled;

        var payload = BeaconPacket.BuildPayload(deviceId, secret, flags, batteryPercent: 84, nonce);

        Assert.Equal(BeaconPacket.PayloadLength, payload.Length);
        Assert.Equal((byte)'L', payload[0]);
        Assert.Equal((byte)'G', payload[1]);
        Assert.Equal(BeaconPacket.ProtocolVersion, payload[2]);

        var ok = BeaconPacket.TryVerifyAndParse(payload, deviceId, secret, out var parsed);
        Assert.True(ok);
        Assert.Equal(BeaconPacket.ProtocolVersion, parsed.Version);
        Assert.Equal(deviceId, parsed.DeviceId);
        Assert.Equal(flags, parsed.Flags);
        Assert.Equal(84, parsed.BatteryPercent);
        Assert.Equal(nonce, parsed.Nonce);
    }

    [Theory]
    [InlineData(0)]  // Magic
    [InlineData(2)]  // Version
    [InlineData(5)]  // DeviceId
    [InlineData(11)] // Flags
    [InlineData(12)] // Battery
    [InlineData(14)] // Nonce
    [InlineData(20)] // AuthTag
    public void TryVerifyAndParse_RejectsTamperedByte(int byteIndex)
    {
        var deviceId = RandomNumberGenerator.GetBytes(BeaconPacket.DeviceIdLength);
        var secret = RandomNumberGenerator.GetBytes(BeaconPacket.SecretLength);
        var nonce = RandomNumberGenerator.GetBytes(BeaconPacket.NonceLength);

        var payload = BeaconPacket.BuildPayload(deviceId, secret, BeaconStatusFlags.TravelActive, 50, nonce);
        payload[byteIndex] ^= 0xFF;

        var ok = BeaconPacket.TryVerifyAndParse(payload, deviceId, secret, out _);
        Assert.False(ok);
    }

    [Fact]
    public void TryVerifyAndParse_RejectsWrongSecretOrWrongLength()
    {
        var deviceId = RandomNumberGenerator.GetBytes(BeaconPacket.DeviceIdLength);
        var secret = RandomNumberGenerator.GetBytes(BeaconPacket.SecretLength);
        var wrongSecret = RandomNumberGenerator.GetBytes(BeaconPacket.SecretLength);
        var nonce = RandomNumberGenerator.GetBytes(BeaconPacket.NonceLength);

        var payload = BeaconPacket.BuildPayload(deviceId, secret, BeaconStatusFlags.TravelActive, 99, nonce);

        Assert.False(BeaconPacket.TryVerifyAndParse(payload, deviceId, wrongSecret, out _));
        Assert.False(BeaconPacket.TryVerifyAndParse(payload[..^1], deviceId, secret, out _));
    }
}

