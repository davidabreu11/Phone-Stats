package com.phonestats.app;

import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shows each SIM's connected tower(s) and nearby towers. */
final class CellTab {

    private static final int MAX_NEIGHBORS = 8;

    private final MainActivity a;
    private final CellReader cell;
    private final LinearLayout col;
    final View view;

    CellTab(MainActivity a, CellReader cell) {
        this.a = a;
        this.cell = cell;
        this.col = Ui.column(a);
        this.view = Ui.scroll(col);
    }

    void render() {
        col.removeAllViews();
        if (!a.hasLocationPermission()) {
            a.addPermissionCard(col, "Cell tower details count as location data on Android, "
                    + "so this tab needs Location permission. Choose \"Precise\" when asked.");
            return;
        }
        if (!a.isLocationOn()) {
            a.addLocationOffCard(col);
            return;
        }
        List<CellReader.Sim> sims = cell.sims();
        if (sims.isEmpty()) {
            col.addView(Ui.text(a, "Reading cell towers…", 15, Ui.MUTED, false));
            return;
        }
        for (CellReader.Sim s : sims) renderSim(s);

        TextView note = Ui.text(a, "Phones can only measure carriers they have an active SIM or eSIM for. "
                + "Signal ratings use RSRP: Excellent ≥ −80, Good ≥ −90, Fair ≥ −100, Poor ≥ −110 dBm.",
                12, Ui.MUTED, false);
        note.setPadding(Ui.dp(a, 4), 0, Ui.dp(a, 4), Ui.dp(a, 8));
        col.addView(note);
    }

    private void renderSim(CellReader.Sim s) {
        String title = (s.slot >= 0 ? "SIM " + (s.slot + 1) + " · " : "") + s.name.toUpperCase(Locale.US);
        LinearLayout c = Ui.card(col, title);
        if (s.error != null) {
            c.addView(Ui.text(a, s.error, 15, Ui.RED, false));
            return;
        }
        List<CellReader.Cell> reg = new ArrayList<>();
        List<CellReader.Cell> others = new ArrayList<>();
        for (CellReader.Cell x : s.cells) (x.registered ? reg : others).add(x);

        if (reg.isEmpty()) {
            c.addView(Ui.text(a, "Not connected to a tower right now", 16, Ui.RED, true));
        }
        for (CellReader.Cell x : reg) addMain(c, x);

        if (!others.isEmpty()) {
            TextView h = Ui.text(a, "NEARBY TOWERS (" + others.size() + ")", 12, Ui.MUTED, true);
            h.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 2));
            c.addView(h);
            for (int i = 0; i < Math.min(MAX_NEIGHBORS, others.size()); i++) addNeighbor(c, others.get(i));
        }

        long age = (SystemClock.elapsedRealtime() - s.updatedMs) / 1000;
        TextView u = Ui.text(a, "Updated " + (age < 2 ? "just now" : age + "s ago"), 12, Ui.MUTED, false);
        u.setPadding(0, Ui.dp(a, 8), 0, 0);
        c.addView(u);
    }

    private void addMain(LinearLayout c, CellReader.Cell x) {
        int r = Ui.cellRating(x.signal);
        String head = x.tech + (x.band.isEmpty() ? "" : " · " + x.band);
        TextView t = Ui.text(a, head, 22, Ui.TEXT, true);
        t.setPadding(0, Ui.dp(a, 4), 0, 0);
        c.addView(t);
        BarView b = Ui.bar(c);
        b.set(Ui.cellFraction(x.signal), Ui.ratingColor(r));

        TextView sig = Ui.text(a, (x.signal == CellReader.NA ? "Signal not reported"
                : x.signal + " dBm · " + Ui.ratingName(r)), 16, Ui.ratingColor(r), true);
        c.addView(sig);

        StringBuilder d = new StringBuilder();
        if (x.quality != CellReader.NA) d.append("RSRQ ").append(x.quality).append(" dB");
        if (x.sinr != CellReader.NA) append(d, "SINR " + x.sinr + " dB");
        if (!x.bandwidth.isEmpty()) append(d, x.bandwidth + " wide");
        if (d.length() > 0) d.append('\n');
        d.append(x.carrier);
        if (!x.id.isEmpty()) d.append(" · ").append(x.id);
        if (!x.channel.isEmpty()) d.append(" · ").append(x.channel);
        c.addView(Ui.text(a, d.toString(), 13, Ui.MUTED, false));
    }

    private void addNeighbor(LinearLayout c, CellReader.Cell x) {
        int r = Ui.cellRating(x.signal);
        String sig = x.signal == CellReader.NA ? "   —   " : String.format(Locale.US, "%4d dBm", x.signal);
        String line = String.format(Locale.US, "%s  %-4s %-7s %s", sig, x.tech, x.band.isEmpty() ? "?" : x.band,
                x.id.isEmpty() ? x.carrier : x.id + " · " + x.carrier);
        TextView t = Ui.text(a, line, 12, Ui.ratingColor(r), false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setPadding(0, Ui.dp(a, 3), 0, 0);
        c.addView(t);
    }

    private static void append(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append(" · ");
        sb.append(s);
    }
}
