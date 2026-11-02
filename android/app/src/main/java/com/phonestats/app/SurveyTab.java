package com.phonestats.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Record named spots (cell + Wi-Fi), list them weakest first, export as CSV. */
final class SurveyTab {

    private static final long MEASURE_MS = 5000;
    private static final String PREF_OFFICE = "office_ssid";

    private final MainActivity a;
    private final CellReader cell;
    private final WifiReader wifi;
    private final SurveyStore store;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    final View view;

    private EditText office;
    private AutoCompleteTextView spotName;
    private ArrayAdapter<String> names;
    private Button recordBtn;
    private TextView status;
    private LinearLayout results;
    private boolean measuring = false;

    private static final class Line {
        final String text;
        final int rating;

        Line(String text, int rating) {
            this.text = text;
            this.rating = rating;
        }
    }

    private static final class Spot {
        String name;
        String time;
        int recordings;
        int score = 4;
        final List<Line> lines = new ArrayList<>();
    }

    SurveyTab(MainActivity a, CellReader cell, WifiReader wifi, SurveyStore store) {
        this.a = a;
        this.cell = cell;
        this.wifi = wifi;
        this.store = store;
        this.prefs = a.getSharedPreferences("phone_stats", Context.MODE_PRIVATE);
        this.view = build();
    }

