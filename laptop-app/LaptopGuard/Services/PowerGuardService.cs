using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Microsoft.Win32;

namespace LaptopGuard.Services;

public readonly record struct LaptopPowerInfo(
    byte BatteryPercent,
    bool IsAcConnected,
    bool HasBattery);

/// <summary>
/// Manages Windows wake-lock execution state, battery telemetry, display blanking, and startup registration.
/// </summary>
public sealed class PowerGuardService : IDisposable
{
    private const uint EsContinuous = 0x80000000;
    private const uint EsSystemRequired = 0x00000001;

    private const int WmSysCommand = 0x0112;
    private const int ScMonitorPower = 0xF170;
    private const int MonitorOff = 2;

    private const string StartupRegistryPath = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string StartupValueName = "LaptopGuard";

    private bool _keepAwakeActive;

    public bool IsKeepAwakeActive => _keepAwakeActive;

    /// <summary>
    /// Enables or clears the Windows system execution-state request on the calling thread.
    /// Must be called consistently from the UI dispatcher thread.
    /// </summary>
    public bool SetKeepAwake(bool enable)
    {
        var flags = enable ? (EsContinuous | EsSystemRequired) : EsContinuous;
        var result = SetThreadExecutionState(flags);
        _keepAwakeActive = enable && result != 0;
        return result != 0;
    }

    public static LaptopPowerInfo GetPowerInfo()
    {
        var status = SystemInformation.PowerStatus;
        var hasBattery = !status.BatteryChargeStatus.HasFlag(BatteryChargeStatus.NoSystemBattery);
        byte batteryPct = 255;

        if (hasBattery && status.BatteryLifePercent is >= 0f and <= 1f)
        {
            batteryPct = (byte)Math.Clamp((int)Math.Round(status.BatteryLifePercent * 100f), 0, 100);
        }

        var isAc = status.PowerLineStatus == PowerLineStatus.Online;
        return new LaptopPowerInfo(batteryPct, isAc, hasBattery);
    }

    public static void TurnOffDisplay(nint windowHandle)
    {
        var target = windowHandle != 0 ? windowHandle : (nint)0xFFFF;
        PostMessage(target, WmSysCommand, (nint)ScMonitorPower, (nint)MonitorOff);
    }

    public static void OpenBluetoothSettings() =>
        OpenShellTarget("ms-settings:bluetooth");

    public static void OpenLidPowerSettings()
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = "control.exe",
                Arguments = "powercfg.cpl,,3",
                UseShellExecute = true
            });
        }
        catch
        {
            OpenShellTarget("ms-settings:powersleep");
        }
    }

    public static bool IsStartupEnabled()
    {
        using var key = Registry.CurrentUser.OpenSubKey(StartupRegistryPath, writable: false);
        return key?.GetValue(StartupValueName) is string val && !string.IsNullOrWhiteSpace(val);
    }

    public static void SetStartupEnabled(bool enable)
    {
        using var key = Registry.CurrentUser.CreateSubKey(StartupRegistryPath, writable: true);
        if (key is null)
        {
            return;
        }

        if (!enable)
        {
            key.DeleteValue(StartupValueName, throwOnMissingValue: false);
            return;
        }

        var exePath = ResolveAppExecutablePath();
        key.SetValue(StartupValueName, $"\"{exePath}\" --minimized", RegistryValueKind.String);
    }

    private static string ResolveAppExecutablePath()
    {
        var procPath = Environment.ProcessPath;
        if (!string.IsNullOrWhiteSpace(procPath) &&
            string.Equals(Path.GetFileName(procPath), "LaptopGuard.exe", StringComparison.OrdinalIgnoreCase))
        {
            return procPath;
        }

        return Path.Combine(AppContext.BaseDirectory, "LaptopGuard.exe");
    }

    private static void OpenShellTarget(string target)
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = target,
                UseShellExecute = true
            });
        }
        catch
        {
            // Ignore shell launch errors on restricted systems.
        }
    }

    public void Dispose()
    {
        if (_keepAwakeActive)
        {
            SetKeepAwake(false);
        }
    }

    [DllImport("kernel32.dll")]
    private static extern uint SetThreadExecutionState(uint esFlags);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PostMessage(nint hWnd, int msg, nint wParam, nint lParam);
}
