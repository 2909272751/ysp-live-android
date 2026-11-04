using System;
using System.Runtime.InteropServices;

// 通过 WASAPI 读取默认播放设备的音频峰值。
// 峰值 > 0 说明系统确实在渲染声音（不是"状态看起来对"而已）。
public static class AudioPeak
{
    [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
    private class MMDeviceEnumerator { }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IMMDeviceEnumerator
    {
        int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
        int GetDevice(string id, out IMMDevice device);
        int RegisterEndpointNotificationCallback(IntPtr client);
        int UnregisterEndpointNotificationCallback(IntPtr client);
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

    /// <summary>默认扬声器的当前峰值（0.0 ~ 1.0）。返回 -1 表示读取失败。</summary>
    public static float ReadPeak()
    {
        try
        {
            var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumerator());
            IMMDevice device;
            // eRender = 0, eConsole = 0
            if (enumerator.GetDefaultAudioEndpoint(0, 0, out device) != 0 || device == null)
                return -1f;

            Guid iid = typeof(IAudioMeterInformation).GUID;
            object obj;
            if (device.Activate(ref iid, 1 /*CLSCTX_INPROC_SERVER*/, IntPtr.Zero, out obj) != 0
                || obj == null)
                return -1f;

            var meter = (IAudioMeterInformation)obj;
            float peak;
            if (meter.GetPeakValue(out peak) != 0)
                return -1f;
            return peak;
        }
        catch
        {
            return -1f;
        }
    }
}
