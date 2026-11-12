using System.IO;
using LaptopGuard.Services;
using Xunit;

namespace LaptopGuard.Tests;

public sealed class ProfileStoreTests : IDisposable
{
    private readonly string _tempDir;

    public ProfileStoreTests()
    {
        _tempDir = Path.Combine(Path.GetTempPath(), "LaptopGuardTests_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_tempDir);
    }

    [Fact]
    public void LoadOrCreate_PersistsSameIdentityAcrossInstances()
    {
        var store1 = new ProfileStore(_tempDir);
        var first = store1.LoadOrCreate();

        var store2 = new ProfileStore(_tempDir);
        var second = store2.LoadOrCreate();

        Assert.Equal(first.DeviceIdHex, second.DeviceIdHex);
        Assert.Equal(first.SecretHex, second.SecretHex);
        Assert.Equal(16, first.DeviceIdHex.Length);
        Assert.Equal(32, first.SecretHex.Length);
    }

    [Fact]
    public void LoadOrCreate_RecoversFromCorruptJson()
    {
        var store = new ProfileStore(_tempDir);
        File.WriteAllText(store.FilePath, "{ not valid json");

        var recovered = store.LoadOrCreate();
        Assert.Equal(16, recovered.DeviceIdHex.Length);
        Assert.Equal(32, recovered.SecretHex.Length);
    }

    [Fact]
    public void CreateFreshProfile_GeneratesDistinctIdentity()
    {
        var store = new ProfileStore(_tempDir);
        var first = store.LoadOrCreate();
        var fresh = store.CreateFreshProfile("Travel Laptop", keepAwake: false, minimizeToTray: false);

        Assert.NotEqual(first.DeviceIdHex, fresh.DeviceIdHex);
        Assert.NotEqual(first.SecretHex, fresh.SecretHex);
        Assert.Equal("Travel Laptop", fresh.DeviceName);
        Assert.False(fresh.KeepAwakeDuringTravel);
        Assert.False(fresh.MinimizeToTrayOnClose);
    }

    public void Dispose()
    {
        if (Directory.Exists(_tempDir))
        {
            Directory.Delete(_tempDir, recursive: true);
        }
    }
}
