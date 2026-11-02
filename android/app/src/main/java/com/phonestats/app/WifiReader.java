package com.phonestats.app;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/** Scans for Wi-Fi access points. Android allows 4 scans per 2 minutes unless throttling is off. */
final class WifiReader {

    static final class Ap {
        String ssid;
        String bssid;
        int rssi;
        int freq;
        String band;
        int channel;
        String width = "";
        String standard = "";
        boolean connected;
    }

    private final Context ctx;
    private final WifiManager wm;
    private final Runnable onUpdate;
    private List<Ap> latest = new ArrayList<>();
    private long newestResultMs = 0;
    private boolean throttled = false;
    private boolean registered = false;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            throttled = false;
            load();
            onUpdate.run();
        }
    };

    WifiReader(Context ctx, Runnable onUpdate) {
        this.ctx = ctx;
        this.onUpdate = onUpdate;
        this.wm = ctx.getApplicationContext().getSystemService(WifiManager.class);
    }

    void start() {
        if (registered || wm == null) return;
        IntentFilter f = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, f);
        }
        registered = true;
    }

    void stop() {
        if (!registered) return;
        try {
            ctx.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        registered = false;
    }

    boolean isWifiOn() {
        return wm != null && wm.isWifiEnabled();
    }

    /** Starts a scan. If Android refuses (throttled), shows the most recent results instead. */
    @SuppressWarnings("deprecation")
    void scan() {
        if (wm == null) return;
        boolean ok;
        try {
            ok = wm.startScan();
        } catch (SecurityException e) {
            ok = false;
        }
        if (!ok) {
            throttled = true;
            load();
            onUpdate.run();
        }
    }

    List<Ap> results() {
        return latest;
    }

    /** Age of the newest scan result in seconds, or -1 if none yet. */
    long ageSeconds() {
        if (newestResultMs == 0) return -1;
        return (SystemClock.elapsedRealtime() - newestResultMs) / 1000;
    }

    String status() {
        long age = ageSeconds();
        if (age < 0) return "Scanning…";
        if (throttled && age > 10) {
            return "Android is limiting scans, so these results are " + age + "s old. For faster updates, "
                    + "turn off \"Wi-Fi scan throttling\" in Developer options.";
        }
        return "Last scan " + (age < 2 ? "just now" : age + "s ago") + " · scans every 5 seconds";
    }

    @SuppressWarnings("deprecation")
    WifiInfo connection() {
        try {
            return wm == null ? null : wm.getConnectionInfo();
        } catch (Exception e) {
            return null;
        }
    }

    /** Name of the connected Wi-Fi network, or null. */
    String connectedSsid() {
        WifiInfo info = connection();
        if (info == null || info.getNetworkId() == -1) return null;
        String s = info.getSSID();
        if (s == null || s.equals(WifiManager.UNKNOWN_SSID) || s.isEmpty()) return null;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return s;
    }

    @SuppressLint("MissingPermission")
    @SuppressWarnings("deprecation")
    private void load() {
        List<ScanResult> rs;
        try {
            rs = wm.getScanResults();
        } catch (SecurityException e) {
            return;
        }
        if (rs == null) return;
        WifiInfo info = connection();
        String cb = info == null ? null : info.getBSSID();
        long newestUs = 0;
        List<Ap> out = new ArrayList<>();
        for (ScanResult r : rs) {
            Ap a = new Ap();
            a.ssid = r.SSID == null || r.SSID.isEmpty() ? "(hidden network)" : r.SSID;
            a.bssid = r.BSSID == null ? "" : r.BSSID;
            a.rssi = r.level;
            a.freq = r.frequency;
            a.band = band(r.frequency);
            a.channel = channel(r.frequency);
            a.width = width(r.channelWidth);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) a.standard = standard(r.getWifiStandard(), a.band);
            a.connected = cb != null && cb.equalsIgnoreCase(a.bssid);
            out.add(a);
            newestUs = Math.max(newestUs, r.timestamp);
        }
        out.sort((x, y) -> Integer.compare(y.rssi, x.rssi));
        latest = out;
        if (newestUs > 0) newestResultMs = newestUs / 1000;
    }

    static String band(int mhz) {
        if (mhz < 3000) return "2.4 GHz";
        if (mhz < 5925) return "5 GHz";
        return "6 GHz";
    }

    static int channel(int mhz) {
        if (mhz == 2484) return 14;
        if (mhz < 3000) return (mhz - 2407) / 5;
        if (mhz < 5925) return (mhz - 5000) / 5;
        return (mhz - 5950) / 5;
    }

    private static String width(int w) {
        switch (w) {
            case ScanResult.CHANNEL_WIDTH_20MHZ: return "20 MHz";
            case ScanResult.CHANNEL_WIDTH_40MHZ: return "40 MHz";
            case ScanResult.CHANNEL_WIDTH_80MHZ: return "80 MHz";
            case ScanResult.CHANNEL_WIDTH_160MHZ: return "160 MHz";
            case ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ: return "80+80 MHz";
            case ScanResult.CHANNEL_WIDTH_320MHZ: return "320 MHz";
            default: return "";
        }
    }

    private static String standard(int s, String band) {
        switch (s) {
            case ScanResult.WIFI_STANDARD_11BE: return "Wi-Fi 7";
            case ScanResult.WIFI_STANDARD_11AX: return band.equals("6 GHz") ? "Wi-Fi 6E" : "Wi-Fi 6";
            case ScanResult.WIFI_STANDARD_11AC: return "Wi-Fi 5";
            case ScanResult.WIFI_STANDARD_11N: return "Wi-Fi 4";
            case ScanResult.WIFI_STANDARD_LEGACY: return "Older Wi-Fi";
            default: return "";
        }
    }
}
