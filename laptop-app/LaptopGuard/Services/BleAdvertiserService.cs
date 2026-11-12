using System.Security.Cryptography;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Radios;
using Windows.Storage.Streams;
using LaptopGuard.Models;
using LaptopGuard.Protocol;

namespace LaptopGuard.Services;

public enum BluetoothReadiness
{
    Unknown,
    Ready,
    RadioOff,
    NoAdapter,
    LowEnergyUnsupported
}

public readonly record struct BeaconTelemetry(
    bool IsBroadcasting,
    BluetoothLEAdvertisementPublisherStatus PublisherStatus,
    BluetoothError LastError,
    string StatusMessage,
    string PayloadHex,
    long RotationCount,
    DateTimeOffset? LastUpdatedLocal,
    LaptopPowerInfo PowerInfo);

/// <summary>
/// Broadcasts the signed 23-byte Laptop Guard BLE beacon via Windows WinRT Advertisement Publisher.
/// </summary>
public sealed class BleAdvertiserService : IAsyncDisposable
{
    private static readonly TimeSpan NonceRotationInterval = TimeSpan.FromSeconds(25);

    private readonly SemaphoreSlim _gate = new(1, 1);
    private BluetoothLEAdvertisementPublisher? _publisher;
    private CancellationTokenSource? _rotationCts;
    private Task? _rotationTask;
    private DeviceProfile? _activeProfile;

    private long _rotationCount;
    private string _lastPayloadHex = string.Empty;
    private DateTimeOffset? _lastUpdatedLocal;
    private BluetoothError _lastError = BluetoothError.Success;
    private BluetoothLEAdvertisementPublisherStatus _publisherStatus = BluetoothLEAdvertisementPublisherStatus.Stopped;
    private string _statusMessage = "Idle — ready when you start Travel Mode.";

    public event Action<BeaconTelemetry>? TelemetryUpdated;

    public bool IsRunning => _activeProfile is not null && _rotationCts is not null;

    /// <summary>
    /// Checks whether the laptop has a Bluetooth LE radio and whether it is currently powered on.
    /// </summary>
    public static async Task<(BluetoothReadiness Readiness, string Message)> CheckBluetoothReadinessAsync()
    {
        try
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter is null)
            {
                return (BluetoothReadiness.NoAdapter, "No Bluetooth adapter found on this PC.");
            }

            if (!adapter.IsLowEnergySupported)
            {
                return (BluetoothReadiness.LowEnergyUnsupported, "This Bluetooth adapter does not support Bluetooth Low Energy (BLE).");
            }

            var radios = await Radio.GetRadiosAsync();
            var btRadio = radios.FirstOrDefault(r => r.Kind == RadioKind.Bluetooth);
            if (btRadio is not null && btRadio.State != RadioState.On)
            {
                return (BluetoothReadiness.RadioOff, "Windows Bluetooth is turned OFF. Turn it on to start Travel Mode.");
            }

