package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class MiniProgressView extends View {

    private final Paint trackPaint;
    private final Paint playedPaint;

    private long position;
    private long duration;

    public MiniProgressView(Context context) {
        this(context, null);
    }

    public MiniProgressView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MiniProgressView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setColor(Color.WHITE);
        trackPaint.setAlpha(48);
        playedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        playedPaint.setColor(Color.WHITE);
        playedPaint.setAlpha(230);
    }

    public void setProgress(long position, long duration) {
        this.duration = Math.max(0, duration);
        this.position = Math.max(0, Math.min(position, this.duration));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (duration <= 0) return;
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;
        canvas.drawRect(0, 0, width, height, trackPaint);
        float playedWidth = width * (position / (float) duration);
        if (playedWidth > 0) canvas.drawRect(0, 0, playedWidth, height, playedPaint);
    }
}
