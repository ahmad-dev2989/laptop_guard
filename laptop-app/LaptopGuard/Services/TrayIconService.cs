using System.Drawing;
using System.Windows.Forms;
using Application = System.Windows.Application;

namespace LaptopGuard.Services;

/// <summary>
/// Manages the Windows system tray icon and quick actions while Travel Mode runs in the background.
/// </summary>
public sealed class TrayIconService : IDisposable
{
    private readonly NotifyIcon _notifyIcon;
    private readonly ToolStripMenuItem _toggleTravelItem;
    private readonly Icon _loadedIcon;

    public event Action? OpenRequested;
    public event Action? ToggleTravelRequested;
    public event Action? TurnScreenOffRequested;
    public event Action? ExitRequested;

    public TrayIconService()
    {
        var iconStreamInfo = Application.GetResourceStream(new Uri("pack://application:,,,/Assets/app.ico"));
        _loadedIcon = iconStreamInfo is not null
            ? new Icon(iconStreamInfo.Stream)
            : SystemIcons.Shield;

        var menu = new ContextMenuStrip();
        var openItem = new ToolStripMenuItem("Open Laptop Guard");
        openItem.Click += (_, _) => OpenRequested?.Invoke();

        _toggleTravelItem = new ToolStripMenuItem("Start Travel Mode");
        _toggleTravelItem.Click += (_, _) => ToggleTravelRequested?.Invoke();

        var screenOffItem = new ToolStripMenuItem("Turn Screen Off Now");
        screenOffItem.Click += (_, _) => TurnScreenOffRequested?.Invoke();

        var exitItem = new ToolStripMenuItem("Exit");
        exitItem.Click += (_, _) => ExitRequested?.Invoke();

        menu.Items.Add(openItem);
        menu.Items.Add(_toggleTravelItem);
        menu.Items.Add(screenOffItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(exitItem);

        _notifyIcon = new NotifyIcon
        {
            Icon = _loadedIcon,
            Text = "Laptop Guard — Idle",
            ContextMenuStrip = menu,
            Visible = true
        };
        _notifyIcon.DoubleClick += (_, _) => OpenRequested?.Invoke();
    }

    public void UpdateState(bool isBroadcasting)
    {
        _notifyIcon.Text = isBroadcasting
            ? "Laptop Guard — Travel Mode ACTIVE"
            : "Laptop Guard — Idle";
        _toggleTravelItem.Text = isBroadcasting
            ? "Stop Travel Mode"
            : "Start Travel Mode";
    }

    public void ShowTrayHint(string title, string message)
    {
        _notifyIcon.ShowBalloonTip(2500, title, message, ToolTipIcon.Info);
    }

    public void Dispose()
    {
        _notifyIcon.Visible = false;
        _notifyIcon.Dispose();
        _loadedIcon.Dispose();
    }
}

