package com.phonestats.app;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared colors, layout helpers and signal ratings. */
final class Ui {
    static final int BG = 0xFF0F1115;
    static final int CARD = 0xFF1A1D23;
    static final int TEXT = 0xFFE8EAED;
    static final int MUTED = 0xFF9AA0A6;
    static final int BLUE = 0xFF8AB4F8;
    static final int GREEN = 0xFF81C995;
    static final int YELLOW = 0xFFFDD663;
    static final int ORANGE = 0xFFFCAD70;
    static final int RED = 0xFFF28B82;
    static final int TRACK = 0xFF2D3139;

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final String[] RATING = {"Very poor", "Poor", "Fair", "Good", "Excellent"};

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static LinearLayout column(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 16);
        col.setPadding(p, dp(c, 4), p, p);
        return col;
    }

    static ScrollView scroll(LinearLayout col) {
        ScrollView s = new ScrollView(col.getContext());
        s.setBackgroundColor(BG);
        s.addView(col, new ScrollView.LayoutParams(MATCH, WRAP));
        return s;
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    /** Adds a rounded card to parent; title may be null. */
    static LinearLayout card(LinearLayout parent, String title) {
        Context c = parent.getContext();
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, dp(c, 16)));
        int p = dp(c, 16);
        card.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.bottomMargin = dp(c, 12);
        parent.addView(card, lp);
        if (title != null) {
            TextView t = text(c, title, 12, MUTED, true);
            t.setLetterSpacing(0.08f);
            t.setPadding(0, 0, 0, dp(c, 6));
            card.addView(t);
        }
        return card;
    }

    static BarView bar(LinearLayout parent) {
        Context c = parent.getContext();
        BarView b = new BarView(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, dp(c, 8));
        lp.topMargin = dp(c, 6);
        lp.bottomMargin = dp(c, 6);
        parent.addView(b, lp);
        return b;
    }

    static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        int h = dp(c, 12);
        int v = dp(c, 10);
        b.setPadding(h, v, h, v);
        style(b, primary);
        return b;
    }

    static void style(Button b, boolean active) {
        b.setBackground(rounded(active ? BLUE : TRACK, dp(b.getContext(), 20)));
        b.setTextColor(active ? BG : TEXT);
    }

    static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        styleInput(e, hint);
        return e;
    }

    static void styleInput(EditText e, String hint) {
        Context c = e.getContext();
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setBackground(rounded(TRACK, dp(c, 10)));
        int p = dp(c, 12);
        e.setPadding(p, p, p, p);
    }

    static LinearLayout.LayoutParams spaced(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(c, topDp);
        return lp;
    }

    // ---- Ratings: -1 = no reading, 0..4 = very poor..excellent

    /** LTE RSRP / 5G SS-RSRP in dBm. */
    static int cellRating(int dbm) {
        if (dbm == Integer.MAX_VALUE) return -1;
        if (dbm >= -80) return 4;
        if (dbm >= -90) return 3;
        if (dbm >= -100) return 2;
        if (dbm >= -110) return 1;
        return 0;
    }

    /** Wi-Fi RSSI in dBm. */
    static int wifiRating(int dbm) {
        if (dbm == Integer.MAX_VALUE) return -1;
        if (dbm >= -60) return 4;
        if (dbm >= -67) return 3;
        if (dbm >= -75) return 2;
        if (dbm >= -82) return 1;
        return 0;
    }

    static String ratingName(int r) {
        return r < 0 ? "No reading" : RATING[Math.min(4, r)];
    }

    static int ratingColor(int r) {
        if (r < 0) return MUTED;
        if (r >= 3) return GREEN;
        if (r == 2) return YELLOW;
        if (r == 1) return ORANGE;
        return RED;
    }

    /** Bar fill for a cell signal: -125 dBm = empty, -70 dBm = full. */
    static float cellFraction(int dbm) {
        return dbm == Integer.MAX_VALUE ? 0f : (dbm + 125) / 55f;
    }

    /** Bar fill for a Wi-Fi signal: -95 dBm = empty, -35 dBm = full. */
    static float wifiFraction(int dbm) {
        return (dbm + 95) / 60f;
    }

    static int levelColor(float f) {
        return f < 0.6f ? GREEN : f < 0.85f ? YELLOW : RED;
    }
}
