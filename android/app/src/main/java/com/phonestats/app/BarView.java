package com.phonestats.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A simple rounded progress bar. */
public class BarView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float fraction = 0f;

    public BarView(Context context) {
        super(context);
        track.setColor(Ui.TRACK);
        fill.setColor(Ui.BLUE);
    }

    public void set(float value, int color) {
        if (Float.isNaN(value)) value = 0f;
        fraction = Math.max(0f, Math.min(1f, value));
        fill.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = getHeight();
        float radius = h / 2f;
        rect.set(0, 0, getWidth(), h);
        canvas.drawRoundRect(rect, radius, radius, track);
        if (fraction > 0f) {
            rect.right = Math.max(h, getWidth() * fraction);
            canvas.drawRoundRect(rect, radius, radius, fill);
        }
    }
}
