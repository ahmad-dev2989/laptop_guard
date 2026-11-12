using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Devices.Bluetooth.Advertisement;
using LaptopGuard.Models;
using LaptopGuard.Protocol;

namespace LaptopGuard.Services;

public readonly record struct PhonePresence(
    bool IsConnected,
    short LastRssi,
    byte PhoneBatteryPercent,
    bool GuardActive,
    bool IsPairingHandshake,
    DateTimeOffset? LastSeenLocal);

/// <summary>
/// Listens for signed LA (0xFFFF) BLE heartbeat and pairing packets sent by the paired Android app.
/// </summary>
public sealed class PhoneWatcherService : IDisposable
{
    private static readonly TimeSpan PresenceTimeout = TimeSpan.FromSeconds(18);

    private readonly object _lock = new();
    private BluetoothLEAdvertisementWatcher? _watcher;
    private DeviceProfile? _profile;
    private PhonePresence _lastPresence;

    public event Action<PhonePresence>? PhonePresenceChanged;

    public PhonePresence CurrentPresence
    {
        get
        {
            lock (_lock)
            {
                return ComputeCurrentPresenceLocked();
            }
        }
    }

    public void Start(DeviceProfile profile)
    {
        lock (_lock)
        {
            _profile = profile;
            if (_watcher is not null)
            {
                if (_watcher.Status is BluetoothLEAdvertisementWatcherStatus.Stopped or BluetoothLEAdvertisementWatcherStatus.Aborted)
                {
                    try
                    {
                        _watcher.Start();
                    }
                    catch
                    {
                        // Bluetooth adapter may still be off; retry on periodic check.
                    }
                }
                return;
            }

            var watcher = new BluetoothLEAdvertisementWatcher
            {
                ScanningMode = BluetoothLEScanningMode.Passive
            };
            watcher.Received += OnAdvertisementReceived;
            watcher.Stopped += OnWatcherStopped;
            _watcher = watcher;

            try
            {
                watcher.Start();
            }
            catch
            {
                // Will retry when Bluetooth radio turns on.
            }
        }
    }

    public void UpdateProfile(DeviceProfile profile)
    {
        lock (_lock)
        {
            _profile = profile;
            _lastPresence = default;
        }
        PhonePresenceChanged?.Invoke(default);
    }

    public void EnsureRunningIfReady()
    {
        lock (_lock)
        {
            if (_profile is null || _watcher is null)
            {
                return;
            }

            if (_watcher.Status is BluetoothLEAdvertisementWatcherStatus.Stopped or BluetoothLEAdvertisementWatcherStatus.Aborted)
            {
                try
                {
                    _watcher.Start();
                }
                catch
                {
                    // Radio may still be off.
                }
            }
        }
    }

    private void OnAdvertisementReceived(
        BluetoothLEAdvertisementWatcher sender,
        BluetoothLEAdvertisementReceivedEventArgs args)
    {
        DeviceProfile? profile;
        lock (_lock)
        {
            profile = _profile;
        }

        if (profile is null)
        {
            return;
        }

        foreach (var section in args.Advertisement.ManufacturerData)
        {
            if (section.CompanyId != BeaconPacket.CompanyId)
            {
                continue;
            }

            var bytes = section.Data.ToArray();
            if (!BeaconPacket.TryVerifyAndParse(
                    bytes,
                    profile.GetDeviceIdBytes(),
                    profile.GetSecretBytes(),
                    out var parsed,
                    expectedMagic1: BeaconPacket.PhoneMagic1))
            {
                continue;
            }

            var ackFlags = (PhoneAckFlags)parsed.Flags;
            var updated = new PhonePresence(
                IsConnected: true,
                LastRssi: args.RawSignalStrengthInDBm,
                PhoneBatteryPercent: parsed.BatteryPercent,
                GuardActive: ackFlags.HasFlag(PhoneAckFlags.GuardActive),
                IsPairingHandshake: ackFlags.HasFlag(PhoneAckFlags.PairingHandshake),
                LastSeenLocal: DateTimeOffset.Now);

            lock (_lock)
            {
                _lastPresence = updated;
            }

            PhonePresenceChanged?.Invoke(updated);
            break;
        }
    }

    private void OnWatcherStopped(
        BluetoothLEAdvertisementWatcher sender,
        BluetoothLEAdvertisementWatcherStoppedEventArgs args)
    {
        // Ignore; periodic check in MainWindow will restart watcher when Bluetooth turns back on.
    }

    private PhonePresence ComputeCurrentPresenceLocked()
    {
        if (_lastPresence.LastSeenLocal is not { } seen)
        {
            return default;
        }

        var connected = DateTimeOffset.Now - seen <= PresenceTimeout;
        return _lastPresence with { IsConnected = connected };
    }

    public void Dispose()
    {
        lock (_lock)
        {
            if (_watcher is not null)
            {
                _watcher.Received -= OnAdvertisementReceived;
                _watcher.Stopped -= OnWatcherStopped;
                try
                {
                    if (_watcher.Status == BluetoothLEAdvertisementWatcherStatus.Started)
                    {
                        _watcher.Stop();
                    }
                }
                catch
                {
                    // Ignore stop failures during dispose.
                }
                _watcher = null;
            }
        }
    }
}

