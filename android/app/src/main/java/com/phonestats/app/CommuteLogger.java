package com.phonestats.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The commute log engine. One shared instance for the whole app, so it keeps running in the
 * background (inside LogService) when the screen is off or you switch apps.
 * It only runs between Start and Stop, and can stop itself after 30 minutes.
 */
final class CommuteLogger {

    interface Listener {
        void onLogUpdate();
    }

    static final long SAMPLE_MS = 2000;
    static final long AUTO_STOP_MS = 30 * 60 * 1000L;
    private static final long MAX_WAKE_MS = 4 * 60 * 60 * 1000L; // safety cap when auto-stop is off
    private static final int TIMEOUT_MS = 3000;
    // A tiny Google page that exists only to answer "is the internet working?" (HTTP 204, no content)
    private static final String PROBE_URL = "https://www.gstatic.com/generate_204";
    private static final String PREF_AUTO_STOP = "log_auto_stop";
    private static final String[] HEADER = {"time", "elapsed_s", "network", "data_ok", "latency_ms", "error",
            "carrier", "tech", "band", "signal_dbm", "rsrq_db", "sinr_db", "wifi_name", "wifi_dbm", "note"};

    private static CommuteLogger instance;

    static synchronized CommuteLogger get(Context c) {
        if (instance == null) instance = new CommuteLogger(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final CellReader cell;
    private final WifiReader wifi;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private PowerManager.WakeLock wakeLock;

    private boolean running = false;
    private boolean background = false;
    private boolean probeInFlight = false;
    private File file;
    private long startMs;
    private int checks, okChecks, outages;
    private long outageStartMs = -1;
    private long longestOutageMs = 0;
    private Boolean lastOk = null;
    private long lastLatency = -1;
    private boolean serviceUp = true;
    private long serviceBackMs = -1;
    private final List<Long> recoveries = new ArrayList<>();
    private String lastNet = "none";
    private CellReader.Cell lastCell = null;
    private String stopReason = "";

    private final Runnable sampler = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            sample();
            handler.postDelayed(this, SAMPLE_MS);
        }
    };

    private final Runnable autoStopper = () -> stop("Stopped automatically after 30 minutes");

    private CommuteLogger(Context app) {
        this.app = app;
        this.cell = new CellReader(app, () -> { });
        this.wifi = new WifiReader(app, () -> { });
    }

    // ---------------------------------------------------------------- Public API

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    boolean isRunning() {
        return running;
    }

    boolean isBackground() {
        return background;
    }

    File file() {
        return file;
    }

    String stopReason() {
        return stopReason;
    }

    boolean autoStop() {
        return prefs().getBoolean(PREF_AUTO_STOP, true);
    }

    void setAutoStop(boolean on) {
        prefs().edit().putBoolean(PREF_AUTO_STOP, on).apply();
        if (running) {
            scheduleAutoStop();
            acquireWakeLock();
        }
        notifyListeners();
    }

