package com.phonestats.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;

/** Screen for the commute log. The logging itself lives in CommuteLogger so it can run in the background. */
final class LogTab implements CommuteLogger.Listener {

    private static final int REQ_NOTIFICATIONS = 8;

    private final MainActivity a;
    private final CommuteLogger logger;
    private final Handler handler = new Handler(Looper.getMainLooper());
    final View view;

    private TextView status, statusDetail, stats, modeNote;
    private BarView statusBar;
    private Button startBtn, exportBtn, allowBtn;
    private Switch autoStop;

    // Refreshes the counters once a second while this screen is visible
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    LogTab(MainActivity a) {
        this.a = a;
        this.logger = CommuteLogger.get(a);
        this.view = build();
        logger.addListener(this);
    }

    void release() {
        logger.removeListener(this);
        handler.removeCallbacks(ticker);
        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private View build() {
        LinearLayout col = Ui.column(a);

        TextView intro = Ui.text(a, "Tap Start when you board. Every 2 seconds the app checks whether data "
                + "actually works and records the cell signal, so you can see exactly where it fails.",
                13, Ui.MUTED, false);
        intro.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), Ui.dp(a, 12));
        col.addView(intro);

        LinearLayout c = Ui.card(col, "RIGHT NOW");
        status = Ui.text(a, "Not running", 24, Ui.MUTED, true);
        c.addView(status);
        statusBar = Ui.bar(c);
        statusDetail = Ui.text(a, "", 13, Ui.MUTED, false);
        c.addView(statusDetail);

        c = Ui.card(col, "THIS RIDE");
        stats = Ui.text(a, "", 14, Ui.TEXT, false);
        stats.setLineSpacing(0, 1.3f);
        c.addView(stats);

        c = Ui.card(col, "SETTINGS");
        autoStop = new Switch(a);
        autoStop.setText("Stop automatically after 30 minutes");
        autoStop.setTextColor(Ui.TEXT);
        autoStop.setTextSize(15);
        autoStop.setChecked(logger.autoStop());
        autoStop.setOnCheckedChangeListener((b, on) -> logger.setAutoStop(on));
        c.addView(autoStop);
        TextView autoNote = Ui.text(a, "In case you forget. When off, it still stops after 4 hours as a safety limit.",
                12, Ui.MUTED, false);
        autoNote.setPadding(0, Ui.dp(a, 4), 0, 0);
        c.addView(autoNote);
        modeNote = Ui.text(a, "", 13, Ui.TEXT, false);
        modeNote.setPadding(0, Ui.dp(a, 12), 0, 0);
        c.addView(modeNote);
        allowBtn = Ui.button(a, "Allow location access", false);
        allowBtn.setOnClickListener(v -> a.requestPerms());
        c.addView(allowBtn, Ui.spaced(a, 8));

        startBtn = Ui.button(a, "Start logging", true);
        startBtn.setOnClickListener(v -> {
            if (logger.isRunning()) logger.stop("Stopped by you");
            else start();
        });
        col.addView(startBtn, Ui.spaced(a, 4));

        exportBtn = Ui.button(a, "Export CSV", false);
        exportBtn.setOnClickListener(v -> export());
        col.addView(exportBtn, Ui.spaced(a, 10));

        render();
        return Ui.scroll(col);
    }

    void onShown() {
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override
    public void onLogUpdate() {
        render();
    }

    // ---------------------------------------------------------------- Actions

    private void start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
        try {
            logger.start();
        } catch (IOException e) {
            toast("Couldn't create the log file: " + e.getMessage());
        }
    }

    private void export() {
        File file = logger.file();
        if (file == null || !file.exists()) {
            toast("Nothing to export yet");
            return;
        }
        Uri uri;
        try {
            uri = SurveyStore.exportFile(a, file, file.getName());
        } catch (IOException e) {
            toast("Export failed: " + e.getMessage());
            return;
        }
        toast("Saved to Downloads/" + file.getName());
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/csv");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, "Commute log " + file.getName());
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(Intent.createChooser(send, "Share commute log"));
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- Display

    private void render() {
        boolean running = logger.isRunning();
        status.setText(running ? logger.shortStatus()
                : (logger.stopReason().isEmpty() ? "Not running" : logger.stopReason()));
        int st = logger.state();
        int color = !running ? Ui.MUTED : st == 1 ? Ui.GREEN : st == 0 ? Ui.RED : Ui.MUTED;
        status.setTextColor(color);
        statusBar.set(running ? 1f : 0f, color);
        statusDetail.setText(running ? logger.detail() : "");
        stats.setText(logger.statsText());

        startBtn.setText(running ? "Stop logging" : "Start logging");
        Ui.style(startBtn, !running);
        exportBtn.setVisibility(!running && logger.file() != null ? View.VISIBLE : View.GONE);
        if (autoStop.isChecked() != logger.autoStop()) autoStop.setChecked(logger.autoStop());

        boolean perm = logger.hasLocationPermission();
        if (running && logger.isBackground()) {
            modeNote.setText("Running in the background. You can lock the phone or use other apps; "
                    + "a notification shows the status and has a Stop button.");
        } else if (perm) {
            modeNote.setText("Background logging: on. After you tap Start you can lock the phone or use other "
                    + "apps; a notification shows the status and has a Stop button.");
        } else {
            modeNote.setText("Background logging needs Location permission (it's also what lets the app read "
                    + "cell signal). Without it, the log only runs while this screen is open.");
        }
        allowBtn.setVisibility(perm ? View.GONE : View.VISIBLE);

        // Without background mode, keep the screen on so logging doesn't pause
        if (running && !logger.isBackground()) {
            a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void toast(String s) {
        Toast.makeText(a, s, Toast.LENGTH_LONG).show();
    }
}
