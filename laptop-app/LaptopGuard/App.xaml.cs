using System.Threading;
using System.Windows;
using LaptopGuard.Services;

namespace LaptopGuard;

public partial class App : Application
{
    private const string MutexName = @"Local\LaptopGuard_SingleInstance_Mutex";
    private const string ShowEventName = @"Local\LaptopGuard_ShowWindow_Event";

    private Mutex? _singleInstanceMutex;
    private EventWaitHandle? _showWindowEvent;
    private CancellationTokenSource? _activationCts;

    private ProfileStore? _profileStore;
    private PowerGuardService? _powerGuard;
    private BleAdvertiserService? _bleAdvertiser;
    private PhoneWatcherService? _phoneWatcher;
    private TrayIconService? _trayIcon;
    private MainWindow? _mainWindow;
    private bool _isShuttingDown;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        _singleInstanceMutex = new Mutex(true, MutexName, out var isFirstInstance);
        if (!isFirstInstance)
        {
            SignalExistingInstance();
            Shutdown(0);
            return;
        }

        _showWindowEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
        _activationCts = new CancellationTokenSource();
        ListenForActivation(_activationCts.Token);

        _profileStore = new ProfileStore();
        _powerGuard = new PowerGuardService();
        _bleAdvertiser = new BleAdvertiserService();
        _phoneWatcher = new PhoneWatcherService();
        _trayIcon = new TrayIconService();

        _mainWindow = new MainWindow(_profileStore, _powerGuard, _bleAdvertiser, _phoneWatcher, _trayIcon, RequestExit);

        _trayIcon.OpenRequested += () => Dispatcher.Invoke(ShowMainWindow);
        _trayIcon.ToggleTravelRequested += () => Dispatcher.Invoke(() => _mainWindow.ToggleTravelModeFromTray());
        _trayIcon.TurnScreenOffRequested += () => Dispatcher.Invoke(() => _mainWindow.TurnScreenOffFromTray());
        _trayIcon.ExitRequested += () => Dispatcher.Invoke(RequestExit);

        var startMinimized = e.Args.Any(arg =>
            string.Equals(arg, "--minimized", StringComparison.OrdinalIgnoreCase));

        MainWindow = _mainWindow;
        if (startMinimized)
        {
            _mainWindow.ShowInTaskbar = false;
            _mainWindow.WindowState = WindowState.Minimized;
            _mainWindow.Hide();
            _trayIcon.ShowTrayHint("Laptop Guard", "Running in the background. Double-click the tray icon to open.");
        }
        else
        {
            _mainWindow.Show();
        }
    }

    private void ShowMainWindow()
    {
        if (_mainWindow is null)
        {
            return;
        }

        _mainWindow.ShowInTaskbar = true;
        if (!_mainWindow.IsVisible)
        {
            _mainWindow.Show();
        }
        if (_mainWindow.WindowState == WindowState.Minimized)
        {
            _mainWindow.WindowState = WindowState.Normal;
        }
        _mainWindow.Activate();
        _mainWindow.Focus();
    }

    private void ListenForActivation(CancellationToken token)
    {
        Task.Run(() =>
        {
            while (!token.IsCancellationRequested && _showWindowEvent is not null)
            {
                if (_showWindowEvent.WaitOne(500))
                {
                    Dispatcher.BeginInvoke(ShowMainWindow);
                }
            }
        }, token);
    }

    private static void SignalExistingInstance()
    {
        try
        {
            using var ev = EventWaitHandle.OpenExisting(ShowEventName);
            ev.Set();
        }
        catch
        {
            // Existing instance may still be initializing or shutting down.
        }
    }

    private async void RequestExit()
    {
        if (_isShuttingDown)
        {
            return;
        }

        _isShuttingDown = true;
        _mainWindow?.MarkExiting();

        try
        {
            if (_bleAdvertiser is not null)
            {
                await _bleAdvertiser.StopAsync();
                await _bleAdvertiser.DisposeAsync();
            }
        }
        catch
        {
            // Ignore shutdown errors.
        }

        _powerGuard?.Dispose();
        _phoneWatcher?.Dispose();
        _trayIcon?.Dispose();
        _activationCts?.Cancel();
        _activationCts?.Dispose();
        _showWindowEvent?.Dispose();
        _singleInstanceMutex?.ReleaseMutex();
        _singleInstanceMutex?.Dispose();

        Shutdown(0);
    }
}

