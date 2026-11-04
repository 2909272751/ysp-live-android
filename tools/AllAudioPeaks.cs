using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

// 枚举所有渲染设备并读取各自峰值。
// 只看默认设备会漏掉"声音被送到另一个声卡"的情况（多声卡机器常见）。
public static class AllAudioPeaks
{
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumerator { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection devices);
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
        int GetDevice(string id, out IMMDevice device);
        int RegisterEndpointNotificationCallback(IntPtr client);
        int UnregisterEndpointNotificationCallback(IntPtr client);
    }

    [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceCollection
    {
        int GetCount(out int count);
        int Item(int index, out IMMDevice device);
    }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDevice
    {
        int Activate(ref Guid iid, int clsCtx, IntPtr activationParams,
                     [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        int OpenPropertyStore(int access, out IntPtr store);
        int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetState(out int state);
    }

    [ComImport, Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioMeterInformation
    {
        int GetPeakValue(out float peak);
        int GetMeteringChannelCount(out int count);
        int GetChannelsPeakValues(int channelCount, [Out] float[] peaks);
        int QueryHardwareSupport(out int mask);
    }

    /// <summary>返回每个渲染设备的峰值，格式 "name=peak;..."</summary>
    public static string ReadAll()
    {
        try
        {
            var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumerator());
            IMMDeviceCollection col;
            // eRender=0, DEVICE_STATE_ACTIVE=1
            if (enumerator.EnumAudioEndpoints(0, 1, out col) != 0 || col == null)
                return "enum failed";

            int n;
            col.GetCount(out n);
            var parts = new List<string>();
            for (int i = 0; i < n; i++)
            {
                IMMDevice dev;
                if (col.Item(i, out dev) != 0 || dev == null) continue;

                Guid iid = typeof(IAudioMeterInformation).GUID;
                object obj;
                if (dev.Activate(ref iid, 1, IntPtr.Zero, out obj) != 0 || obj == null)
                {
                    parts.Add("#" + i + "=no-meter");
                    continue;
                }
                var meter = (IAudioMeterInformation)obj;
                float peak;
                meter.GetPeakValue(out peak);
                parts.Add("#" + i + "=" + peak.ToString("F4"));
            }
            return string.Join("; ", parts.ToArray());
        }
        catch (Exception e)
        {
            return "err " + e.GetType().Name;
        }
    }

    public static int DeviceCount()
    {
        try
        {
            var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumerator());
            IMMDeviceCollection col;
            if (enumerator.EnumAudioEndpoints(0, 1, out col) != 0 || col == null) return -1;
            int n;
            col.GetCount(out n);
            return n;
        }
        catch { return -1; }
    }
}
