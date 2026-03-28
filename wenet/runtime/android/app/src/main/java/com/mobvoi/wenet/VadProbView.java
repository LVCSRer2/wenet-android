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
    private static final int SAMPLES_PER_BAR = 512;  // match SpectrogramView FFT_SIZE
    private static final int REDRAW_INTERVAL_MS = 100;
    private float speechThreshold = 0.5f;
    private float silenceThreshold = 0.3f;

    private final float[] probBuffer = new float[BAR_COUNT];
    private final float[] personalProbBuffer = new float[BAR_COUNT]; // PersonalVAD target prob
    private float currentProb = 0f;
    private float currentPersonalProb = 0f;
    private int sampleAccum = 0;
    private final Paint barPaint = new Paint();
    private final Paint linePaint = new Paint();
    private final Paint bgPaint = new Paint();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint personalLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        personalLinePaint.setColor(0xFF00E5FF); // cyan for PersonalVAD
        personalLinePaint.setStrokeWidth(2.5f);
    }

    public void setCurrentProb(float prob) {
        this.currentProb = prob;
    }

    public void setCurrentPersonalProb(float prob) {
        this.currentPersonalProb = prob;
    }

    /** Sample-count based advancement: adds bars at SAMPLES_PER_BAR rate. */
    public void addSamples(int numSamples) {
        sampleAccum += numSamples;
        while (sampleAccum >= SAMPLES_PER_BAR) {
            System.arraycopy(probBuffer, 1, probBuffer, 0, BAR_COUNT - 1);
            probBuffer[BAR_COUNT - 1] = currentProb;
            System.arraycopy(personalProbBuffer, 1, personalProbBuffer, 0, BAR_COUNT - 1);
            personalProbBuffer[BAR_COUNT - 1] = currentPersonalProb;
            sampleAccum -= SAMPLES_PER_BAR;
        }
    }

    public void addProb(float prob) {
        System.arraycopy(probBuffer, 1, probBuffer, 0, BAR_COUNT - 1);
        probBuffer[BAR_COUNT - 1] = prob;
    }

    public void zero() {
        java.util.Arrays.fill(probBuffer, 0f);
        java.util.Arrays.fill(personalProbBuffer, 0f);
        sampleAccum = 0;
        currentProb = 0f;
        currentPersonalProb = 0f;
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

        // PersonalVAD target prob: cyan line graph overlay
        float halfBar = barWidth / 2f;
        for (int i = 1; i < BAR_COUNT; i++) {
            float x1 = (i - 1) * barWidth + halfBar;
            float y1 = h * (1f - personalProbBuffer[i - 1]);
            float x2 = i * barWidth + halfBar;
            float y2 = h * (1f - personalProbBuffer[i]);
            canvas.drawLine(x1, y1, x2, y2, personalLinePaint);
        }

        // Label
        canvas.drawText("VAD", 4, h - 4, labelPaint);
        // PersonalVAD label (top-right)
        canvas.drawText("P-VAD", w - labelPaint.measureText("P-VAD") - 4, 18f, labelPaint);

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
