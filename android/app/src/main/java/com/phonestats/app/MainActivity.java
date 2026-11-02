package com.phonestats.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Tabbed shell: Phone, Cellular, Wi-Fi, Survey.
 * Everything refreshes only while the app is on screen; nothing runs in the background.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 7;
    private static final long TICK_MS = 1000;
    private static final long CELL_MS = 3000;
    private static final long WIFI_MS = 5000;
    private static final String[] TAB_NAMES = {"Phone", "Cell", "Wi-Fi", "Survey", "Log"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private CellReader cell;
    private WifiReader wifi;
    private PhoneTab phoneTab;
    private CellTab cellTab;
    private WifiTab wifiTab;
    private SurveyTab surveyTab;
    private LogTab logTab;

    private FrameLayout content;
    private final Button[] tabs = new Button[TAB_NAMES.length];
    private int current = 0;
    private long lastCell = 0;
    private long lastWifi = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CpuReader cpu = new CpuReader();
        cell = new CellReader(this, this::onCellUpdate);
        wifi = new WifiReader(this, this::onWifiUpdate);
        SurveyStore store = new SurveyStore(this);
        phoneTab = new PhoneTab(this, cpu);
        cellTab = new CellTab(this, cell);
        wifiTab = new WifiTab(this, wifi);
        surveyTab = new SurveyTab(this, cell, wifi, store);
        logTab = new LogTab(this);
        setContentView(buildShell());
        selectTab(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        wifi.start();
        selectTab(current); // permissions or settings may have changed while away
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    protected void onDestroy() {
        logTab.release();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        wifi.stop();
    }

    // ---------------------------------------------------------------- Layout

    private View buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = Math.max(insets.getSystemWindowInsetBottom(), 0);
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), bottom);
            return insets;
        });

        TextView title = Ui.text(this, "Phone Stats", 24, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 4));
        root.addView(title);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 10));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            Button b = Ui.button(this, TAB_NAMES[i], false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
            lp.leftMargin = Ui.dp(this, 4);
            lp.rightMargin = Ui.dp(this, 4);
            final int idx = i;
            b.setOnClickListener(v -> selectTab(idx));
            bar.addView(b, lp);
            tabs[i] = b;
        }
        root.addView(bar);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));
        return root;
    }

    private View viewFor(int i) {
        switch (i) {
            case 1: return cellTab.view;
            case 2: return wifiTab.view;
            case 3: return surveyTab.view;
            case 4: return logTab.view;
            default: return phoneTab.view;
        }
    }

    private void selectTab(int i) {
        current = i;
        View v = viewFor(i);
        if (content.getChildCount() != 1 || content.getChildAt(0) != v) {
            content.removeAllViews();
            content.addView(v, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        }
        for (int k = 0; k < tabs.length; k++) Ui.style(tabs[k], k == i);
        lastCell = 0;
        lastWifi = 0;
        if (i == 1) cellTab.render();
        if (i == 2) wifiTab.render();
        if (i == 3) surveyTab.onShown();
        if (i == 4) logTab.onShown();
        onTick();
    }

    // ---------------------------------------------------------------- Refresh loop

    private void onTick() {
        long now = SystemClock.elapsedRealtime();
        switch (current) {
            case 0:
                phoneTab.refresh();
                break;
            case 1:
                if (now - lastCell >= CELL_MS) {
                    lastCell = now;
                    if (hasLocationPermission() && isLocationOn()) cell.requestUpdate();
                }
                break;
            case 2:
                if (now - lastWifi >= WIFI_MS) {
                    lastWifi = now;
                    if (hasLocationPermission() && isLocationOn() && wifi.isWifiOn()) wifi.scan();
                }
                wifiTab.renderStatus();
                break;
            default:
                break;
        }
    }

    private void onCellUpdate() {
        if (current == 1) cellTab.render();
    }

    private void onWifiUpdate() {
        if (current == 2) wifiTab.render();
    }

    // ---------------------------------------------------------------- Permissions

    boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    boolean isLocationOn() {
        LocationManager lm = getSystemService(LocationManager.class);
        return lm != null && lm.isLocationEnabled();
    }

    void requestPerms() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        p.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        requestPermissions(p.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) selectTab(current);
    }

    void addPermissionCard(LinearLayout col, String why) {
        LinearLayout c = Ui.card(col, "PERMISSION NEEDED");
        c.addView(Ui.text(this, why, 15, Ui.TEXT, false));
        TextView more = Ui.text(this, "The app only reads this while it's open on screen and never sends it anywhere. "
                + "It also asks for Phone permission to list your SIMs.", 13, Ui.MUTED, false);
        more.setPadding(0, Ui.dp(this, 6), 0, 0);
        c.addView(more);
        Button allow = Ui.button(this, "Allow access", true);
        allow.setOnClickListener(v -> requestPerms());
        c.addView(allow, Ui.spaced(this, 12));
        Button settings = Ui.button(this, "Open app settings (if Allow does nothing)", false);
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null))));
        c.addView(settings, Ui.spaced(this, 8));
    }

    void addLocationOffCard(LinearLayout col) {
        LinearLayout c = Ui.card(col, "LOCATION IS OFF");
        c.addView(Ui.text(this, "Android only shares cell tower and Wi-Fi details while Location is on.",
                15, Ui.TEXT, false));
        Button open = Ui.button(this, "Turn on Location", true);
        open.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        c.addView(open, Ui.spaced(this, 12));
    }
}
