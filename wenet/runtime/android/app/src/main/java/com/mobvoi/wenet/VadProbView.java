package com.mobvoi.wenet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Scrolling bar chart showing Silero VAD speech probability (0.0 ~ 1.0).
 */
public class VadProbView extends View {

    private static final int BAR_COUNT = 200;
    private static final int REDRAW_INTERVAL_MS = 100;
    private float speechThreshold = 0.5f;
    private float silenceThreshold = 0.3f;

    private final float[] probBuffer = new float[BAR_COUNT];
    private final Paint barPaint = new Paint();
    private final Paint linePaint = new Paint();
    private final Paint bgPaint = new Paint();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void setThresholds(float speech, float silence) {
        this.speechThreshold = speech;
        this.silenceThreshold = silence;
    }

    // DAW playback mode
    private boolean playbackMode = false;
    private float cursorFraction = -1f;
    private final Paint cursorPaint = new Paint();

    public VadProbView(Context context) {
        super(context);
        init();
    }

    public VadProbView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VadProbView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        bgPaint.setColor(0xFF1A1A2E);
        linePaint.setStrokeWidth(1f);
        labelPaint.setColor(0x99FFFFFF);
        labelPaint.setTextSize(18f);
        cursorPaint.setColor(0xFFFF0000);
        cursorPaint.setStrokeWidth(3f);
    }

    public void addProb(float prob) {
        System.arraycopy(probBuffer, 1, probBuffer, 0, BAR_COUNT - 1);
        probBuffer[BAR_COUNT - 1] = prob;
    }

    public void zero() {
        java.util.Arrays.fill(probBuffer, 0f);
    }

    /** Set full probability data for DAW playback mode. */
    public void setFullProbData(float[] probs) {
        if (probs == null || probs.length == 0) return;
        playbackMode = true;
        cursorFraction = 0f;
        for (int i = 0; i < BAR_COUNT; i++) {
            int srcIdx = (int) ((long) i * probs.length / BAR_COUNT);
            probBuffer[i] = probs[Math.min(srcIdx, probs.length - 1)];
        }
        postInvalidate();
    }

    public void setCursorPosition(float fraction) {
        cursorFraction = fraction;
        postInvalidate();
    }

    public void clearPlaybackMode() {
        playbackMode = false;
        cursorFraction = -1f;
        zero();
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        // Background
        canvas.drawRect(0, 0, w, h, bgPaint);

        float barWidth = (float) w / BAR_COUNT;

        // Draw threshold lines
        float speechY = h * (1f - speechThreshold);
        float silenceY = h * (1f - silenceThreshold);
        linePaint.setColor(0x40FF6B6B);
        canvas.drawLine(0, speechY, w, speechY, linePaint);
        linePaint.setColor(0x406BCF7F);
        canvas.drawLine(0, silenceY, w, silenceY, linePaint);

        // Draw bars
        for (int i = 0; i < BAR_COUNT; i++) {
            float prob = probBuffer[i];
            if (prob <= 0.001f) continue;
            float barH = prob * h;
            float left = i * barWidth;
            float right = left + barWidth;

            if (prob >= speechThreshold) {
                barPaint.setColor(0xCCFF6B6B);  // red for speech
            } else if (prob >= silenceThreshold) {
                barPaint.setColor(0xCCFFBB33);  // yellow for uncertain
            } else {
                barPaint.setColor(0x664CAF50);  // dim green for silence
            }
            canvas.drawRect(left, h - barH, right, h, barPaint);
        }

        // Label
        canvas.drawText("VAD", 4, h - 4, labelPaint);

        // Cursor in playback mode
        if (playbackMode && cursorFraction >= 0f) {
            float cx = cursorFraction * w;
            canvas.drawLine(cx, 0, cx, h, cursorPaint);
        }

        if (!playbackMode) {
            postInvalidateDelayed(REDRAW_INTERVAL_MS);
        }
    }
}
