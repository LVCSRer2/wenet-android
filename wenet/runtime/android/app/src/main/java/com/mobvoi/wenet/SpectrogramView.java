package com.mobvoi.wenet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

public class SpectrogramView extends View {

    private static final int FFT_SIZE = 256;
    private static final int FREQ_BINS = FFT_SIZE / 2;
    private static final int MAX_COLUMNS = 200;

    private final Paint paint = new Paint();
    // Grid: [column][freqBin] storing packed ARGB colors
    private final int[][] grid = new int[MAX_COLUMNS][FREQ_BINS];
    private final Object lock = new Object();
    private int currentColumn = 0;
    private int totalColumns = 0;

    // Circular sample buffer for accumulating 256 samples
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
    }

    /**
     * Called from recording thread with raw PCM samples.
     * Accumulates samples and runs FFT when 256 samples are collected.
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
        // Apply Hann window and copy to FFT arrays
        for (int i = 0; i < FFT_SIZE; i++) {
            fftReal[i] = sampleBuffer[i] * hannWindow[i];
            fftImag[i] = 0.0;
        }

        // Radix-2 Cooley-Tukey FFT
        fft(fftReal, fftImag, FFT_SIZE);

        // Compute magnitude in dB, map to colors, and write to grid
        int[] col = new int[FREQ_BINS];
        for (int k = 0; k < FREQ_BINS; k++) {
            double mag = Math.sqrt(fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k]);
            double db = 20.0 * Math.log10(mag + 1e-10);
            double norm = (db + 20.0) / 100.0;
            norm = Math.max(0.0, Math.min(1.0, norm));
            // Store low freq at bottom: index 0 = top of view = highest freq bin
            col[FREQ_BINS - 1 - k] = heatmapColor(norm);
        }

        synchronized (lock) {
            grid[currentColumn] = col;
            currentColumn = (currentColumn + 1) % MAX_COLUMNS;
            totalColumns++;
        }

        postInvalidate();
    }

    /**
     * Radix-2 Cooley-Tukey in-place FFT.
     */
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
        int snapTotal;
        int[][] snapGrid;

        synchronized (lock) {
            snapColumn = currentColumn;
            snapTotal = totalColumns;
            // Shallow copy of references is fine — each col array is replaced atomically
            snapGrid = grid;
        }

        int count = Math.min(snapTotal, MAX_COLUMNS);
        if (count == 0) return;

        float colWidth = (float) viewW / count;
        float binH = (float) viewH / FREQ_BINS;

        for (int i = 0; i < count; i++) {
            int col;
            if (snapTotal <= MAX_COLUMNS) {
                col = i;
            } else {
                col = (snapColumn + i) % MAX_COLUMNS;
            }
            float left = i * colWidth;
            float right = (i + 1) * colWidth;
            int[] colData = snapGrid[col];
            for (int k = 0; k < FREQ_BINS; k++) {
                paint.setColor(colData[k]);
                canvas.drawRect(left, k * binH, right, (k + 1) * binH, paint);
            }
        }
    }

    public void clear() {
        synchronized (lock) {
            sampleCount = 0;
            currentColumn = 0;
            totalColumns = 0;
            for (int c = 0; c < MAX_COLUMNS; c++) {
                grid[c] = new int[FREQ_BINS];
            }
        }
        postInvalidate();
    }
}
