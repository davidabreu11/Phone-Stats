package com.phonestats.app;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The original stats screen: RAM, processor, battery, heat, storage, system. */
final class PhoneTab {

    // Android may return no reading if thermal headroom is asked for more often than this.
    private static final long THERMAL_REFRESH_MS = 10_000;

    private final Activity a;
    private final CpuReader cpu;
    final View view;

    private long lastThermalMs = -THERMAL_REFRESH_MS;
    private String thermalText = "Reading…";
    private int thermalColor = Ui.GREEN;
    private float thermalFraction = 0f;

    private TextView ramValue, ramDetail;
    private BarView ramBar;
    private TextView cpuValue, cpuDetail;
    private final List<TextView> coreLabels = new ArrayList<>();
    private final List<BarView> coreBars = new ArrayList<>();
    private TextView batteryValue, batteryDetail;
    private BarView batteryBar;
    private TextView thermalValue;
    private BarView thermalBar;
    private TextView storageValue, storageDetail;
    private BarView storageBar;
    private TextView systemDetail;

    PhoneTab(Activity a, CpuReader cpu) {
        this.a = a;
        this.cpu = cpu;
        this.view = build();
    }

    private View build() {
        LinearLayout col = Ui.column(a);

        LinearLayout c = Ui.card(col, "MEMORY (RAM)");
        ramValue = Ui.text(a, "", 22, Ui.TEXT, true);
        c.addView(ramValue);
        ramBar = Ui.bar(c);
        ramDetail = Ui.text(a, "", 13, Ui.MUTED, false);
        c.addView(ramDetail);

        c = Ui.card(col, "PROCESSOR");
        cpuValue = Ui.text(a, "", 22, Ui.TEXT, true);
        c.addView(cpuValue);
        cpuDetail = Ui.text(a, "", 13, Ui.MUTED, false);
        cpuDetail.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 4));
        c.addView(cpuDetail);
        for (int i = 0; i < cpu.coreCount(); i++) {
            TextView label = Ui.text(a, "", 13, Ui.TEXT, false);
            label.setTypeface(Typeface.MONOSPACE);
            label.setPadding(0, Ui.dp(a, 6), 0, 0);
            c.addView(label);
            coreLabels.add(label);
            coreBars.add(Ui.bar(c));
        }

        c = Ui.card(col, "BATTERY");
        batteryValue = Ui.text(a, "", 22, Ui.TEXT, true);
        c.addView(batteryValue);
        batteryBar = Ui.bar(c);
        batteryDetail = Ui.text(a, "", 13, Ui.MUTED, false);
        c.addView(batteryDetail);

        c = Ui.card(col, "HEAT & THROTTLING");
        thermalValue = Ui.text(a, "", 15, Ui.TEXT, false);
        c.addView(thermalValue);
        thermalBar = Ui.bar(c);

        c = Ui.card(col, "STORAGE");
        storageValue = Ui.text(a, "", 22, Ui.TEXT, true);
        c.addView(storageValue);
        storageBar = Ui.bar(c);
        storageDetail = Ui.text(a, "", 13, Ui.MUTED, false);
        c.addView(storageDetail);

        c = Ui.card(col, "SYSTEM");
        systemDetail = Ui.text(a, "", 14, Ui.TEXT, false);
        systemDetail.setLineSpacing(0, 1.3f);
        c.addView(systemDetail);

        return Ui.scroll(col);
    }

    void refresh() {
        try { updateMemory(); } catch (Exception e) { ramDetail.setText("Couldn't read memory"); }
        try { updateCpu(); } catch (Exception e) { cpuDetail.setText("Couldn't read processor info"); }
        try { updateBattery(); } catch (Exception e) { batteryDetail.setText("Couldn't read battery"); }
        try { updateThermal(); } catch (Exception e) { thermalValue.setText("Not available on this phone"); }
        try { updateStorage(); } catch (Exception e) { storageDetail.setText("Couldn't read storage"); }
        try { updateSystem(); } catch (Exception e) { systemDetail.setText("Couldn't read system info"); }
    }

    private void updateMemory() {
        ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long used = mi.totalMem - mi.availMem;
        float f = (float) used / mi.totalMem;
        ramValue.setText(String.format(Locale.US, "%s of %s used", gb(used), gb(mi.totalMem)));
        ramBar.set(f, Ui.levelColor(f));
        ramDetail.setText(String.format(Locale.US, "%.0f%% used  ·  %s available  ·  Low memory: %s",
                f * 100, gb(mi.availMem), mi.lowMemory ? "YES" : "no"));
    }

    private void updateCpu() {
        CpuReader.Snapshot s = cpu.read();
        if (s.usagePercent >= 0) {
            cpuValue.setText(String.format(Locale.US, "%.0f%% busy", s.usagePercent));
            cpuDetail.setText("Overall CPU usage across all cores");
        } else if (cpu.usageAvailable()) {
            cpuValue.setText("Measuring…");
            cpuDetail.setText("");
        } else {
            cpuValue.setText(String.format(Locale.US, "%s avg speed", ghz(s.avgCurKhz)));
            cpuDetail.setText(String.format(Locale.US,
                    "Running at %.0f%% of max speed, %d of %d cores active.\n"
                            + "Android doesn't let apps read true CPU usage %%, so this shows clock speeds.",
                    s.avgFraction * 100, s.online, s.cores.length));
        }

        for (int i = 0; i < s.cores.length && i < coreLabels.size(); i++) {
            CpuReader.Core c = s.cores[i];
            TextView label = coreLabels.get(i);
            BarView b = coreBars.get(i);
            if (!c.online) {
                label.setText(String.format(Locale.US, "Core %d  asleep", i));
                b.set(0f, Ui.BLUE);
                continue;
            }
            String max = c.maxKhz > 0 ? " / " + ghz(c.maxKhz) : "";
            if (c.usage >= 0) {
                label.setText(String.format(Locale.US, "Core %d  %3.0f%%  ·  %s%s", i, c.usage, ghz(c.curKhz), max));
                b.set(c.usage / 100f, Ui.levelColor(c.usage / 100f));
            } else {
                label.setText(String.format(Locale.US, "Core %d  %s%s", i, ghz(c.curKhz), max));
                float f = c.maxKhz > 0 ? (float) c.curKhz / c.maxKhz : 0f;
                b.set(f, Ui.BLUE);
            }
        }
    }

    private void updateBattery() {
        Intent b = a.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        float pct = scale > 0 ? level * 100f / scale : 0f;
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        float tempC = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        float volts = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f;

        batteryValue.setText(String.format(Locale.US, "%.0f%%  ·  %s", pct, batteryStatus(status, plugged)));
        batteryBar.set(pct / 100f, pct <= 15 ? Ui.RED : pct <= 30 ? Ui.YELLOW : Ui.GREEN);

        StringBuilder d = new StringBuilder(String.format(Locale.US,
                "Temp %.1f °C (%.1f °F)  ·  %.2f V", tempC, tempC * 9 / 5 + 32, volts));
        BatteryManager bm = (BatteryManager) a.getSystemService(Context.BATTERY_SERVICE);
        int microAmps = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (microAmps != Integer.MIN_VALUE && microAmps != 0) {
            d.append(String.format(Locale.US, "  ·  %d mA", Math.abs(microAmps) / 1000));
        }
        batteryDetail.setText(d.toString());
    }

    private void updateThermal() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastThermalMs >= THERMAL_REFRESH_MS) {
            lastThermalMs = now;
            PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
            int st = pm.getCurrentThermalStatus();
            String t = "Status: " + thermalStatus(st);
            thermalColor = st <= PowerManager.THERMAL_STATUS_LIGHT ? Ui.GREEN
                    : st == PowerManager.THERMAL_STATUS_MODERATE ? Ui.YELLOW : Ui.RED;
            thermalFraction = Math.min(1f, st / (float) PowerManager.THERMAL_STATUS_SEVERE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                float h = pm.getThermalHeadroom(0);
                if (!Float.isNaN(h)) {
                    thermalFraction = h;
                    t += String.format(Locale.US, "\n%.0f%% of the way to heavy slow-down", h * 100);
                }
            }
            thermalText = t;
        }
        thermalValue.setText(thermalText);
        thermalBar.set(thermalFraction, thermalColor);
    }

    private void updateStorage() {
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes();
        long free = fs.getAvailableBytes();
        long used = total - free;
        float f = (float) used / total;
        storageValue.setText(String.format(Locale.US, "%s of %s used", gb(used), gb(total)));
        storageBar.set(f, Ui.levelColor(f));
        storageDetail.setText(String.format(Locale.US, "%.0f%% used  ·  %s free", f * 100, gb(free)));
    }

    private void updateSystem() {
        StringBuilder s = new StringBuilder();
        s.append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        s.append("Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            s.append("Chip: ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append('\n');
        }
        s.append("Cores: ").append(cpu.coreCount()).append('\n');
        s.append("Up since restart: ").append(duration(SystemClock.elapsedRealtime()));
        systemDetail.setText(s.toString());
    }

    private static String gb(long bytes) {
        return String.format(Locale.US, "%.1f GB", bytes / 1073741824.0);
    }

    private static String ghz(long khz) {
        if (khz <= 0) return "—";
        if (khz < 1_000_000) return String.format(Locale.US, "%d MHz", khz / 1000);
        return String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0);
    }

    private static String duration(long ms) {
        long m = ms / 60000;
        long d = m / 1440;
        long h = (m % 1440) / 60;
        m = m % 60;
        if (d > 0) return String.format(Locale.US, "%dd %dh %dm", d, h, m);
        if (h > 0) return String.format(Locale.US, "%dh %dm", h, m);
        return String.format(Locale.US, "%dm", m);
    }

    private static String batteryStatus(int status, int plugged) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) return "Charging (wireless)";
                if (plugged == BatteryManager.BATTERY_PLUGGED_USB) return "Charging (USB)";
                return "Charging";
            case BatteryManager.BATTERY_STATUS_FULL:
                return "Full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return "Plugged in, not charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                return "On battery";
            default:
                return "Unknown";
        }
    }

    private static String thermalStatus(int st) {
        switch (st) {
            case PowerManager.THERMAL_STATUS_NONE: return "Cool";
            case PowerManager.THERMAL_STATUS_LIGHT: return "Warm (light)";
            case PowerManager.THERMAL_STATUS_MODERATE: return "Warm (moderate)";
            case PowerManager.THERMAL_STATUS_SEVERE: return "Hot (slowing down)";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "Very hot";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "Emergency";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "Shutting down";
            default: return "Unknown";
        }
    }
}
