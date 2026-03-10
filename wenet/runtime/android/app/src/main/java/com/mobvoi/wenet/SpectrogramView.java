package com.mobvoi.wenet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.OverScroller;
import java.io.File;
import java.io.FileInputStream;

public class SpectrogramView extends View {

    private static final int FFT_SIZE = 512;
    private static final int FREQ_BINS = FFT_SIZE / 2;
    private static final int MAX_COLUMNS_STREAMING = 200;  // for streaming mode circular buffer
    private static final int SAMPLE_RATE = 8000;
    private static final float DEFAULT_VISIBLE_SECONDS = 20f;
    private static final float MIN_VISIBLE_SECONDS = 0.5f;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint cursorPaint = new Paint();
    private volatile double dbFloor = -20.0;
    private volatile double dbCeil = 80.0;

    // DAW playback mode
    private boolean playbackMode = false;
    private float cursorFraction = -1f;

    private int totalDurationMs = 0;
    private long totalSamples = 0;
    private float visibleSeconds = DEFAULT_VISIBLE_SECONDS;
    private float scrollOffsetMs = 0f;
    private boolean userScrolling = false;

    // On-demand window rendering
    private String pcmFilePath = null;
    private volatile boolean windowLoading = false;
    private Bitmap windowBitmap = null;
    private float lastRenderedOffsetMs = -100000f;
    private float lastRenderedVisibleSec = -1f;
    private int lastRenderedWidth = -1;

    // Touch handling
    private float touchStartX = 0;
    private float touchStartOffsetMs = 0;
    private VelocityTracker velocityTracker = null;
    private OverScroller scroller = null;
    private android.view.ScaleGestureDetector scaleDetector = null;
    private OnPlaybackSeekListener seekListener = null;

    public interface OnPlaybackSeekListener {
        void onSeek(int ms);
        void onZoomChanged(float visibleSeconds);
    }

    // Streaming mode: MAX_COLUMNS_STREAMING x FREQ_BINS
    private Bitmap offscreen;
    private final int[] pixelRow = new int[FREQ_BINS];
    private final Object lock = new Object();
    private int currentColumn = 0;
    private boolean wrapped = false;

    // Circular sample buffer
    private final short[] sampleBuffer = new short[FFT_SIZE];
    private int sampleCount = 0;

    // FFT working arrays
    private final double[] fftReal = new double[FFT_SIZE];
    private final double[] fftImag = new double[FFT_SIZE];

    // Hann window
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
        offscreen = Bitmap.createBitmap(MAX_COLUMNS_STREAMING, FREQ_BINS, Bitmap.Config.ARGB_8888);
        offscreen.eraseColor(Color.BLACK);
        scroller = new OverScroller(getContext());

