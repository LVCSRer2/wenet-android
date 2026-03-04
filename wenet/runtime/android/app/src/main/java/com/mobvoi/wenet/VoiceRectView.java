package com.mobvoi.wenet;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import androidx.core.content.ContextCompat;
import java.util.Arrays;

/**
 * 自定义的音频模拟条形图 Created by shize on 2016/9/5.
 */
public class VoiceRectView extends View {

  private static final int SAMPLES_PER_BAR = 512;  // match SpectrogramView FFT_SIZE
  // 音频矩形的数量
  private int mRectCount;
  // 音频矩形的画笔
  private Paint mRectPaint;
  // 渐变颜色的两种
  private int topColor, downColor;
  // 音频矩形的宽和高
  private int mRectWidth, mRectHeight;
  // 偏移量
  private int offset;
  // 频率速度
  private int mSpeed;

  private double[] mEnergyBuffer = null;

  // Sample-based accumulation for sync with spectrogram/VAD views
  private double sampleEnergyAccum = 0;
  private int sampleCount = 0;
  private int sampleAccum = 0;

  // DAW playback mode
  private boolean playbackMode = false;
  private float cursorFraction = -1f;
  private final Paint cursorPaint = new Paint();

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
    // 将属性存储到TypedArray中
    TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.VoiceRect);
    mRectPaint = new Paint();
    // 添加矩形画笔的基础颜色
    mRectPaint.setColor(ta.getColor(R.styleable.VoiceRect_RectTopColor,
        ContextCompat.getColor(context, R.color.top_color)));
    // 添加矩形渐变色的上面部分
    topColor = ta.getColor(R.styleable.VoiceRect_RectTopColor,
        ContextCompat.getColor(context, R.color.top_color));
    // 添加矩形渐变色的下面部分
    downColor = ta.getColor(R.styleable.VoiceRect_RectDownColor,
        ContextCompat.getColor(context, R.color.down_color));
    // 设置矩形的数量
    mRectCount = ta.getInt(R.styleable.VoiceRect_RectCount, 10);
    mEnergyBuffer = new double[mRectCount];

    // 设置重绘的时间间隔，也就是变化速度
    mSpeed = ta.getInt(R.styleable.VoiceRect_RectSpeed, 300);
    // 每个矩形的间隔
    offset = ta.getInt(R.styleable.VoiceRect_RectOffset, 0);
    // 回收TypeArray
    ta.recycle();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldW, int oldH) {
    super.onSizeChanged(w, h, oldW, oldH);
    // 渐变效果
    LinearGradient mLinearGradient;
    // 画布的宽
    int mWidth;
    // 获取画布的宽
    mWidth = getWidth();
    // 获取矩形的最大高度
    mRectHeight = getHeight();
    // 获取单个矩形的宽度(减去的部分为到右边界的间距)
    mRectWidth = (mWidth - offset) / mRectCount;
    // 实例化一个线性渐变
    mLinearGradient = new LinearGradient(
        0,
        0,
        mRectWidth,
        mRectHeight,
        topColor,
        downColor,
        Shader.TileMode.CLAMP
    );
    // 添加进画笔的着色器
    mRectPaint.setShader(mLinearGradient);
  }

  public void add(double energy) {
    if (mEnergyBuffer.length - 1 >= 0) {
      System.arraycopy(mEnergyBuffer, 1, mEnergyBuffer, 0, mEnergyBuffer.length - 1);
    }
    mEnergyBuffer[mEnergyBuffer.length - 1] = energy;
  }

  /** Sample-count based: accumulates 512 samples per bar, matching SpectrogramView. */
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

  /** Set full waveform for DAW playback mode. */
  public void setFullWaveform(double[] energies) {
    if (energies == null || energies.length == 0) return;
    playbackMode = true;
    cursorFraction = 0f;
    // Resample to mRectCount bars
    for (int i = 0; i < mRectCount; i++) {
      int srcIdx = (int) ((long) i * energies.length / mRectCount);
      mEnergyBuffer[i] = energies[Math.min(srcIdx, energies.length - 1)];
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
    // Draw cursor line in playback mode
    if (playbackMode && cursorFraction >= 0f) {
      int viewWidth = getWidth();
      float cx = cursorFraction * viewWidth;
      cursorPaint.setColor(0xFFFF0000);
      cursorPaint.setStrokeWidth(3f);
      canvas.drawLine(cx, 0, cx, mRectHeight, cursorPaint);
    }
    // Only auto-redraw in streaming mode
    if (!playbackMode) {
      postInvalidateDelayed(mSpeed);
    }
  }
}
