package com.mobvoi.wenet;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Arrays;

public class VoiceRectView extends View {

  private static final int SAMPLES_PER_BAR = 512;  // match SpectrogramView FFT_SIZE
  private static final int SAMPLE_RATE = 8000;
  private static final int VISIBLE_SECONDS = 20;
  private static final int VISIBLE_BARS = VISIBLE_SECONDS * SAMPLE_RATE / SAMPLES_PER_BAR; // 312

  // Streaming mode fields
  private int mRectCount;
  private Paint mRectPaint;
  private int topColor, downColor;
  private int mRectWidth, mRectHeight;
  private int offset;
  private int mSpeed;
  private double[] mEnergyBuffer = null;

  // Sample-based accumulation for sync with spectrogram/VAD views
  private double sampleEnergyAccum = 0;
  private int sampleCount = 0;
  private int sampleAccum = 0;

  // Playback mode fields
  private boolean playbackMode = false;
  private float cursorFraction = -1f;
  private final Paint cursorPaint = new Paint();
  private ArrayList<Double> fullBars = null;
  private int totalDurationMs = 0;
  private int scrollOffsetBars = 0;
  private boolean userScrolling = false;

  // Touch scrolling
  private float touchStartX = 0;
  private int touchStartOffset = 0;
  private OnPlaybackSeekListener seekListener = null;

  public interface OnPlaybackSeekListener {
    void onSeek(int ms);
  }

  public VoiceRectView(Context context) {
    this(context, null);
  }

  public VoiceRectView(Context context, AttributeSet attrs) {
    this(context, attrs, 0);
  }

