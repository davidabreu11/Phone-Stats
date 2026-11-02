package com.phonestats.app;

import android.net.wifi.WifiInfo;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Connected network plus every access point heard, grouped by network name. */
final class WifiTab {

    private static final int MAX_NETWORKS = 25;
    private static final int MAX_APS_PER_NETWORK = 12;

    private final MainActivity a;
    private final WifiReader wifi;
    private final LinearLayout col;
    final View view;
    private TextView status;

    WifiTab(MainActivity a, WifiReader wifi) {
        this.a = a;
        this.wifi = wifi;
        this.col = Ui.column(a);
        this.view = Ui.scroll(col);
    }

    void renderStatus() {
        if (status != null) status.setText(wifi.status());
    }

    void render() {
        col.removeAllViews();
        status = null;
        if (!a.hasLocationPermission()) {
            a.addPermissionCard(col, "Wi-Fi scan results count as location data on Android, "
                    + "so this tab needs Location permission. Choose \"Precise\" when asked.");
            return;
        }
        if (!a.isLocationOn()) {
            a.addLocationOffCard(col);
            return;
        }
        if (!wifi.isWifiOn()) {
            LinearLayout c = Ui.card(col, "WI-FI IS OFF");
            c.addView(Ui.text(a, "Turn on Wi-Fi to scan for access points.", 15, Ui.TEXT, false));
            return;
        }

        status = Ui.text(a, wifi.status(), 12, Ui.MUTED, false);
        status.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), Ui.dp(a, 10));
        col.addView(status);

        renderConnected();

        Map<String, List<WifiReader.Ap>> groups = new LinkedHashMap<>();
        for (WifiReader.Ap ap : wifi.results()) {
            List<WifiReader.Ap> g = groups.get(ap.ssid);
            if (g == null) {
                g = new ArrayList<>();
                groups.put(ap.ssid, g);
            }
            g.add(ap);
        }
        TextView h = Ui.text(a, String.format(Locale.US, "NEARBY · %d NETWORKS · %d ACCESS POINTS",
                groups.size(), wifi.results().size()), 12, Ui.MUTED, true);
        h.setLetterSpacing(0.08f);
        h.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), 0, Ui.dp(a, 8));
        col.addView(h);

        int shown = 0;
        for (Map.Entry<String, List<WifiReader.Ap>> e : groups.entrySet()) {
            if (shown++ >= MAX_NETWORKS) break;
            renderNetwork(e.getKey(), e.getValue());
        }
    }

    private void renderConnected() {
        String ssid = wifi.connectedSsid();
        WifiInfo info = wifi.connection();
        LinearLayout c = Ui.card(col, "CONNECTED NETWORK");
        if (ssid == null || info == null) {
            c.addView(Ui.text(a, "Not connected to Wi-Fi", 16, Ui.MUTED, false));
            return;
        }
        int rssi = info.getRssi();
        int r = Ui.wifiRating(rssi);
        c.addView(Ui.text(a, ssid, 22, Ui.TEXT, true));
        Ui.bar(c).set(Ui.wifiFraction(rssi), Ui.ratingColor(r));
        c.addView(Ui.text(a, rssi + " dBm · " + Ui.ratingName(r), 16, Ui.ratingColor(r), true));
        int f = info.getFrequency();
        String d = WifiReader.band(f) + " · channel " + WifiReader.channel(f)
                + " · " + info.getLinkSpeed() + " Mbps link";
        c.addView(Ui.text(a, d, 13, Ui.MUTED, false));
    }

    private void renderNetwork(String ssid, List<WifiReader.Ap> aps) {
        boolean connected = false;
        for (WifiReader.Ap ap : aps) connected |= ap.connected;
        String title = ssid + (aps.size() > 1 ? "  (" + aps.size() + " access points)" : "")
                + (connected ? "  · CONNECTED" : "");
        LinearLayout c = Ui.card(col, null);
        c.addView(Ui.text(a, title, 16, connected ? Ui.BLUE : Ui.TEXT, true));
        for (int i = 0; i < Math.min(MAX_APS_PER_NETWORK, aps.size()); i++) {
            WifiReader.Ap ap = aps.get(i);
            int r = Ui.wifiRating(ap.rssi);
            Ui.bar(c).set(Ui.wifiFraction(ap.rssi), Ui.ratingColor(r));
            StringBuilder s = new StringBuilder();
            s.append(ap.rssi).append(" dBm ").append(Ui.ratingName(r))
                    .append(" · ").append(ap.band).append(" ch ").append(ap.channel);
            if (!ap.width.isEmpty()) s.append(" · ").append(ap.width);
            if (!ap.standard.isEmpty()) s.append(" · ").append(ap.standard);
            s.append("\nAP ").append(ap.bssid).append(ap.connected ? "  ← you're connected to this one" : "");
            c.addView(Ui.text(a, s.toString(), 13, Ui.ratingColor(r), false));
        }
    }
}
