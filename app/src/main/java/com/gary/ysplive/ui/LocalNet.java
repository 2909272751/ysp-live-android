package com.gary.ysplive.ui;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.TextUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/**
 * 读取本机局域网 IPv4 地址（用于展示局域网订阅地址）。
 * <p>优先用 {@link ConnectivityManager}/{@link LinkProperties}（API 21+ 的现代方式），
 * 取不到时退回 {@link WifiManager} 与网卡枚举。全部做了异常兜底。
 */
final class LocalNet {

    private LocalNet() {
    }

    /** 返回形如 {@code 192.168.1.23} 的地址；无可用网络返回空串。 */
    static String ipv4(Context ctx) {
        String ip = fromLinkProperties(ctx);
        if (!ip.isEmpty()) {
            return ip;
        }
        ip = fromWifiManager(ctx);
        if (!ip.isEmpty()) {
            return ip;
        }
        return fromInterfaces();
    }

    /** 根据 IP 与端口拼出局域网订阅地址；未开启局域网访问时仍返回地址供参考。 */
    static String playlistUrl(String ip, int port) {
        if (ip == null || ip.isEmpty()) {
            return "";
        }
        return "http://" + ip + ":" + port + "/all.m3u";
    }

    private static String fromLinkProperties(Context ctx) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return "";
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network active = cm.getActiveNetwork();
                if (active == null) {
                    return "";
                }
                LinkProperties lp = cm.getLinkProperties(active);
                if (lp == null) {
                    return "";
                }
                for (android.net.LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress addr = la.getAddress();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
                return "";
            }
            @SuppressWarnings("deprecation")
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) {
                return "";
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    @SuppressWarnings("deprecation")
    private static String fromWifiManager(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return "";
            }
            int ipInt = wm.getConnectionInfo() == null ? 0 : wm.getConnectionInfo().getIpAddress();
            if (ipInt == 0) {
                return "";
            }
            // 小端序转成点分十进制
            return (ipInt & 0xFF) + "." + ((ipInt >> 8) & 0xFF) + "."
                    + ((ipInt >> 16) & 0xFF) + "." + ((ipInt >> 24) & 0xFF);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String fromInterfaces() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) {
                return "";
            }
            for (NetworkInterface nif : Collections.list(ifaces)) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()
                            || addr.isLinkLocalAddress()) {
                        continue;
                    }
                    String host = addr.getHostAddress();
                    if (!TextUtils.isEmpty(host)) {
                        return host;
                    }
                }
            }
        } catch (Throwable t) {
            // 忽略：没有权限或被限制
        }
        return "";
    }
}