  public VoiceRectView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    setPaint(context, attrs);
  }

  public void setPaint(Context context, AttributeSet attrs) {
    TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.VoiceRect);
    mRectPaint = new Paint();
    mRectPaint.setColor(ta.getColor(R.styleable.VoiceRect_RectTopColor,
        ContextCompat.getColor(context, R.color.top_color)));
    topColor = ta.getColor(R.styleable.VoiceRect_RectTopColor,
        ContextCompat.getColor(context, R.color.top_color));
    downColor = ta.getColor(R.styleable.VoiceRect_RectDownColor,
        ContextCompat.getColor(context, R.color.down_color));
    mRectCount = ta.getInt(R.styleable.VoiceRect_RectCount, 10);
    mEnergyBuffer = new double[mRectCount];
    mSpeed = ta.getInt(R.styleable.VoiceRect_RectSpeed, 300);
    offset = ta.getInt(R.styleable.VoiceRect_RectOffset, 0);
    ta.recycle();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldW, int oldH) {
    super.onSizeChanged(w, h, oldW, oldH);
    mRectHeight = getHeight();
    updateBarWidth();
  }

  private void updateBarWidth() {
    int mWidth = getWidth();
    if (mWidth <= 0) return;
    int barCount = playbackMode ? getVisibleBarCount() : mRectCount;
    if (barCount <= 0) barCount = 1;
    mRectWidth = (mWidth - offset) / barCount;
    LinearGradient mLinearGradient = new LinearGradient(
        0, 0, Math.max(1, mRectWidth), Math.max(1, mRectHeight),
        topColor, downColor, Shader.TileMode.CLAMP);
    mRectPaint.setShader(mLinearGradient);
  }

  private int getVisibleBarCount() {
    if (fullBars == null || fullBars.isEmpty()) return VISIBLE_BARS;
    return Math.min(VISIBLE_BARS, fullBars.size());
  }

  public void setOnPlaybackSeekListener(OnPlaybackSeekListener listener) {
    this.seekListener = listener;
  }

  // --- Streaming mode methods ---

  public void add(double energy) {
    if (mEnergyBuffer.length - 1 >= 0) {
      System.arraycopy(mEnergyBuffer, 1, mEnergyBuffer, 0, mEnergyBuffer.length - 1);
    }
    mEnergyBuffer[mEnergyBuffer.length - 1] = energy;
  }

  public void addSamples(short[] data, int length) {
    for (int i = 0; i < length; i++) {
      sampleEnergyAccum += (double) data[i] * data[i];
      sampleCount++;
      sampleAccum++;
      if (sampleAccum >= SAMPLES_PER_BAR) {
        double avgEnergy = sampleEnergyAccum / sampleCount;
        double db = (10 * Math.log10(1 + avgEnergy)) / 200;
        db = Math.min(db, 1.0);
        add(db);
        sampleEnergyAccum = 0;
        sampleCount = 0;
        sampleAccum = 0;
      }
    }
  }

  public void zero() {
    Arrays.fill(mEnergyBuffer, 0);
    sampleEnergyAccum = 0;
    sampleCount = 0;
    sampleAccum = 0;
  }

  // --- Playback mode methods ---

  public void setFullWaveform(double[] energies, int durationMs) {
    if (energies == null || energies.length == 0) return;
    playbackMode = true;
    cursorFraction = 0f;
    scrollOffsetBars = 0;
    userScrolling = false;
    totalDurationMs = durationMs;
    fullBars = new ArrayList<>(energies.length);
    for (double e : energies) {
      fullBars.add(e);
    }
    updateBarWidth();
    postInvalidate();
  }

  public void setCursorPosition(float fraction) {
    cursorFraction = fraction;
    if (playbackMode && fullBars != null && !fullBars.isEmpty() && !userScrolling) {
      int cursorBar = (int) (fraction * fullBars.size());
      int visibleBars = getVisibleBarCount();
      if (cursorBar < scrollOffsetBars || cursorBar >= scrollOffsetBars + visibleBars) {
        scrollOffsetBars = Math.max(0, cursorBar - visibleBars / 4);
        int maxOffset = Math.max(0, fullBars.size() - visibleBars);
        scrollOffsetBars = Math.min(scrollOffsetBars, maxOffset);
      }
    }
    postInvalidate();
  }

  public void clearPlaybackMode() {
    playbackMode = false;
    cursorFraction = -1f;
    fullBars = null;
    scrollOffsetBars = 0;
    userScrolling = false;
    totalDurationMs = 0;
    zero();
    updateBarWidth();
    postInvalidate();
  }

  // --- Touch handling for playback scroll ---

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (!playbackMode || fullBars == null || fullBars.isEmpty()) {
      return super.onTouchEvent(event);
    }

    int viewWidth = getWidth();
    if (viewWidth <= 0) return super.onTouchEvent(event);

    int visibleBars = getVisibleBarCount();
    int maxOffset = Math.max(0, fullBars.size() - visibleBars);

    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN:
        userScrolling = true;
        touchStartX = event.getX();
        touchStartOffset = scrollOffsetBars;
        getParent().requestDisallowInterceptTouchEvent(true);
        return true;

      case MotionEvent.ACTION_MOVE: {
        float deltaX = touchStartX - event.getX();
        float barWidth = (float) viewWidth / visibleBars;
        int deltaBars = (int) (deltaX / barWidth);
        scrollOffsetBars = Math.max(0, Math.min(maxOffset, touchStartOffset + deltaBars));
        postInvalidate();
        return true;
      }

      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL: {
        userScrolling = false;
        getParent().requestDisallowInterceptTouchEvent(false);
        // Calculate seek position from current scroll center
        float touchX = event.getX();
        int barIndex = scrollOffsetBars + (int) (touchX / ((float) viewWidth / visibleBars));
        barIndex = Math.max(0, Math.min(fullBars.size() - 1, barIndex));
        int seekMs = barIndexToMs(barIndex);
        if (seekListener != null) {
          seekListener.onSeek(seekMs);
        }
        return true;
      }
    }
    return super.onTouchEvent(event);
  }

  private int barIndexToMs(int barIndex) {
    if (fullBars == null || fullBars.isEmpty()) return 0;
    return (int) ((long) barIndex * totalDurationMs / fullBars.size());
  }

  // --- Drawing ---

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);

    if (playbackMode && fullBars != null && !fullBars.isEmpty()) {
      drawPlaybackMode(canvas);
    } else {
      drawStreamingMode(canvas);
    }
  }

  private void drawStreamingMode(Canvas canvas) {
    float currentHeight;
    for (int i = 0; i < mRectCount; i++) {
      currentHeight = (float) (mRectHeight * mEnergyBuffer[i]);
      canvas.drawRect(
          (float) (mRectWidth * i + offset),
          (mRectHeight - currentHeight) / 2,
          (float) (mRectWidth * (i + 1)),
          mRectHeight / 2 + currentHeight / 2,
          mRectPaint
      );
    }
    postInvalidateDelayed(mSpeed);
  }

  private void drawPlaybackMode(Canvas canvas) {
    int viewWidth = getWidth();
    int viewHeight = getHeight();
    if (viewWidth <= 0 || viewHeight <= 0) return;

    int visibleBars = getVisibleBarCount();
    float barWidth = (float) viewWidth / visibleBars;

    // Draw bars
    for (int i = 0; i < visibleBars; i++) {
      int barIdx = scrollOffsetBars + i;
      if (barIdx >= fullBars.size()) break;
      double energy = fullBars.get(barIdx);
      float h = (float) (viewHeight * energy);
      float left = barWidth * i;
      float right = barWidth * (i + 1);
      canvas.drawRect(left, (viewHeight - h) / 2, right, viewHeight / 2 + h / 2, mRectPaint);
    }

    // Draw cursor line
    if (cursorFraction >= 0f && fullBars.size() > 0) {
      int cursorBar = (int) (cursorFraction * fullBars.size());
      int relativeBar = cursorBar - scrollOffsetBars;
      if (relativeBar >= 0 && relativeBar < visibleBars) {
        float cx = (relativeBar + 0.5f) * barWidth;
        cursorPaint.setColor(0xFFFF0000);
        cursorPaint.setStrokeWidth(3f);
        canvas.drawLine(cx, 0, cx, viewHeight, cursorPaint);
      }
    }
  }
}
