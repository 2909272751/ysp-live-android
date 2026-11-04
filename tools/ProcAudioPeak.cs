using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;

// 按"进程"读音频峰值：枚举所有渲染设备的音频会话，只挑属于指定 PID 的会话。
// 之前的版本读的是设备总峰值，会把其它程序的声音也算进来，结论不可靠。
public static class ProcAudioPeak
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
        int RegisterEndpointNotificationCallback(IntPtr c);
        int UnregisterEndpointNotificationCallback(IntPtr c);
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
        int Activate(ref Guid iid, int clsCtx, IntPtr ap,
                     [MarshalAs(UnmanagedType.IUnknown)] out object iface);
        int OpenPropertyStore(int access, out IntPtr store);
        int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetState(out int state);
    }

    [ComImport, Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioSessionManager2
    {
        // IAudioSessionManager
        int GetAudioSessionControl(IntPtr guid, int flags, out IntPtr session);
        int GetSimpleAudioVolume(IntPtr guid, int flags, out IntPtr vol);
        // IAudioSessionManager2
        int GetSessionEnumerator(out IAudioSessionEnumerator enumerator);
        int RegisterSessionNotification(IntPtr cb);
        int UnregisterSessionNotification(IntPtr cb);
        int RegisterDuckNotification(string id, IntPtr cb);
        int UnregisterDuckNotification(IntPtr cb);
    }

    [ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioSessionEnumerator
    {
        int GetCount(out int count);
        int GetSession(int index, out IAudioSessionControl ctl);
    }

    [ComImport, Guid("F4B1A599-7266-4319-A8CA-E70ACB11E8CD"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioSessionControl
    {
        int GetState(out int state);
        int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);
        int SetDisplayName(string name, IntPtr guid);
        int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);
        int SetIconPath(string path, IntPtr guid);
        int GetGroupingParam(out Guid g);
        int SetGroupingParam(ref Guid g, IntPtr ctx);
        int RegisterAudioSessionNotification(IntPtr cb);
        int UnregisterAudioSessionNotification(IntPtr cb);
    }

    [ComImport, Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioSessionControl2
    {
        // 继承自 IAudioSessionControl 的 9 个方法
        int GetState(out int state);
        int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);
        int SetDisplayName(string name, IntPtr guid);
        int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);
        int SetIconPath(string path, IntPtr guid);
        int GetGroupingParam(out Guid g);
        int SetGroupingParam(ref Guid g, IntPtr ctx);
        int RegisterAudioSessionNotification(IntPtr cb);
        int UnregisterAudioSessionNotification(IntPtr cb);
        // 自己的方法
        int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string id);
        int GetProcessId(out int pid);
    }

    [ComImport, Guid("C02216F6-8C67-4B5B-9D00-D008E73E0064"),
     InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IAudioMeterInformation
    {
        int GetPeakValue(out float peak);
        int GetMeteringChannelCount(out int count);
        int GetChannelsPeakValues(int n, [Out] float[] peaks);
        int QueryHardwareSupport(out int mask);
    }

    /// <summary>返回指定进程在所有渲染设备上的最大峰值；-1 表示没有找到它的会话。</summary>
    public static float PeakForProcess(int targetPid)
    {
        float best = -1f;
        try
        {
            var enumerator = (IMMDeviceEnumerator)(new MMDeviceEnumerator());
            IMMDeviceCollection col;
            if (enumerator.EnumAudioEndpoints(0, 1, out col) != 0 || col == null) return -1f;
            int nDev;
            col.GetCount(out nDev);

            for (int d = 0; d < nDev; d++)
            {
                IMMDevice dev;
                if (col.Item(d, out dev) != 0 || dev == null) continue;

                Guid mgrIid = typeof(IAudioSessionManager2).GUID;
                object mgrObj;
                if (dev.Activate(ref mgrIid, 1, IntPtr.Zero, out mgrObj) != 0 || mgrObj == null)
                    continue;
                var mgr = (IAudioSessionManager2)mgrObj;

                IAudioSessionEnumerator sesEnum;
                if (mgr.GetSessionEnumerator(out sesEnum) != 0 || sesEnum == null) continue;
                int nSes;
                sesEnum.GetCount(out nSes);

                for (int s = 0; s < nSes; s++)
                {
                    IAudioSessionControl ctl;
                    if (sesEnum.GetSession(s, out ctl) != 0 || ctl == null) continue;
                    var ctl2 = ctl as IAudioSessionControl2;
                    if (ctl2 == null) continue;

                    int pid;
                    if (ctl2.GetProcessId(out pid) != 0 || pid != targetPid) continue;

                    object meterObj = ctl;  // 同一对象可 QueryInterface 到 IAudioMeterInformation
                    var meter = meterObj as IAudioMeterInformation;
                    if (meter == null) continue;
                    float p;
                    if (meter.GetPeakValue(out p) != 0) continue;
                    if (p > best) best = p;
                }
            }
        }
        catch
        {
            return -1f;
        }
        return best;
    }
}
