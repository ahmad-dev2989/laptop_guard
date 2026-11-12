using System.IO;
using System.Windows.Media.Imaging;
using QRCoder;

namespace LaptopGuard.Services;

/// <summary>
/// Generates crisp frozen WPF BitmapImage instances for QR pairing codes.
/// </summary>
public static class QrImageHelper
{
    public static BitmapImage GenerateQrBitmap(string payload, int pixelsPerModule = 12)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.M);
        var pngQr = new PngByteQRCode(data);
        var bytes = pngQr.GetGraphic(pixelsPerModule, darkColorRgba: [18, 20, 24, 255], lightColorRgba: [255, 255, 255, 255], drawQuietZones: true);

        using var ms = new MemoryStream(bytes);
        var bitmap = new BitmapImage();
        bitmap.BeginInit();
        bitmap.CacheOption = BitmapCacheOption.OnLoad;
        bitmap.StreamSource = ms;
        bitmap.EndInit();
        bitmap.Freeze();
        return bitmap;
    }
}