            return (BluetoothReadiness.Ready, "Bluetooth LE ready.");
        }
        catch (Exception ex)
        {
            return (BluetoothReadiness.Unknown, $"Could not check Bluetooth status: {ex.Message}");
        }
    }

    public async Task StartAsync(DeviceProfile profile)
    {
        await _gate.WaitAsync();
        try
        {
            await StopInternalAsync();

            _activeProfile = profile;
            _rotationCount = 0;
            _lastError = BluetoothError.Success;

            PublishCurrentPacket(profile);

            _rotationCts = new CancellationTokenSource();
            var token = _rotationCts.Token;
            _rotationTask = Task.Run(() => RotationLoopAsync(token), token);
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task RefreshFlagsAsync(DeviceProfile profile)
    {
        await _gate.WaitAsync();
        try
        {
            if (_activeProfile is null)
            {
                return;
            }

            _activeProfile = profile;
            PublishCurrentPacket(profile);
        }
        finally
        {
            _gate.Release();
        }
    }

    public async Task StopAsync()
    {
        await _gate.WaitAsync();
        try
        {
            await StopInternalAsync();
            _statusMessage = "Idle — Travel Mode stopped.";
            EmitTelemetry();
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task RotationLoopAsync(CancellationToken token)
    {
        using var timer = new PeriodicTimer(NonceRotationInterval);
        try
        {
            while (await timer.WaitForNextTickAsync(token))
            {
                await _gate.WaitAsync(token);
                try
                {
                    if (_activeProfile is not null)
                    {
                        PublishCurrentPacket(_activeProfile);
                    }
                }
                finally
                {
                    _gate.Release();
                }
            }
        }
        catch (OperationCanceledException)
        {
            // Expected on stop/dispose.
        }
    }

    private void PublishCurrentPacket(DeviceProfile profile)
    {
        StopPublisherOnly();

        var power = PowerGuardService.GetPowerInfo();
        var flags = BeaconStatusFlags.TravelActive;
        if (profile.KeepAwakeDuringTravel)
        {
            flags |= BeaconStatusFlags.KeepAwakeEnabled;
        }
        if (power.IsAcConnected)
        {
            flags |= BeaconStatusFlags.AcPowerConnected;
        }

        var nonce = RandomNumberGenerator.GetBytes(BeaconPacket.NonceLength);
        var payload = BeaconPacket.BuildPayload(
            profile.GetDeviceIdBytes(),
            profile.GetSecretBytes(),
            flags,
            power.BatteryPercent,
            nonce);

        var writer = new DataWriter();
        writer.WriteBytes(payload);

        var manufacturerData = new BluetoothLEManufacturerData(BeaconPacket.CompanyId, writer.DetachBuffer());
        var publisher = new BluetoothLEAdvertisementPublisher();
        publisher.Advertisement.ManufacturerData.Add(manufacturerData);
        publisher.StatusChanged += OnPublisherStatusChanged;

        _publisher = publisher;
        _rotationCount++;
        _lastPayloadHex = Convert.ToHexString(payload);
        _lastUpdatedLocal = DateTimeOffset.Now;
        _statusMessage = "Starting BLE beacon broadcast...";

        publisher.Start();
        _publisherStatus = publisher.Status;
        if (_publisherStatus == BluetoothLEAdvertisementPublisherStatus.Started)
        {
            _statusMessage = "Broadcasting BLE beacon — phone can track signal strength.";
        }

        EmitTelemetry(power);
    }

    private void OnPublisherStatusChanged(
        BluetoothLEAdvertisementPublisher sender,
        BluetoothLEAdvertisementPublisherStatusChangedEventArgs args)
    {
        if (!ReferenceEquals(sender, _publisher))
        {
            return;
        }

        _publisherStatus = args.Status;
        _lastError = args.Error;
        _statusMessage = FormatStatusMessage(args.Status, args.Error);
        EmitTelemetry();
    }

    private static string FormatStatusMessage(
        BluetoothLEAdvertisementPublisherStatus status,
        BluetoothError error) =>
        status switch
        {
            BluetoothLEAdvertisementPublisherStatus.Started =>
                "Broadcasting BLE beacon — phone can track signal strength.",
            BluetoothLEAdvertisementPublisherStatus.Waiting =>
                "Waiting for Bluetooth adapter to start broadcasting...",
            BluetoothLEAdvertisementPublisherStatus.Stopping =>
                "Stopping BLE beacon...",
            BluetoothLEAdvertisementPublisherStatus.Stopped =>
                error == BluetoothError.Success
                    ? "Travel Mode stopped."
                    : $"Stopped ({error}).",
            BluetoothLEAdvertisementPublisherStatus.Aborted =>
                error switch
                {
                    BluetoothError.RadioNotAvailable or BluetoothError.DisabledByUser =>
                        "Bluetooth radio is OFF or unavailable. Turn on Bluetooth in Windows Settings.",
                    BluetoothError.NotSupported =>
                        "This Bluetooth adapter does not support BLE advertising.",
                    _ => $"Broadcast aborted ({error})."
                },
            _ => status.ToString()
        };

    private async Task StopInternalAsync()
    {
        if (_rotationCts is not null)
        {
            await _rotationCts.CancelAsync();
            _rotationCts.Dispose();
            _rotationCts = null;
        }

        if (_rotationTask is not null)
        {
            try
            {
                await _rotationTask;
            }
            catch
            {
                // Ignored during shutdown.
            }
            _rotationTask = null;
        }

        StopPublisherOnly();
        _activeProfile = null;
        _publisherStatus = BluetoothLEAdvertisementPublisherStatus.Stopped;
    }

    private void StopPublisherOnly()
    {
        if (_publisher is null)
        {
            return;
        }

        _publisher.StatusChanged -= OnPublisherStatusChanged;
        try
        {
            if (_publisher.Status is BluetoothLEAdvertisementPublisherStatus.Started or BluetoothLEAdvertisementPublisherStatus.Waiting)
            {
                _publisher.Stop();
            }
        }
        catch
        {
            // Ignore errors while tearing down an old publisher instance.
        }

        _publisher = null;
    }

    private void EmitTelemetry(LaptopPowerInfo? powerOverride = null)
    {
        var power = powerOverride ?? PowerGuardService.GetPowerInfo();
        var isBroadcasting = _publisherStatus == BluetoothLEAdvertisementPublisherStatus.Started;
        TelemetryUpdated?.Invoke(new BeaconTelemetry(
            IsBroadcasting: isBroadcasting,
            PublisherStatus: _publisherStatus,
            LastError: _lastError,
            StatusMessage: _statusMessage,
            PayloadHex: _lastPayloadHex,
            RotationCount: _rotationCount,
            LastUpdatedLocal: _lastUpdatedLocal,
            PowerInfo: power));
    }

    public async ValueTask DisposeAsync()
    {
        await _gate.WaitAsync();
        try
        {
            await StopInternalAsync();
        }
        finally
        {
            _gate.Release();
            _gate.Dispose();
        }
    }
}

