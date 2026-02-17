package com.mobvoi.wenet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

public class SpectrogramView extends View {

    private static final int FFT_SIZE = 512;
    private static final int FREQ_BINS = FFT_SIZE / 2;
    private static final int MAX_COLUMNS = 200;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private volatile double dbFloor = -10.0;
    private volatile double dbCeil = 80.0;

    // Offscreen bitmap: MAX_COLUMNS x FREQ_BINS, updated from recording thread
    private Bitmap offscreen;
    private final int[] pixelRow = new int[FREQ_BINS]; // temp buffer for one column
    private final Object lock = new Object();
    private int currentColumn = 0;
    private boolean wrapped = false;

    // Circular sample buffer
    private final short[] sampleBuffer = new short[FFT_SIZE];
    private int sampleCount = 0;

    // FFT working arrays
    private final double[] fftReal = new double[FFT_SIZE];
    private final double[] fftImag = new double[FFT_SIZE];

    // Hann window (precomputed)
    private final double[] hannWindow = new double[FFT_SIZE];

    public SpectrogramView(Context context) {
        super(context);
        init();
    }

    public SpectrogramView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SpectrogramView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        for (int i = 0; i < FFT_SIZE; i++) {
            hannWindow[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
        }
        offscreen = Bitmap.createBitmap(MAX_COLUMNS, FREQ_BINS, Bitmap.Config.ARGB_8888);
        offscreen.eraseColor(Color.BLACK);
    }

    /**
     * Called from recording thread with raw PCM samples.
     */
    public void addSamples(short[] samples, int length) {
        if (samples == null || length <= 0) return;
        for (int i = 0; i < length; i++) {
            sampleBuffer[sampleCount++] = samples[i];
            if (sampleCount == FFT_SIZE) {
                processWindow();
                sampleCount = 0;
            }
        }
    }

    private void processWindow() {
        for (int i = 0; i < FFT_SIZE; i++) {
            fftReal[i] = sampleBuffer[i] * hannWindow[i];
            fftImag[i] = 0.0;
        }

        fft(fftReal, fftImag, FFT_SIZE);

        // Compute colors for this column (low freq at bottom)
        for (int k = 0; k < FREQ_BINS; k++) {
            double mag = Math.sqrt(fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k]);
            mag /= FFT_SIZE;
            double db = 20.0 * Math.log10(mag + 1e-10);
            double floor = dbFloor;
            double ceil = dbCeil;
            double range = ceil - floor;
            if (range < 1.0) range = 1.0;
            double norm = (db - floor) / range;
            norm = Math.max(0.0, Math.min(1.0, norm));
            pixelRow[FREQ_BINS - 1 - k] = heatmapColor(norm);
        }

        // Write column directly to offscreen bitmap
        synchronized (lock) {
            offscreen.setPixels(pixelRow, 0, 1, currentColumn, 0, 1, FREQ_BINS);
            currentColumn++;
            if (currentColumn >= MAX_COLUMNS) {
                currentColumn = 0;
                wrapped = true;
            }
        }

        postInvalidate();
    }

    private static void fft(double[] real, double[] imag, int n) {
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (i < j) {
                double tr = real[i]; real[i] = real[j]; real[j] = tr;
                double ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
            int m = n >> 1;
            while (m >= 1 && j >= m) {
                j -= m;
                m >>= 1;
            }
            j += m;
        }

        for (int step = 2; step <= n; step <<= 1) {
            int halfStep = step >> 1;
            double angle = -2.0 * Math.PI / step;
            double wR = Math.cos(angle);
            double wI = Math.sin(angle);
            for (int k = 0; k < n; k += step) {
                double curR = 1.0, curI = 0.0;
                for (int m2 = 0; m2 < halfStep; m2++) {
                    int idx1 = k + m2;
                    int idx2 = idx1 + halfStep;
                    double tR = curR * real[idx2] - curI * imag[idx2];
                    double tI = curR * imag[idx2] + curI * real[idx2];
                    real[idx2] = real[idx1] - tR;
                    imag[idx2] = imag[idx1] - tI;
                    real[idx1] += tR;
                    imag[idx1] += tI;
                    double newCurR = curR * wR - curI * wI;
                    curI = curR * wI + curI * wR;
                    curR = newCurR;
                }
            }
        }
    }

    private static int heatmapColor(double v) {
        int r, g, b;
        if (v < 0.25) {
            double t = v / 0.25;
            r = 0; g = 0; b = (int) (255 * t);
        } else if (v < 0.5) {
            double t = (v - 0.25) / 0.25;
            r = 0; g = (int) (255 * t); b = (int) (255 * (1.0 - t));
        } else if (v < 0.75) {
            double t = (v - 0.5) / 0.25;
            r = (int) (255 * t); g = 255; b = 0;
        } else {
            double t = (v - 0.75) / 0.25;
            r = 255; g = (int) (255 * (1.0 - t)); b = 0;
        }
        return Color.rgb(r, g, b);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);

        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW == 0 || viewH == 0) return;

        int snapColumn;
        boolean snapWrapped;
        synchronized (lock) {
            snapColumn = currentColumn;
            snapWrapped = wrapped;
        }

        if (snapColumn == 0 && !snapWrapped) return;

        Rect dst = new Rect(0, 0, viewW, viewH);

        if (!snapWrapped) {
            // Not yet filled: draw columns 0..snapColumn-1
            Rect src = new Rect(0, 0, snapColumn, FREQ_BINS);
            canvas.drawBitmap(offscreen, src, dst, bitmapPaint);
        } else {
            // Circular: oldest is at snapColumn, newest is at snapColumn-1
            // Draw two strips: [snapColumn..MAX_COLUMNS-1] then [0..snapColumn-1]
            int rightPart = MAX_COLUMNS - snapColumn;
            int leftW = (int) ((long) rightPart * viewW / MAX_COLUMNS);

            if (rightPart > 0) {
                Rect src1 = new Rect(snapColumn, 0, MAX_COLUMNS, FREQ_BINS);
                Rect dst1 = new Rect(0, 0, leftW, viewH);
                canvas.drawBitmap(offscreen, src1, dst1, bitmapPaint);
            }
            if (snapColumn > 0) {
                Rect src2 = new Rect(0, 0, snapColumn, FREQ_BINS);
                Rect dst2 = new Rect(leftW, 0, viewW, viewH);
                canvas.drawBitmap(offscreen, src2, dst2, bitmapPaint);
            }
        }
    }

    public void setDbFloor(double floor) {
        this.dbFloor = floor;
    }

    public void setDbCeil(double ceil) {
        this.dbCeil = ceil;
    }

    public void clear() {
        synchronized (lock) {
            sampleCount = 0;
            currentColumn = 0;
            wrapped = false;
            offscreen.eraseColor(Color.BLACK);
        }
        postInvalidate();
    }
}