    private View build() {
        LinearLayout col = Ui.column(a);

        TextView intro = Ui.text(a, "Stand in a spot, give it a name, and tap Record. Each recording saves "
                + "every carrier on this phone plus every Wi-Fi access point it can hear.", 13, Ui.MUTED, false);
        intro.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), Ui.dp(a, 12));
        col.addView(intro);

        LinearLayout c = Ui.card(col, "YOUR OFFICE WI-FI NAME");
        office = Ui.input(a, "e.g. CompanyWiFi");
        office.setText(prefs.getString(PREF_OFFICE, ""));
        office.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                prefs.edit().putString(PREF_OFFICE, s.toString().trim()).apply();
                renderResults();
            }
        });
        c.addView(office);
        Button useConnected = Ui.button(a, "Use the network I'm connected to", false);
        useConnected.setOnClickListener(v -> {
            String ssid = wifi.connectedSsid();
            if (ssid == null) toast("Not connected to Wi-Fi (or Location permission is off)");
            else office.setText(ssid);
        });
        c.addView(useConnected, Ui.spaced(a, 8));
        TextView hint = Ui.text(a, "Results show how well this network reaches each spot.", 12, Ui.MUTED, false);
        hint.setPadding(0, Ui.dp(a, 6), 0, 0);
        c.addView(hint);

        c = Ui.card(col, "RECORD A SPOT");
        spotName = new AutoCompleteTextView(a);
        Ui.styleInput(spotName, "e.g. 3rd floor, east corner");
        spotName.setThreshold(1);
        names = new ArrayAdapter<>(a, android.R.layout.simple_dropdown_item_1line, new ArrayList<>(spotNames()));
        spotName.setAdapter(names);
        c.addView(spotName);
        recordBtn = Ui.button(a, "Record here", true);
        recordBtn.setOnClickListener(v -> record());
        c.addView(recordBtn, Ui.spaced(a, 10));
        status = Ui.text(a, "", 13, Ui.MUTED, false);
        status.setPadding(0, Ui.dp(a, 8), 0, 0);
        c.addView(status);

        TextView h = Ui.text(a, "RESULTS · WEAKEST SPOTS FIRST", 12, Ui.MUTED, true);
        h.setLetterSpacing(0.08f);
        h.setPadding(Ui.dp(a, 4), Ui.dp(a, 4), 0, Ui.dp(a, 8));
        col.addView(h);
        results = new LinearLayout(a);
        results.setOrientation(LinearLayout.VERTICAL);
        col.addView(results);

        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button export = Ui.button(a, "Export CSV", true);
        export.setOnClickListener(v -> export());
        Button clear = Ui.button(a, "Delete all", false);
        clear.setOnClickListener(v -> confirmClear());
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        lp1.rightMargin = Ui.dp(a, 6);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, Ui.WRAP, 1f);
        lp2.leftMargin = Ui.dp(a, 6);
        row.addView(export, lp1);
        row.addView(clear, lp2);
        col.addView(row, Ui.spaced(a, 4));

        TextView tip = Ui.text(a, "Long-press a spot to delete it. Recording the same name again replaces "
                + "what's shown with the newest reading; older readings stay in the CSV.", 12, Ui.MUTED, false);
        tip.setPadding(Ui.dp(a, 4), Ui.dp(a, 12), Ui.dp(a, 4), 0);
        col.addView(tip);

        renderResults();
        return Ui.scroll(col);
    }

    void onShown() {
        if (office.getText().toString().trim().isEmpty() && a.hasLocationPermission()) {
            String ssid = wifi.connectedSsid();
            if (ssid != null) office.setText(ssid);
        }
        renderResults();
    }

    // ---------------------------------------------------------------- Recording

    private void record() {
        if (measuring) return;
        if (!a.hasLocationPermission()) {
            status.setText("Recording needs Location permission.");
            a.requestPerms();
            return;
        }
        if (!a.isLocationOn()) {
            status.setText("Turn on Location in quick settings first.");
            return;
        }
        String spot = spotName.getText().toString().trim();
        if (spot.isEmpty()) {
            status.setText("Type a name for this spot first.");
            return;
        }
        measuring = true;
        recordBtn.setEnabled(false);
        recordBtn.setAlpha(0.5f);
        status.setTextColor(Ui.TEXT);
        status.setText("Measuring… hold still for about 5 seconds.");
        cell.requestUpdate();
        wifi.scan();
        handler.postDelayed(() -> finish(spot), MEASURE_MS);
    }

    private void finish(String spot) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        List<String[]> rows = new ArrayList<>();
        int cellCount = 0;
        for (CellReader.Sim s : cell.sims()) {
            for (CellReader.Cell c : s.cells) {
                rows.add(new String[]{time, spot, "cell", c.carrier, c.tech, c.band, c.channel, "", c.id,
                        num(c.signal), num(c.quality), num(c.sinr), Ui.ratingName(Ui.cellRating(c.signal)),
                        c.registered ? "registered" : "", ""});
                cellCount++;
            }
        }
        long age = wifi.ageSeconds();
        int wifiCount = 0;
        if (wifi.isWifiOn()) {
            for (WifiReader.Ap ap : wifi.results()) {
                rows.add(new String[]{time, spot, "wifi", ap.ssid, ap.standard, ap.band,
                        String.valueOf(ap.channel), String.valueOf(ap.freq), ap.bssid, String.valueOf(ap.rssi),
                        "", "", Ui.ratingName(Ui.wifiRating(ap.rssi)), ap.connected ? "connected" : "",
                        String.valueOf(age)});
                wifiCount++;
            }
        }
        measuring = false;
        recordBtn.setEnabled(true);
        recordBtn.setAlpha(1f);
        try {
            store.append(rows);
        } catch (IOException e) {
            status.setTextColor(Ui.RED);
            status.setText("Couldn't save: " + e.getMessage());
            return;
        }
        String msg = String.format(Locale.US, "Saved \"%s\": %d cell and %d Wi-Fi readings.", spot, cellCount, wifiCount);
        if (age > 15) {
            msg += " Wi-Fi results were " + age + "s old because Android limited scanning; "
                    + "turning off \"Wi-Fi scan throttling\" in Developer options fixes this.";
        }
        if (!wifi.isWifiOn()) msg += " Wi-Fi is off, so no Wi-Fi readings were taken.";
        status.setTextColor(Ui.GREEN);
        status.setText(msg);
        if (names.getPosition(spot) < 0) names.add(spot);
        spotName.setText("");
        renderResults();
    }

    private static String num(int v) {
        return v == CellReader.NA ? "" : String.valueOf(v);
    }

    // ---------------------------------------------------------------- Results

    private Set<String> spotNames() {
        Set<String> s = new LinkedHashSet<>();
        for (String[] r : store.rows()) s.add(r[SurveyStore.SPOT]);
        return s;
    }

    private void renderResults() {
        if (results == null) return;
        results.removeAllViews();
        List<Spot> spots = summarize();
        if (spots.isEmpty()) {
            LinearLayout c = Ui.card(results, null);
            c.addView(Ui.text(a, "No spots recorded yet.", 15, Ui.MUTED, false));
            return;
        }
        for (Spot s : spots) {
            LinearLayout c = Ui.card(results, null);
            c.addView(Ui.text(a, s.name, 17, Ui.TEXT, true));
            String sub = s.time + (s.recordings > 1 ? " · " + s.recordings + " recordings" : "")
                    + " · overall " + Ui.ratingName(s.score);
            TextView st = Ui.text(a, sub, 12, Ui.ratingColor(s.score), false);
            st.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 6));
            c.addView(st);
            for (Line l : s.lines) {
                TextView t = Ui.text(a, "● " + l.text, 14, Ui.ratingColor(l.rating), false);
                t.setPadding(0, Ui.dp(a, 3), 0, 0);
                c.addView(t);
            }
            final String name = s.name;
            c.setOnLongClickListener(v -> {
                confirmDeleteSpot(name);
                return true;
            });
        }
    }

    private List<Spot> summarize() {
        Map<String, String> latest = new HashMap<>();
        Map<String, Set<String>> times = new LinkedHashMap<>();
        for (String[] r : store.rows()) {
            String spot = r[SurveyStore.SPOT];
            String t = r[SurveyStore.TIME];
            Set<String> ts = times.get(spot);
            if (ts == null) {
                ts = new LinkedHashSet<>();
                times.put(spot, ts);
            }
            ts.add(t);
            String cur = latest.get(spot);
            if (cur == null || t.compareTo(cur) > 0) latest.put(spot, t);
        }
        String officeName = office == null ? "" : office.getText().toString().trim();

        List<Spot> out = new ArrayList<>();
        for (Map.Entry<String, Set<String>> e : times.entrySet()) {
            Spot s = new Spot();
            s.name = e.getKey();
            s.time = latest.get(s.name);
            s.recordings = e.getValue().size();
            List<String[]> rows = new ArrayList<>();
            for (String[] r : store.rows()) {
                if (r[SurveyStore.SPOT].equals(s.name) && r[SurveyStore.TIME].equals(s.time)) rows.add(r);
            }
            addWifiLine(s, rows, officeName);
            addCellLines(s, rows);
            for (Line l : s.lines) s.score = Math.min(s.score, Math.max(0, l.rating));
            out.add(s);
        }
        out.sort((x, y) -> x.score != y.score ? Integer.compare(x.score, y.score) : x.name.compareToIgnoreCase(y.name));
        return out;
    }

    private void addWifiLine(Spot s, List<String[]> rows, String officeName) {
        String target = officeName;
        boolean anyWifi = false;
        for (String[] r : rows) {
            if (!r[SurveyStore.TYPE].equals("wifi")) continue;
            anyWifi = true;
            if (target.isEmpty() && r[SurveyStore.IN_USE].equals("connected")) target = r[SurveyStore.NETWORK];
        }
        if (target.isEmpty()) {
            String[] best = null;
            for (String[] r : rows) {
                if (r[SurveyStore.TYPE].equals("wifi") && (best == null || sig(r) > sig(best))) best = r;
            }
            if (best == null) {
                s.lines.add(new Line(anyWifi ? "Wi-Fi: no readings" : "Wi-Fi: none recorded", -1));
            } else {
                int r = Ui.wifiRating(sig(best));
                s.lines.add(new Line(String.format(Locale.US, "Wi-Fi strongest: \"%s\" %d dBm · %s",
                        best[SurveyStore.NETWORK], sig(best), Ui.ratingName(r)), r));
            }
            return;
        }
        String[] best = null;
        int count = 0;
        for (String[] r : rows) {
            if (!r[SurveyStore.TYPE].equals("wifi") || !r[SurveyStore.NETWORK].equals(target)) continue;
            count++;
            if (best == null || sig(r) > sig(best)) best = r;
        }
        if (best == null) {
            s.lines.add(new Line(anyWifi
                    ? "Wi-Fi \"" + target + "\": not heard here. Possible dead zone"
                    : "Wi-Fi: no readings (Wi-Fi was off or not scanned)", anyWifi ? 0 : -1));
            return;
        }
        int r = Ui.wifiRating(sig(best));
        s.lines.add(new Line(String.format(Locale.US, "Wi-Fi \"%s\": %d dBm · %s · %s ch %s · %d access point%s heard",
                target, sig(best), Ui.ratingName(r), best[SurveyStore.BAND], best[SurveyStore.CHANNEL],
                count, count == 1 ? "" : "s"), r));
    }

    private void addCellLines(Spot s, List<String[]> rows) {
        boolean any = false;
        for (String[] r : rows) {
            if (!r[SurveyStore.TYPE].equals("cell") || !r[SurveyStore.IN_USE].equals("registered")) continue;
            any = true;
            int dbm = sig(r);
            int rating = Ui.cellRating(dbm);
            String band = r[SurveyStore.BAND].isEmpty() ? "" : " " + r[SurveyStore.BAND];
            String value = dbm == Integer.MAX_VALUE ? "signal not reported" : dbm + " dBm · " + Ui.ratingName(rating);
            s.lines.add(new Line(r[SurveyStore.NETWORK] + " " + r[SurveyStore.TECH] + band + ": " + value, rating));
        }
        if (!any) s.lines.add(new Line("Cellular: no service recorded", 0));
    }

    private static int sig(String[] r) {
        try {
            return Integer.parseInt(r[SurveyStore.SIGNAL]);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    // ---------------------------------------------------------------- Export / delete

    private void export() {
        if (store.rows().isEmpty()) {
            toast("Nothing to export yet");
            return;
        }
        String name = "phone-stats-survey-" + new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(new Date()) + ".csv";
        Uri uri;
        try {
            uri = store.exportToDownloads(a, name);
        } catch (IOException e) {
            toast("Export failed: " + e.getMessage());
            return;
        }
        toast("Saved to Downloads/" + name);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/csv");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, "Signal survey " + name);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(Intent.createChooser(send, "Share survey CSV"));
        } catch (Exception ignored) {
            // Saved to Downloads either way.
        }
    }

    private void confirmDeleteSpot(String name) {
        new AlertDialog.Builder(a)
                .setTitle("Delete \"" + name + "\"?")
                .setMessage("This removes every recording for this spot.")
                .setPositiveButton("Delete", (d, w) -> {
                    try {
                        store.deleteSpot(name);
                    } catch (IOException e) {
                        toast("Couldn't delete: " + e.getMessage());
                    }
                    names.remove(name);
                    renderResults();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmClear() {
        if (store.rows().isEmpty()) {
            toast("Nothing to delete");
            return;
        }
        new AlertDialog.Builder(a)
                .setTitle("Delete all survey data?")
                .setMessage("This removes every recorded spot. Tap Export CSV first if you want to keep a copy.")
                .setPositiveButton("Delete all", (d, w) -> {
                    try {
                        store.clear();
                    } catch (IOException e) {
                        toast("Couldn't delete: " + e.getMessage());
                    }
                    names.clear();
                    renderResults();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(a, s, Toast.LENGTH_LONG).show();
    }
}
