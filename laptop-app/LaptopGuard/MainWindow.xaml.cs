using System.ComponentModel;
using System.Windows;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using LaptopGuard.Models;
using LaptopGuard.Protocol;
using LaptopGuard.Services;

namespace LaptopGuard;

public partial class MainWindow : Window
{
    private readonly ProfileStore _profileStore;
    private readonly PowerGuardService _powerGuard;
    private readonly BleAdvertiserService _bleAdvertiser;
    private readonly PhoneWatcherService _phoneWatcher;
    private readonly TrayIconService _trayIcon;
    private readonly Action _exitCallback;
    private readonly DispatcherTimer _environmentTimer;

    private DeviceProfile _profile;
    private bool _isInitializingUi;
    private bool _isExiting;
    private bool _shownTrayBalloon;
    private bool _isStartingTravel;
    private DateTimeOffset _manualStopCooldownUntil = DateTimeOffset.MinValue;
    private string _latestPayloadHex = string.Empty;

    public MainWindow(
        ProfileStore profileStore,
        PowerGuardService powerGuard,
        BleAdvertiserService bleAdvertiser,
        PhoneWatcherService phoneWatcher,
        TrayIconService trayIcon,
        Action exitCallback)
    {
        InitializeComponent();

        _profileStore = profileStore;
        _powerGuard = powerGuard;
        _bleAdvertiser = bleAdvertiser;
        _phoneWatcher = phoneWatcher;
        _trayIcon = trayIcon;
        _exitCallback = exitCallback;

        _profile = _profileStore.LoadOrCreate();
        _bleAdvertiser.TelemetryUpdated += OnTelemetryUpdated;
        _phoneWatcher.PhonePresenceChanged += OnPhonePresenceChanged;

        _environmentTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(4)
        };
        _environmentTimer.Tick += async (_, _) => await RefreshEnvironmentStatusAsync();