    boolean hasLocationPermission() {
        return app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void start() throws IOException {
        if (running) return;
        String stamp = new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(new Date());
        file = new File(app.getFilesDir(), "commute-" + stamp + ".csv");
        try (Writer w = writer(false)) {
            w.write(SurveyStore.line(HEADER));
        }
        checks = okChecks = outages = 0;
        outageStartMs = -1;
        longestOutageMs = 0;
        lastOk = null;
        lastLatency = -1;
        serviceUp = true;
        serviceBackMs = -1;
        recoveries.clear();
        lastNet = "none";
        lastCell = null;
        stopReason = "";
        startMs = SystemClock.elapsedRealtime();
        running = true;
        acquireWakeLock();
        scheduleAutoStop();

        // Background mode needs Location permission (a location-type service is what lets Android
        // keep reading cell towers with the screen off).
        background = false;
        if (hasLocationPermission()) {
            try {
                app.startForegroundService(new Intent(app, LogService.class));
                background = true;
            } catch (Exception e) {
                background = false;
            }
        }
        writeRow(new String[]{now(), "0.0", "", "", "", "", "", "", "", "", "", "", "", "",
                background ? "started (background allowed)" : "started (screen-on only)"});
        handler.post(sampler);
        notifyListeners();
    }

    void stop(String reason) {
        if (!running) return;
        running = false;
        handler.removeCallbacks(sampler);
        handler.removeCallbacks(autoStopper);
        long now = SystemClock.elapsedRealtime();
        if (outageStartMs >= 0) longestOutageMs = Math.max(longestOutageMs, now - outageStartMs);
        stopReason = reason;
        writeRow(new String[]{now(), seconds(now), "", "", "", "", "", "", "", "", "", "", "", "", reason});
        releaseWakeLock();
        if (background) {
            try {
                app.stopService(new Intent(app, LogService.class));
            } catch (Exception ignored) {
            }
        }
        background = false;
        notifyListeners();
    }

    // ---------------------------------------------------------------- Text for the screen and notification

    String shortStatus() {
        if (!running) return stopReason.isEmpty() ? "Not running" : "Stopped";
        if (Boolean.TRUE.equals(lastOk)) return "Data working · " + lastLatency + " ms";
        if (Boolean.FALSE.equals(lastOk)) return "No data · " + currentOutageSecs() + " s";
        return "Checking…";
    }

    /** -1 = no checks yet, 1 = data working, 0 = no data */
    int state() {
        return lastOk == null ? -1 : (lastOk ? 1 : 0);
    }

    String detail() {
        StringBuilder d = new StringBuilder("Via ").append(lastNet.equals("none") ? "no network" : lastNet);
        CellReader.Cell c = lastCell;
        if (c == null) {
            d.append(" · no cell service");
        } else {
            d.append(" · ").append(c.carrier).append(' ').append(c.tech);
            if (!c.band.isEmpty()) d.append(' ').append(c.band);
            if (c.signal != CellReader.NA) {
                d.append(" · ").append(c.signal).append(" dBm ").append(Ui.ratingName(Ui.cellRating(c.signal)));
            }
        }
        return d.toString();
    }

    String summaryLine() {
        if (checks == 0) return "Starting…";
        String s = String.format(Locale.US, "Data worked %.0f%% · %d outages · %s",
                100.0 * okChecks / checks, outages, dur(elapsedSecs()));
        if (running && autoStop()) s += " · stops at 30m";
        return s;
    }

    String statsText() {
        if (checks == 0) return running ? "No checks yet." : "Start logging to see results.";
        long longest = longestOutageMs;
        if (running && outageStartMs >= 0) longest = Math.max(longest, SystemClock.elapsedRealtime() - outageStartMs);
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.US, "Logged for %s · %d checks\n", dur(elapsedSecs()), checks));
        s.append(String.format(Locale.US, "Data worked %.0f%% of the time\n", 100.0 * okChecks / checks));
        s.append(String.format(Locale.US, "Outages: %d · longest %s\n", outages, dur(longest / 1000)));
        if (recoveries.isEmpty()) {
            s.append("Signal back → data back: no stations measured yet");
        } else {
            long sum = 0;
            long max = 0;
            for (long r : recoveries) {
                sum += r;
                max = Math.max(max, r);
            }
            s.append(String.format(Locale.US, "Signal back → data back: avg %.1f s, worst %.1f s (%d times)",
                    sum / 1000.0 / recoveries.size(), max / 1000.0, recoveries.size()));
        }
        if (running && autoStop()) {
            long left = Math.max(0, (AUTO_STOP_MS - (SystemClock.elapsedRealtime() - startMs)) / 1000);
            s.append("\nStops automatically in ").append(dur(left));
        }
        return s.toString();
    }

    // ---------------------------------------------------------------- Sampling

    private void sample() {
        if (autoStop() && SystemClock.elapsedRealtime() - startMs >= AUTO_STOP_MS) {
            stop("Stopped automatically after 30 minutes");
            return;
        }
        if (hasLocationPermission() && locationOn()) cell.requestUpdate();
        trackService();
        if (probeInFlight) return; // previous check still waiting on a timeout
        probeInFlight = true;
        final String net = networkType();
        pool.execute(() -> {
            long t0 = SystemClock.elapsedRealtime();
            boolean ok = false;
            String err = "";
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(PROBE_URL).openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setUseCaches(false);
                int code = conn.getResponseCode();
                ok = code == 204 || code == 200;
                if (!ok) err = "HTTP " + code;
            } catch (Exception e) {
                err = e.getClass().getSimpleName();
            } finally {
                if (conn != null) conn.disconnect();
            }
            final long ms = SystemClock.elapsedRealtime() - t0;
            final boolean fOk = ok;
            final String fErr = err;
            handler.post(() -> {
                probeInFlight = false;
                record(net, fOk, ms, fErr);
            });
        });
    }

    /** Notes the moment cell signal comes back, to time how long data takes to follow. */
    private void trackService() {
        boolean up = servingCell() != null;
        if (up && !serviceUp && !Boolean.TRUE.equals(lastOk)) serviceBackMs = SystemClock.elapsedRealtime();
        if (!up) serviceBackMs = -1;
        serviceUp = up;
    }

    private void record(String net, boolean ok, long latency, String err) {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        checks++;
        if (ok) okChecks++;
        if (!ok && !Boolean.FALSE.equals(lastOk)) {
            outages++;
            outageStartMs = now;
        }
        if (ok && Boolean.FALSE.equals(lastOk) && outageStartMs >= 0) {
            longestOutageMs = Math.max(longestOutageMs, now - outageStartMs);
            outageStartMs = -1;
        }
        if (ok && serviceBackMs >= 0) {
            recoveries.add(now - serviceBackMs);
            serviceBackMs = -1;
        }
        lastOk = ok;
        lastLatency = ok ? latency : -1;
        lastNet = net;
        lastCell = servingCell();

        CellReader.Cell c = lastCell;
        String wName = "";
        String wDbm = "";
        if ("wifi".equals(net)) {
            String s = wifi.connectedSsid();
            WifiInfo info = wifi.connection();
            if (s != null) wName = s;
            if (info != null) wDbm = String.valueOf(info.getRssi());
        }
        writeRow(new String[]{now(), seconds(now), net, ok ? "1" : "0", ok ? String.valueOf(latency) : "", err,
                c == null ? "" : c.carrier, c == null ? "no service" : c.tech, c == null ? "" : c.band,
                c == null ? "" : num(c.signal), c == null ? "" : num(c.quality), c == null ? "" : num(c.sinr),
                wName, wDbm, ""});
        notifyListeners();
    }

    // ---------------------------------------------------------------- Helpers

    private void scheduleAutoStop() {
        handler.removeCallbacks(autoStopper);
        if (running && autoStop()) {
            long left = AUTO_STOP_MS - (SystemClock.elapsedRealtime() - startMs);
            handler.postDelayed(autoStopper, Math.max(0, left));
        }
    }

    /** Keeps the processor awake so checks continue with the screen off. Always time-limited. */
    private void acquireWakeLock() {
        PowerManager pm = app.getSystemService(PowerManager.class);
        if (pm == null) return;
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneStats:commuteLog");
            wakeLock.setReferenceCounted(false);
        }
        long left = autoStop() ? AUTO_STOP_MS - (SystemClock.elapsedRealtime() - startMs) + 60_000 : MAX_WAKE_MS;
        wakeLock.acquire(Math.max(60_000, left));
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onLogUpdate();
    }

    private CellReader.Cell servingCell() {
        for (CellReader.Sim s : cell.sims()) {
            for (CellReader.Cell c : s.cells) {
                if (c.registered && c.signal != CellReader.NA) return c;
            }
        }
        return null;
    }

    private boolean locationOn() {
        LocationManager lm = app.getSystemService(LocationManager.class);
        return lm != null && lm.isLocationEnabled();
    }

    private String networkType() {
        ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
        if (cm == null) return "none";
        Network n = cm.getActiveNetwork();
        if (n == null) return "none";
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        if (nc == null) return "none";
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
        return "other";
    }

    private void writeRow(String[] row) {
        if (file == null) return;
        try (Writer w = writer(true)) {
            w.write(SurveyStore.line(row));
        } catch (IOException ignored) {
        }
    }

    private Writer writer(boolean append) throws IOException {
        return new OutputStreamWriter(new FileOutputStream(file, append), StandardCharsets.UTF_8);
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences("phone_stats", Context.MODE_PRIVATE);
    }

    private long currentOutageSecs() {
        return outageStartMs >= 0 ? (SystemClock.elapsedRealtime() - outageStartMs) / 1000 : 0;
    }

    private long elapsedSecs() {
        return (SystemClock.elapsedRealtime() - startMs) / 1000;
    }

    private String seconds(long now) {
        return String.format(Locale.US, "%.1f", (now - startMs) / 1000.0);
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static String num(int v) {
        return v == CellReader.NA ? "" : String.valueOf(v);
    }

    static String dur(long secs) {
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m " + (secs % 60) + "s";
        return (secs / 3600) + "h " + ((secs % 3600) / 60) + "m";
    }
}