        scaleDetector = new android.view.ScaleGestureDetector(getContext(), new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(android.view.ScaleGestureDetector detector) {
                if (!playbackMode || totalDurationMs <= 0) return false;

                float oldVisibleSec = visibleSeconds;
                float factor = detector.getScaleFactor();
                if (factor > 1.0f) factor = 1.0f + (factor - 1.0f) * 1.5f;
                else factor = 1.0f - (1.0f - factor) * 1.5f;

                float newVisibleSec = oldVisibleSec / factor;
                float maxVisibleSec = totalDurationMs / 1000.0f;
                newVisibleSec = Math.max(MIN_VISIBLE_SECONDS, Math.min(newVisibleSec, maxVisibleSec));

                if (Math.abs(newVisibleSec - visibleSeconds) > 0.001f) {
                    float focusX = detector.getFocusX();
                    float viewW = getWidth();
                    if (viewW > 0) {
                        float focusFraction = focusX / viewW;
                        float focusTimeMs = scrollOffsetMs + focusFraction * oldVisibleSec * 1000f;
                        visibleSeconds = newVisibleSec;
                        scrollOffsetMs = focusTimeMs - focusFraction * visibleSeconds * 1000f;
                        clampScrollOffset();
                    } else {
                        visibleSeconds = newVisibleSec;
                    }

                    if (seekListener != null) seekListener.onZoomChanged(visibleSeconds);
                    postInvalidate();
                }
                return true;
            }
        });
    }

    private void clampScrollOffset() {
        float maxOffset = Math.max(0, totalDurationMs - visibleSeconds * 1000f);
        scrollOffsetMs = Math.max(0, Math.min(maxOffset, scrollOffsetMs));
    }

    public void setOnPlaybackSeekListener(OnPlaybackSeekListener listener) {
        this.seekListener = listener;
    }

    // --- Streaming mode ---

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
        double floor = dbFloor, ceil = dbCeil, range = Math.max(1.0, ceil - floor);
        for (int k = 0; k < FREQ_BINS; k++) {
            double mag = Math.sqrt(fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k]) / FFT_SIZE;
            double db = 20.0 * Math.log10(mag + 1e-10);
            double norm = Math.max(0.0, Math.min(1.0, (db - floor) / range));
            pixelRow[FREQ_BINS - 1 - k] = heatmapColor(norm);
        }
        synchronized (lock) {
            offscreen.setPixels(pixelRow, 0, 1, currentColumn, 0, 1, FREQ_BINS);
            currentColumn++;
            if (currentColumn >= MAX_COLUMNS_STREAMING) { currentColumn = 0; wrapped = true; }
        }
        postInvalidate();
    }

    // --- Playback mode ---

    public void setFullSpectrogramFromFile(String filePath, int totalSamplesCount) {
        synchronized (lock) {
            pcmFilePath = filePath;
            totalSamples = totalSamplesCount;
            totalDurationMs = (int) (totalSamples * 1000L / SAMPLE_RATE);
            userScrolling = false;
            playbackMode = true;
            visibleSeconds = DEFAULT_VISIBLE_SECONDS;
            scrollOffsetMs = 0f;
            lastRenderedOffsetMs = -100000f;
            if (windowBitmap != null) { windowBitmap.recycle(); windowBitmap = null; }
            if (cursorFraction > 0f) {
                scrollOffsetMs = Math.max(0, cursorFraction * totalDurationMs - (visibleSeconds * 1000f) / 4f);
                clampScrollOffset();
            } else {
                cursorFraction = 0f;
            }
        }
        postInvalidate();
    }

    private void triggerWindowLoadIfNeeded() {
        if (windowLoading || pcmFilePath == null) return;
        int viewW = getWidth();
        if (viewW <= 0) return;

        synchronized (lock) {
            if (windowBitmap == null || lastRenderedWidth != viewW
                    || Math.abs(scrollOffsetMs - lastRenderedOffsetMs) > (visibleSeconds * 1000f) / 10f
                    || Math.abs(visibleSeconds - lastRenderedVisibleSec) > 0.01f) {
                loadWindowAsync(scrollOffsetMs, visibleSeconds, viewW);
            }
        }
    }

    private void loadWindowAsync(final float offsetMs, final float visSec, final int width) {
        if (windowLoading) return;
        windowLoading = true;
        final String path = pcmFilePath;
        new Thread(() -> {
            loadWindowSync(path, offsetMs, visSec, width);
            windowLoading = false;
            postInvalidate();
        }).start();
    }

    private void loadWindowSync(String path, float offsetMs, float visSec, int width) {
        if (path == null || width <= 0) return;
        File file = new File(path);
        if (!file.exists()) return;

        // Resolution-independent rendering: exactly 'width' columns
        Bitmap bmp = Bitmap.createBitmap(width, FREQ_BINS, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.BLACK);

        try (FileInputStream fis = new FileInputStream(path)) {
            // Calculate total samples needed for the entire visible window
            int totalSamplesInWindow = (int) (visSec * SAMPLE_RATE);
            // We need a bit more data at the end to satisfy the last FFT window
            int samplesToRead = totalSamplesInWindow + FFT_SIZE;
            
            long startByte = (long) (offsetMs * SAMPLE_RATE * 2 / 1000.0) & -2L;
            fis.skip(startByte);

            // Read the entire segment into memory (small enough for 8kHz)
            short[] segmentBuffer = new short[samplesToRead];
            byte[] rawBytes = new byte[samplesToRead * 2];
            int bytesRead = readStreamFully(fis, rawBytes);
            int actualSamples = bytesRead / 2;
            for (int i = 0; i < actualSamples; i++) {
                segmentBuffer[i] = (short) ((rawBytes[i * 2] & 0xFF) | (rawBytes[i * 2 + 1] << 8));
            }

            double[] wr = new double[FFT_SIZE], wi = new double[FFT_SIZE];
            int[] pr = new int[FREQ_BINS];
            double floor = dbFloor, ceil = dbCeil, range = Math.max(1.0, ceil - floor);
            double samplesPerColumn = (double) totalSamplesInWindow / width;

            for (int col = 0; col < width; col++) {
                int startIdx = (int) (col * samplesPerColumn);
                if (startIdx >= actualSamples) break;

                // High-resolution: use full FFT_SIZE window from the segment buffer
                int samplesInThisWindow = Math.min(FFT_SIZE, actualSamples - startIdx);
                for (int i = 0; i < samplesInThisWindow; i++) {
                    wr[i] = segmentBuffer[startIdx + i] * hannWindow[i];
                    wi[i] = 0.0;
                }
                // Only pad with zeros if we actually ran out of file data
                for (int i = samplesInThisWindow; i < FFT_SIZE; i++) wr[i] = wi[i] = 0.0;

                fft(wr, wi, FFT_SIZE);
                for (int k = 0; k < FREQ_BINS; k++) {
                    double mag = Math.sqrt(wr[k] * wr[k] + wi[k] * wi[k]) / FFT_SIZE;
                    double db = 20.0 * Math.log10(mag + 1e-10);
                    double norm = Math.max(0.0, Math.min(1.0, (db - floor) / range));
                    pr[FREQ_BINS - 1 - k] = heatmapColor(norm);
                }
                bmp.setPixels(pr, 0, 1, col, 0, 1, FREQ_BINS);
            }
        } catch (Exception e) {
            Log.e("SpectrogramView", "loadWindowSync error: " + e.getMessage());
        }

        synchronized (lock) {
            if (windowBitmap != null) windowBitmap.recycle();
            windowBitmap = bmp;
            lastRenderedOffsetMs = offsetMs;
            lastRenderedVisibleSec = visSec;
            lastRenderedWidth = width;
        }
    }

    private int readStreamFully(FileInputStream fis, byte[] buf) throws java.io.IOException {
        int total = 0;
        while (total < buf.length) {
            int read = fis.read(buf, total, buf.length - total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    public void setCursorPosition(float fraction) {
        cursorFraction = fraction;
        if (playbackMode && totalDurationMs > 0 && !userScrolling) {
            float cursorMs = fraction * totalDurationMs;
            if (cursorMs < scrollOffsetMs || cursorMs >= scrollOffsetMs + visibleSeconds * 1000f) {
                scrollOffsetMs = Math.max(0, cursorMs - (visibleSeconds * 1000f) / 4f);
                clampScrollOffset();
            }
        }
        postInvalidate();
    }

    public void setWindowSizeMs(int windowSizeMs) {
        float newVisibleSec = windowSizeMs / 1000.0f;
        if (Math.abs(newVisibleSec - visibleSeconds) > 0.001f) {
            visibleSeconds = newVisibleSec;
            clampScrollOffset();
            postInvalidate();
        }
    }

    public void clearPlaybackMode() {
        playbackMode = false; cursorFraction = -1f; pcmFilePath = null;
        synchronized (lock) {
            if (windowBitmap != null) { windowBitmap.recycle(); windowBitmap = null; }
            totalDurationMs = 0; scrollOffsetMs = 0f; userScrolling = false;
        }
        clear();
    }

    public void clear() {
        synchronized (lock) { sampleCount = 0; currentColumn = 0; wrapped = false; offscreen.eraseColor(Color.BLACK); }
        postInvalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!playbackMode || totalDurationMs <= 0) return super.onTouchEvent(event);
        boolean scaleHandled = scaleDetector.onTouchEvent(event);
        if (scaleDetector.isInProgress()) return true;

        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                userScrolling = true;
                touchStartX = event.getX();
                touchStartOffsetMs = scrollOffsetMs;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                float deltaX = touchStartX - event.getX();
                float msPerPx = (visibleSeconds * 1000f) / getWidth();
                scrollOffsetMs = touchStartOffsetMs + deltaX * msPerPx;
                clampScrollOffset();
                postInvalidate();
                return true;
            case MotionEvent.ACTION_UP:
                userScrolling = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                velocityTracker.computeCurrentVelocity(1000);
                float vX = velocityTracker.getXVelocity();
                float msPerPxUp = (visibleSeconds * 1000f) / getWidth();
                scroller.fling((int) scrollOffsetMs, 0, (int) (-vX * msPerPxUp), 0, 0, (int) Math.max(0, totalDurationMs - visibleSeconds * 1000f), 0, 0);
                velocityTracker.recycle(); velocityTracker = null;
                int seekMs = (int) (scrollOffsetMs + event.getX() * msPerPxUp);
                if (seekListener != null) seekListener.onSeek(Math.max(0, Math.min(totalDurationMs, seekMs)));
                postInvalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                userScrolling = false;
                if (velocityTracker != null) { velocityTracker.recycle(); velocityTracker = null; }
                return true;
        }
        return scaleHandled || super.onTouchEvent(event);
    }

    @Override
    public void computeScroll() {
        if (scroller != null && scroller.computeScrollOffset()) {
            scrollOffsetMs = scroller.getCurrX();
            clampScrollOffset();
            postInvalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.BLACK);
        int viewW = getWidth(), viewH = getHeight();
        if (viewW == 0 || viewH == 0) return;

        if (playbackMode) {
            triggerWindowLoadIfNeeded();
            synchronized (lock) {
                if (windowBitmap != null && !windowBitmap.isRecycled()) {
                    float viewMs = visibleSeconds * 1000f;
                    float bufferMs = lastRenderedVisibleSec * 1000f;
                    
                    Rect dst = new Rect(0, 0, viewW, viewH);
                    if (Math.abs(scrollOffsetMs - lastRenderedOffsetMs) < 1.0f 
                            && Math.abs(viewMs - bufferMs) < 1.0f
                            && lastRenderedWidth == viewW) {
                        canvas.drawBitmap(windowBitmap, null, dst, bitmapPaint);
                    } else {
                        // Transform the old buffer to fit current view while waiting for reload
                        float scale = bufferMs / viewMs;
                        float dx = (lastRenderedOffsetMs - scrollOffsetMs) * (viewW / viewMs);
                        canvas.save();
                        canvas.translate(dx, 0);
                        canvas.scale(scale, (float) viewH / FREQ_BINS);
                        canvas.drawBitmap(windowBitmap, 0, 0, bitmapPaint);
                        canvas.restore();
                    }
                }
            }
            if (cursorFraction >= 0f) {
                float cx = (cursorFraction * totalDurationMs - scrollOffsetMs) / (visibleSeconds * 1000f) * viewW;
                if (cx >= 0 && cx <= viewW) {
                    cursorPaint.setColor(0xFFFF0000);
                    cursorPaint.setStrokeWidth(3f);
                    canvas.drawLine(cx, 0, cx, viewH, cursorPaint);
                }
            }
        } else {
            drawStreamingMode(canvas, viewW, viewH);
        }
    }

    private void drawStreamingMode(Canvas canvas, int viewW, int viewH) {
        int snapColumn;
        boolean snapWrapped;
        synchronized (lock) {
            snapColumn = currentColumn;
            snapWrapped = wrapped;
        }
        if (snapColumn == 0 && !snapWrapped) return;
        Rect dst = new Rect(0, 0, viewW, viewH);
        if (!snapWrapped) {
            Rect src = new Rect(0, 0, snapColumn, FREQ_BINS);
            canvas.drawBitmap(offscreen, src, dst, bitmapPaint);
        } else {
            int rightPart = MAX_COLUMNS_STREAMING - snapColumn;
            int leftW = (int) ((long) rightPart * viewW / MAX_COLUMNS_STREAMING);
            if (rightPart > 0) {
                Rect src1 = new Rect(snapColumn, 0, MAX_COLUMNS_STREAMING, FREQ_BINS);
                Rect dst1 = new Rect(0, 0, leftW, viewH);
                canvas.drawBitmap(offscreen, src1, dst1, bitmapPaint);
            }
            if (snapColumn > 0) {
                Rect src2 = new Rect(0, 0, snapColumn, FREQ_BINS);
                Rect dst2 = new Rect(leftW, 0, viewW, viewH);
                canvas.drawBitmap(offscreen, src2, dst2, bitmapPaint);
            }
        }
        if (cursorFraction >= 0f) {
            float cx = cursorFraction * viewW;
            cursorPaint.setColor(0xFFFF0000);
            cursorPaint.setStrokeWidth(3f);
            canvas.drawLine(cx, 0, cx, viewH, cursorPaint);
        }
    }

    public void setDbFloor(double floor) { this.dbFloor = floor; reloadIfPlayback(); }
    public void setDbCeil(double ceil) { this.dbCeil = ceil; reloadIfPlayback(); }
    public void setDbRange(double floor, double ceil) { this.dbFloor = floor; this.dbCeil = ceil; reloadIfPlayback(); }

    private void reloadIfPlayback() {
        if (playbackMode && pcmFilePath != null) {
            windowLoading = false;
            triggerWindowLoadIfNeeded();
        } else {
            postInvalidate();
        }
    }

    private static void fft(double[] real, double[] imag, int n) {
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (i < j) {
                double tr = real[i]; real[i] = real[j]; real[j] = tr;
                double ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
            int m = n >> 1;
            while (m >= 1 && j >= m) { j -= m; m >>= 1; }
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
                    int idx1 = k + m2, idx2 = idx1 + halfStep;
                    double tR = curR * real[idx2] - curI * imag[idx2];
                    double tI = curR * imag[idx2] + curI * real[idx2];
                    real[idx2] = real[idx1] - tR; imag[idx2] = imag[idx1] - tI;
                    real[idx1] += tR; imag[idx1] += tI;
                    double newCurR = curR * wR - curI * wI;
                    curI = curR * wI + curI * wR; curR = newCurR;
                }
            }
        }
    }

    private static int heatmapColor(double v) {
        int r, g, b;
        if (v < 0.25) { double t = v / 0.25; r = 0; g = 0; b = (int) (255 * t); }
        else if (v < 0.5) { double t = (v - 0.25) / 0.25; r = 0; g = (int) (255 * t); b = (int) (255 * (1.0 - t)); }
        else if (v < 0.75) { double t = (v - 0.5) / 0.25; r = (int) (255 * t); g = 255; b = 0; }
        else { double t = (v - 0.75) / 0.25; r = 255; g = (int) (255 * (1.0 - t)); b = 0; }
        return Color.rgb(r, g, b);
    }
}