        Loaded += MainWindow_Loaded;
        BindProfileToUi();
    }

    public void MarkExiting() => _isExiting = true;

    public async void ToggleTravelModeFromTray() => await ToggleTravelModeAsync();

    public void TurnScreenOffFromTray() => TurnOffScreenNow();

    private async void MainWindow_Loaded(object sender, RoutedEventArgs e)
    {
        _phoneWatcher.Start(_profile);
        _environmentTimer.Start();
        await RefreshEnvironmentStatusAsync();
    }

    private void BindProfileToUi()
    {
        _isInitializingUi = true;
        try
        {
            DeviceNameTextBox.Text = _profile.DeviceName;
            AutoStartOnPhoneCheckBox.IsChecked = _profile.AutoStartOnPhoneConnect;
            KeepAwakeCheckBox.IsChecked = _profile.KeepAwakeDuringTravel;
            MinimizeToTrayCheckBox.IsChecked = _profile.MinimizeToTrayOnClose;
            LaunchOnStartupCheckBox.IsChecked = PowerGuardService.IsStartupEnabled();

            TelemetryDeviceIdText.Text = _profile.DeviceIdHex.ToUpperInvariant();
            ManualCodeTextBox.Text = PairingCode.BuildManualCode(_profile);
            ProfilePathText.Text = $"Saved permanently at: {_profileStore.FilePath}";

            var qrUri = PairingCode.BuildUri(_profile);
            QrCodeImage.Source = QrImageHelper.GenerateQrBitmap(qrUri);
            UpdatePhonePresenceUi(_phoneWatcher.CurrentPresence);
        }
        finally
        {
            _isInitializingUi = false;
        }
    }

    private async Task RefreshEnvironmentStatusAsync()
    {
        UpdateBatteryBadge(PowerGuardService.GetPowerInfo());
        UpdatePhonePresenceUi(_phoneWatcher.CurrentPresence);

        var (readiness, message) = await BleAdvertiserService.CheckBluetoothReadinessAsync();
        if (readiness == BluetoothReadiness.Ready)
        {
            BluetoothWarningBanner.Visibility = Visibility.Collapsed;
            _phoneWatcher.EnsureRunningIfReady();
        }
        else
        {
            BluetoothWarningText.Text = message;
            BluetoothWarningBanner.Visibility = Visibility.Visible;
        }
    }

    private void UpdateBatteryBadge(LaptopPowerInfo power)
    {
        if (!power.HasBattery)
        {
            BatteryBadgeText.Text = "AC Power";
            return;
        }

        var suffix = power.IsAcConnected ? " (AC)" : string.Empty;
        BatteryBadgeText.Text = $"Battery: {power.BatteryPercent}%{suffix}";
    }

    private async void ToggleTravelButton_Click(object sender, RoutedEventArgs e) =>
        await ToggleTravelModeAsync();

    private async Task ToggleTravelModeAsync()
    {
        if (_bleAdvertiser.IsRunning)
        {
            _manualStopCooldownUntil = DateTimeOffset.UtcNow.AddSeconds(45);
            await StopTravelModeAsync();
            return;
        }

        _manualStopCooldownUntil = DateTimeOffset.MinValue;
        await StartTravelModeAsync(showPopupOnError: true);
    }

    private async Task StartTravelModeAsync(bool showPopupOnError)
    {
        if (_isStartingTravel || _bleAdvertiser.IsRunning)
        {
            return;
        }

        _isStartingTravel = true;
        ToggleTravelButton.IsEnabled = false;
        try
        {
            var (readiness, message) = await BleAdvertiserService.CheckBluetoothReadinessAsync();
            if (readiness is BluetoothReadiness.NoAdapter or BluetoothReadiness.LowEnergyUnsupported or BluetoothReadiness.RadioOff)
            {
                BluetoothWarningText.Text = message;
                BluetoothWarningBanner.Visibility = Visibility.Visible;
                if (showPopupOnError)
                {
                    MessageBox.Show(this, message, "Bluetooth Required", MessageBoxButton.OK, MessageBoxImage.Warning);
                }
                return;
            }

            _powerGuard.SetKeepAwake(_profile.KeepAwakeDuringTravel);
            await _bleAdvertiser.StartAsync(_profile);
            UpdateVisualActiveState(true);
        }
        catch (Exception ex)
        {
            _powerGuard.SetKeepAwake(false);
            UpdateVisualActiveState(false);
            if (showPopupOnError)
            {
                MessageBox.Show(this, $"Could not start Travel Mode:\n{ex.Message}", "Bluetooth Error", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }
        finally
        {
            _isStartingTravel = false;
            ToggleTravelButton.IsEnabled = true;
        }
    }

    private async Task StopTravelModeAsync()
    {
        ToggleTravelButton.IsEnabled = false;
        try
        {
            await _bleAdvertiser.StopAsync();
            _powerGuard.SetKeepAwake(false);
            UpdateVisualActiveState(false);
        }
        finally
        {
            ToggleTravelButton.IsEnabled = true;
        }
    }

    private void OnPhonePresenceChanged(PhonePresence presence)
    {
        Dispatcher.BeginInvoke(async () =>
        {
            UpdatePhonePresenceUi(presence);

            var shouldAutoStart = _profile.AutoStartOnPhoneConnect &&
                                  !_bleAdvertiser.IsRunning &&
                                  (presence.IsPairingHandshake ||
                                   (presence.GuardActive && DateTimeOffset.UtcNow >= _manualStopCooldownUntil));

            if (shouldAutoStart)
            {
                await StartTravelModeAsync(showPopupOnError: false);
            }
        });
    }

    private void UpdatePhonePresenceUi(PhonePresence presence)
    {
        if (presence.IsConnected && presence.LastSeenLocal is { } seen)
        {
            var ageSec = Math.Max(0, (int)Math.Round((DateTimeOffset.Now - seen).TotalSeconds));
            PhoneLinkPillBorder.Background = new SolidColorBrush(Color.FromRgb(12, 41, 31));
            PhoneLinkPillBorder.BorderBrush = new SolidColorBrush(Color.FromRgb(16, 185, 129));
            PhoneLinkDot.Fill = new SolidColorBrush(Color.FromRgb(16, 185, 129));
            PhoneLinkText.Text = $"PHONE CONNECTED ({presence.LastRssi} dBm)";
            PhoneLinkText.Foreground = new SolidColorBrush(Color.FromRgb(167, 243, 208));

            var modeTag = presence.IsPairingHandshake
                ? "Paired!"
                : presence.GuardActive ? "Guard Active" : "Linked";
            var batTag = presence.PhoneBatteryPercent <= 100 ? $" • Phone {presence.PhoneBatteryPercent}%" : string.Empty;
            TelemetryPhoneStatusText.Text = $"{modeTag} • {presence.LastRssi} dBm{batTag} • {ageSec}s ago";
            TelemetryPhoneStatusText.Foreground = new SolidColorBrush(Color.FromRgb(52, 211, 153));
        }
        else if (presence.LastSeenLocal is { } prevSeen)
        {
            PhoneLinkPillBorder.Background = new SolidColorBrush(Color.FromRgb(37, 42, 54));
            PhoneLinkPillBorder.BorderBrush = new SolidColorBrush(Color.FromRgb(59, 66, 84));
            PhoneLinkDot.Fill = new SolidColorBrush(Color.FromRgb(107, 114, 128));
            PhoneLinkText.Text = "PHONE AWAY";
            PhoneLinkText.Foreground = new SolidColorBrush(Color.FromRgb(209, 213, 219));

            TelemetryPhoneStatusText.Text = $"Last seen at {prevSeen:HH:mm:ss} ({presence.LastRssi} dBm)";
            TelemetryPhoneStatusText.Foreground = new SolidColorBrush(Color.FromRgb(156, 163, 175));
        }
        else
        {
            PhoneLinkPillBorder.Background = new SolidColorBrush(Color.FromRgb(37, 42, 54));
            PhoneLinkPillBorder.BorderBrush = new SolidColorBrush(Color.FromRgb(59, 66, 84));
            PhoneLinkDot.Fill = new SolidColorBrush(Color.FromRgb(107, 114, 128));
            PhoneLinkText.Text = "NO PHONE SIGNAL";
            PhoneLinkText.Foreground = new SolidColorBrush(Color.FromRgb(209, 213, 219));

            TelemetryPhoneStatusText.Text = "Waiting for phone scan or Travel Guard...";
            TelemetryPhoneStatusText.Foreground = new SolidColorBrush(Color.FromRgb(156, 163, 175));
        }
    }

    private void OnTelemetryUpdated(BeaconTelemetry telemetry)
    {
        Dispatcher.BeginInvoke(() =>
        {
            UpdateBatteryBadge(telemetry.PowerInfo);
            BeaconStatusDescription.Text = telemetry.StatusMessage;
            TelemetryRotationsText.Text = telemetry.RotationCount.ToString();
            TelemetryLastTimeText.Text = telemetry.LastUpdatedLocal?.ToString("HH:mm:ss") ?? "Not started";

            if (!string.IsNullOrEmpty(telemetry.PayloadHex))
            {
                _latestPayloadHex = telemetry.PayloadHex;
                TelemetryPayloadHexText.Text = FormatHexGroups(telemetry.PayloadHex);
            }

            UpdateVisualActiveState(telemetry.IsBroadcasting || _bleAdvertiser.IsRunning);
        });
    }

    private void UpdateVisualActiveState(bool active)
    {
        _trayIcon.UpdateState(active);

        if (active)
        {
            ToggleTravelButton.Content = "Stop Travel Mode";
            ToggleTravelButton.Background = new SolidColorBrush(Color.FromRgb(185, 28, 28));
            StatusPillBorder.Background = new SolidColorBrush(Color.FromRgb(46, 29, 13));
            StatusPillBorder.BorderBrush = new SolidColorBrush(Color.FromRgb(217, 119, 6));
            StatusDot.Fill = new SolidColorBrush(Color.FromRgb(245, 158, 11));
            StatusPillText.Text = "TRAVEL MODE ACTIVE";
            StatusPillText.Foreground = new SolidColorBrush(Color.FromRgb(253, 230, 138));
        }
        else
        {
            ToggleTravelButton.Content = "Start Travel Mode";
            ToggleTravelButton.Background = new LinearGradientBrush(
                Color.FromRgb(234, 88, 12),
                Color.FromRgb(245, 158, 11),
                new Point(0, 0),
                new Point(1, 0));
            StatusPillBorder.Background = new SolidColorBrush(Color.FromRgb(37, 42, 54));
            StatusPillBorder.BorderBrush = new SolidColorBrush(Color.FromRgb(59, 66, 84));
            StatusDot.Fill = new SolidColorBrush(Color.FromRgb(107, 114, 128));
            StatusPillText.Text = "IDLE";
            StatusPillText.Foreground = new SolidColorBrush(Color.FromRgb(209, 213, 219));
        }
    }

    private void AutoStartOnPhoneCheckBox_Changed(object sender, RoutedEventArgs e)
    {
        if (_isInitializingUi)
        {
            return;
        }

        _profile.AutoStartOnPhoneConnect = AutoStartOnPhoneCheckBox.IsChecked == true;
        _profileStore.Save(_profile);
    }

    private async void KeepAwakeCheckBox_Changed(object sender, RoutedEventArgs e)
    {
        if (_isInitializingUi)
        {
            return;
        }

        _profile.KeepAwakeDuringTravel = KeepAwakeCheckBox.IsChecked == true;
        _profileStore.Save(_profile);

        if (_bleAdvertiser.IsRunning)
        {
            _powerGuard.SetKeepAwake(_profile.KeepAwakeDuringTravel);
            await _bleAdvertiser.RefreshFlagsAsync(_profile);
        }
    }

    private void MinimizeToTrayCheckBox_Changed(object sender, RoutedEventArgs e)
    {
        if (_isInitializingUi)
        {
            return;
        }

        _profile.MinimizeToTrayOnClose = MinimizeToTrayCheckBox.IsChecked == true;
        _profileStore.Save(_profile);
    }

    private void LaunchOnStartupCheckBox_Changed(object sender, RoutedEventArgs e)
    {
        if (_isInitializingUi)
        {
            return;
        }

        try
        {
            PowerGuardService.SetStartupEnabled(LaunchOnStartupCheckBox.IsChecked == true);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, $"Could not update Windows startup setting:\n{ex.Message}", "Startup Setting", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
    }

    private void SaveDeviceName_Click(object sender, RoutedEventArgs e)
    {
        _profile.DeviceName = PairingCode.SanitizeName(DeviceNameTextBox.Text);
        _profileStore.Save(_profile);
        BindProfileToUi();
    }

    private async void ResetPairingKey_Click(object sender, RoutedEventArgs e)
    {
        var confirm = MessageBox.Show(
            this,
            "Resetting the QR key will unpair any mobile phone that already scanned this laptop. You will need to re-scan the new QR code.\n\nContinue?",
            "Reset Permanent QR Key",
            MessageBoxButton.YesNo,
            MessageBoxImage.Warning);

        if (confirm != MessageBoxResult.Yes)
        {
            return;
        }

        var wasRunning = _bleAdvertiser.IsRunning;
        if (wasRunning)
        {
            await _bleAdvertiser.StopAsync();
        }

        _profile = _profileStore.CreateFreshProfile(
            _profile.DeviceName,
            _profile.KeepAwakeDuringTravel,
            _profile.MinimizeToTrayOnClose,
            _profile.AutoStartOnPhoneConnect);
        _profileStore.Save(_profile);
        _phoneWatcher.UpdateProfile(_profile);
        BindProfileToUi();

        if (wasRunning)
        {
            await _bleAdvertiser.StartAsync(_profile);
        }
    }

    private void CopyManualCode_Click(object sender, RoutedEventArgs e)
    {
        Clipboard.SetText(ManualCodeTextBox.Text);
        FlashButtonLabel(CopyCodeButton, "Copied!", "Copy Pairing Code");
    }

    private void CopyPayloadHex_Click(object sender, RoutedEventArgs e)
    {
        if (!string.IsNullOrEmpty(_latestPayloadHex))
        {
            Clipboard.SetText(_latestPayloadHex);
            FlashButtonLabel(CopyPayloadButton, "Copied!", "Copy Hex");
        }
    }

    private async void FlashButtonLabel(System.Windows.Controls.Button button, string temporaryText, string originalText)
    {
        button.Content = temporaryText;
        await Task.Delay(1400);
        if (button.Content as string == temporaryText)
        {
            button.Content = originalText;
        }
    }

    private void TurnScreenOff_Click(object sender, RoutedEventArgs e) => TurnOffScreenNow();

    private void TurnOffScreenNow()
    {
        var handle = new WindowInteropHelper(this).Handle;
        PowerGuardService.TurnOffDisplay(handle);
    }

    private void OpenLidSettings_Click(object sender, RoutedEventArgs e) =>
        PowerGuardService.OpenLidPowerSettings();

    private void OpenBluetoothSettings_Click(object sender, RoutedEventArgs e) =>
        PowerGuardService.OpenBluetoothSettings();

    protected override void OnClosing(CancelEventArgs e)
    {
        base.OnClosing(e);

        if (_isExiting)
        {
            return;
        }

        if (_profile.MinimizeToTrayOnClose)
        {
            e.Cancel = true;
            ShowInTaskbar = false;
            Hide();
            if (!_shownTrayBalloon)
            {
                _shownTrayBalloon = true;
                _trayIcon.ShowTrayHint(
                    "Laptop Guard",
                    _bleAdvertiser.IsRunning
                        ? "Travel Mode stays active in the system tray."
                        : "Minimized to the system tray.");
            }
            return;
        }

        e.Cancel = true;
        _exitCallback();
    }

    private static string FormatHexGroups(string hex)
    {
        return string.Join(' ', Enumerable.Range(0, hex.Length / 2).Select(i => hex.Substring(i * 2, 2)));
    }
}
