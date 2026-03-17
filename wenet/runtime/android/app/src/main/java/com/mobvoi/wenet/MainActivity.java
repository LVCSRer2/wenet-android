package com.mobvoi.wenet;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

  private final int MY_PERMISSIONS_RECORD_AUDIO = 1;
  private static final String LOG_TAG = "WENET";
  private static final int SAMPLE_RATE = 8000;
  private static final int MAX_QUEUE_SIZE = 2500;
  private static final int PLAYBACK_UPDATE_MS = 50;
  private static final String PREFS_NAME = "wenet_settings";
  private static final String KEY_MODEL_TYPE = "model_type";
  private static final String KEY_VIZ_TYPE = "viz_type";
  private boolean modelLoaded = false;
  private boolean useSpectrogram = false;
  private static final List<String> resource;
  static {
    if ("libtorch".equals(BuildConfig.BACKEND)) {
      resource = Arrays.asList("final.zip", "units.txt");
    } else {
      resource = Arrays.asList(
          "encoder.full.onnx", "encoder.quant.onnx",
          "ctc.full.onnx", "ctc.quant.onnx", "units.txt");
    }
  }

  private boolean startRecord = false;
  private AudioRecord record = null;
  private final Object lock = new Object();
  private int miniBufferSize = 0;
  private AcousticEchoCanceler aec = null;
  private NoiseSuppressor ns = null;
  private AutomaticGainControl agc = null;
  private final BlockingQueue<short[]> bufferQueue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);

  // Silero VAD
  private SileroVad sileroVad;
  private boolean useVad = false;

  // Bluetooth SCO
  private AudioManager audioManager;
  private boolean bluetoothScoOn = false;
  private final BroadcastReceiver scoReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
      if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
        Log.i(LOG_TAG, "Bluetooth SCO connected");
      } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
        Log.i(LOG_TAG, "Bluetooth SCO disconnected");
      }
    }
  };
  private final android.media.AudioDeviceCallback audioDeviceCallback =
      new android.media.AudioDeviceCallback() {
    @Override
    public void onAudioDevicesAdded(android.media.AudioDeviceInfo[] addedDevices) {
      for (android.media.AudioDeviceInfo device : addedDevices) {
        if (device.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
          Log.i(LOG_TAG, "BT device added: " + device.getProductName());
          if (startRecord) {
            // Seamlesly switch to BT mic during recording
            startBluetoothMic();
            if (record != null) {
              boolean result = record.setPreferredDevice(device);
              Log.i(LOG_TAG, "setPreferredDevice(BT_SCO) during recording = " + result);
            }
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                "블루투스 마이크로 자동 전환되었습니다.", Toast.LENGTH_LONG).show());
          }
          return;
        }
      }
    }

    @Override
    public void onAudioDevicesRemoved(android.media.AudioDeviceInfo[] removedDevices) {
      for (android.media.AudioDeviceInfo device : removedDevices) {
        if (device.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
          Log.i(LOG_TAG, "BT disconnected during recording: " + device.getProductName());
          bluetoothScoOn = false;
          if (startRecord) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                "블루투스 연결 해제됨. 폰 마이크로 계속 녹음합니다.", Toast.LENGTH_LONG).show());
          }
        }
      }
    }
  };

  // Recording save
  private String timestampedResult = null;

  // Incremental display cache for buildLiveDisplay
  private StringBuilder cachedConfirmedText = new StringBuilder();
  private StringBuilder cachedInProgressSentence = new StringBuilder();
  private int cachedInProgressStartMs = -1;
  private String lastPartialText = "";
  private String lastDisplayedText = "";
  private int lastAppendedConfirmedLength = 0; // chars from cachedConfirmedText already in TextView
  private String currentRecordingName = null;

  // Realtime Opus encoder
  private android.media.MediaCodec realtimeEncoder = null;
  private android.media.MediaMuxer realtimeMuxer = null;
  private int realtimeMuxerTrack = -1;
  private boolean realtimeMuxerStarted = false;
  private long realtimePresentationUs = 0;
  private short[] encoderAccumBuf = null;
  private int encoderAccumFilled = 0;
  private static final int OPUS_FRAME_SAMPLES = 160; // 20ms at 8kHz

  // Playback (OGG streaming)
  private OggStreamPlayer oggPlayer = null;
  private String playbackAudioPath = null; // OGG path
  private long playbackDurationMs = 0;
  private boolean isPlaying = false;
  private volatile long playbackPositionMs = 0;
  private boolean userSeekingBar = false;
  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private Runnable playbackUpdater;
  // Legacy fields kept for AudioTrack/PCM compat (used only in update helpers)
  private AudioTrack audioTrack = null;
  private long playbackStartBytes = 0;
  private long pcmFileLength = 0;

  // Karaoke
  private List<WordSpan> wordSpans = null;
  private String currentPlaybackRecording = null;
  private SpannableStringBuilder karaokeSSB = null;
  private int[] karaokeSpanStarts = null;
  private int[] karaokeSpanEnds = null;
  private int lastHighlightIndex = -1;
  private BackgroundColorSpan currentBgSpan = null;
  private ForegroundColorSpan currentFgSpan = null;

  private static class WordSpan {
    String word;
    int startMs;
    int endMs;
    WordSpan(String word, int startMs, int endMs) {
      this.word = word;
      this.startMs = startMs;
      this.endMs = endMs;
    }
  }

  public static void assetsInit(Context context) throws IOException {
    AssetManager assetMgr = context.getAssets();
    for (String file : assetMgr.list("")) {
      if (resource.contains(file)) {
        File dst = new File(context.getFilesDir(), file);
        long assetSize = -1;
        try (InputStream tmp = assetMgr.open(file)) {
          assetSize = 0;
          byte[] buf = new byte[4 * 1024];
          int n;
          while ((n = tmp.read(buf)) != -1) { assetSize += n; }
        }
        if (!dst.exists() || dst.length() == 0 || dst.length() != assetSize) {
          Log.i(LOG_TAG, "Copying " + file + " to " + dst.getAbsolutePath());
          InputStream is = assetMgr.open(file);
          OutputStream os = new FileOutputStream(dst);
          byte[] buffer = new byte[4 * 1024];
          int read;
          while ((read = is.read(buffer)) != -1) {
            os.write(buffer, 0, read);
          }
          os.flush();
        }
      }
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode,
      String[] permissions, int[] grantResults) {
    if (requestCode == MY_PERMISSIONS_RECORD_AUDIO) {
      if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        Log.i(LOG_TAG, "record permission is granted");
        initRecorder();
      } else {
        Toast.makeText(this, "Permissions denied to record audio", Toast.LENGTH_LONG).show();
        Button button = findViewById(R.id.button);
        button.setEnabled(false);
      }
    }
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    registerReceiver(scoReceiver,
        new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
    audioManager.registerAudioDeviceCallback(audioDeviceCallback, new Handler(Looper.getMainLooper()));
    requestAudioPermissions();
    try {
      assetsInit(this);
    } catch (IOException e) {
      Log.e(LOG_TAG, "Error process asset files to file path");
    }

    // Initialize Silero VAD
    sileroVad = new SileroVad();
    configureVadFromPrefs();
    useVad = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("vad_enabled", true);
    if (useVad) {
      if (!sileroVad.init(this)) {
        Log.w(LOG_TAG, "Silero VAD init failed, disabling VAD");
        useVad = false;
      }
    }

    TextView textView = findViewById(R.id.textView);
    textView.setText("", TextView.BufferType.EDITABLE);
    // No LinkMovementMethod: avoids DynamicLayout (slow scroll). Touch handled manually below.
    textView.setOnTouchListener(new android.view.View.OnTouchListener() {
      private float tapStartX, tapStartY;
      private boolean moved;
      @Override
      public boolean onTouch(android.view.View v, android.view.MotionEvent event) {
        switch (event.getAction()) {
          case android.view.MotionEvent.ACTION_DOWN:
            tapStartX = event.getX(); tapStartY = event.getY(); moved = false; break;
          case android.view.MotionEvent.ACTION_MOVE:
            if (Math.abs(event.getX()-tapStartX)>8 || Math.abs(event.getY()-tapStartY)>8) moved=true; break;
          case android.view.MotionEvent.ACTION_UP:
            if (!moved && karaokeSpanStarts != null && wordSpans != null) {
              TextView tv = (TextView) v;
              android.text.Layout layout = tv.getLayout();
              if (layout != null) {
                int x = (int)(event.getX()-tv.getTotalPaddingLeft()+tv.getScrollX());
                int y = (int)(event.getY()-tv.getTotalPaddingTop()+tv.getScrollY());
                int line = layout.getLineForVertical(y);
                int offset = layout.getOffsetForHorizontal(line, x);
                int idx = findWordAtCharOffset(offset);
                if (idx >= 0 && wordSpans != null && idx < wordSpans.size()) {
                  seekToMs(wordSpans.get(idx).startMs);
                  if (!isPlaying) resumePlayback();
                }
              }
            }
            break;
        }
        return false; // let ScrollView handle scrolling
      }
    });
    textView.setClickable(true); // ensures ACTION_UP is delivered even inside ScrollView
    int fontSp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt("result_font_size", 18);
    textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSp);

    Button button = findViewById(R.id.button);
    button.setEnabled(false);

    showModelSelectionDialog();

    // Visualization preference
    useSpectrogram = "spectrogram".equals(
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_VIZ_TYPE, "waveform"));
    updateVisualizationVisibility();

    // dB range slider for spectrogram (Material RangeSlider)
    com.google.android.material.slider.RangeSlider dbRangeSlider = findViewById(R.id.dbRangeSlider);
    TextView dbRangeLabel = findViewById(R.id.dbRangeLabel);
    SpectrogramView spectrogramView = findViewById(R.id.spectrogramView);

    float savedFloor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getFloat("db_floor", -20f);
    float savedCeil = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getFloat("db_ceil", 80f);
    dbRangeSlider.setValues(savedFloor, savedCeil);
    spectrogramView.setDbFloor(savedFloor);
    spectrogramView.setDbCeil(savedCeil);
    dbRangeLabel.setText(String.format("dB: %.0f~%.0f", savedFloor, savedCeil));

    dbRangeSlider.addOnChangeListener((slider, value, fromUser) -> {
      java.util.List<Float> values = slider.getValues();
      ((TextView) findViewById(R.id.dbRangeLabel))
          .setText(String.format("dB: %.0f~%.0f", values.get(0), values.get(1)));
    });
    dbRangeSlider.addOnSliderTouchListener(new com.google.android.material.slider.RangeSlider.OnSliderTouchListener() {
      @Override public void onStartTrackingTouch(com.google.android.material.slider.RangeSlider slider) {}
      @Override public void onStopTrackingTouch(com.google.android.material.slider.RangeSlider slider) {
        java.util.List<Float> values = slider.getValues();
        float floor = values.get(0);
        float ceil = values.get(1);
        ((SpectrogramView) findViewById(R.id.spectrogramView)).setDbRange(floor, ceil);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putFloat("db_floor", floor)
            .putFloat("db_ceil", ceil).apply();
      }
    });

    Button copyButton = findViewById(R.id.copyButton);
    copyButton.setOnClickListener(view -> {
      String text = (timestampedResult != null && !timestampedResult.isEmpty())
          ? timestampedResult
          : ((TextView) findViewById(R.id.textView)).getText().toString();
      if (!text.isEmpty()) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("wenet_result", text));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
      }
    });

    button.setText("Record");
    button.setOnClickListener(view -> {
      if (!startRecord) {
        stopPlayback();
        startBluetoothMic();
        startRecord = true;
        currentRecordingName = RecordingManager.createRecordingDir(this);
        if (getSupportActionBar() != null) getSupportActionBar().setTitle(currentRecordingName);
        String codec = getSharedPreferences("wenet_settings", MODE_PRIVATE).getString("codec_type", "opus");
        String audioOutPath;
        if ("aac".equals(codec)) audioOutPath = RecordingManager.getAacPath(this, currentRecordingName);
        else if ("amrnb".equals(codec)) audioOutPath = RecordingManager.getAmrPath(this, currentRecordingName);
        else if ("aac_hw".equals(codec)) audioOutPath = RecordingManager.getAacPath(this, currentRecordingName);
        else audioOutPath = RecordingManager.getOpusPath(this, currentRecordingName);
        startRealtimeEncoder(audioOutPath, codec);
        Recognize.reset();
        cachedConfirmedText = new StringBuilder();
        cachedInProgressSentence = new StringBuilder();
        cachedInProgressStartMs = -1;
        lastPartialText = "";
        lastDisplayedText = "";
        lastAppendedConfirmedLength = 0;
        ((TextView) findViewById(R.id.textView)).setText("", TextView.BufferType.EDITABLE);
        startRecordThread();
        startAsrThread();
        Recognize.startDecode();
        Intent serviceIntent = new Intent(this, RecordingForegroundService.class);
        serviceIntent.setAction(RecordingForegroundService.ACTION_START);
        ContextCompat.startForegroundService(this, serviceIntent);
        button.setText("Stop");
      } else {
        startRecord = false;
        Recognize.setInputFinished();
        button.setText("Record");
      }
      button.setEnabled(false);
    });

    // Play/Pause button
    Button playPauseButton = findViewById(R.id.playPauseButton);
    playPauseButton.setOnClickListener(v -> {
      if (isPlaying) {
        pausePlayback();
      } else {
        resumePlayback();
      }
    });

    // SeekBar
    SeekBar seekBar = findViewById(R.id.seekBar);
    seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
        if (fromUser && playbackAudioPath != null) {
          updatePlaybackUI(progress);
          updateKaraokeHighlight(progress);
          updateVisualizationCursor(progress);
        }
      }
      @Override
      public void onStartTrackingTouch(SeekBar sb) { userSeekingBar = true; }
      @Override
      public void onStopTrackingTouch(SeekBar sb) {
        userSeekingBar = false;
        if (playbackAudioPath != null) {
          seekToMs(sb.getProgress());
        }
      }
    });

    // Recordings button
    Button recordingsButton = findViewById(R.id.recordingsButton);
    recordingsButton.setOnClickListener(v -> showRecordingsDialog());

    // Settings button
    Button settingsButton = findViewById(R.id.settingsButton);
    settingsButton.setOnClickListener(v ->
        startActivityForResult(new Intent(this, SettingsActivity.class), 100));
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == 100 && resultCode == RESULT_OK && data != null) {
      String modelType = data.getStringExtra("model_type");
      if (modelType != null) {
        loadModel(modelType);
      }
      String vizType = data.getStringExtra("viz_type");
      if (vizType != null) {
        useSpectrogram = "spectrogram".equals(vizType);
        updateVisualizationVisibility();
      }
      // Reload VAD setting
      configureVadFromPrefs();
      useVad = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("vad_enabled", true);
      if (useVad && !sileroVad.isInitialized()) {
        sileroVad.init(this);
      }
      // Apply result font size
      int fontSp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt("result_font_size", 18);
      ((TextView) findViewById(R.id.textView)).setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSp);
    }
  }

  private void showModelSelectionDialog() {
    String[] items = {"Full (비양자화)", "Quantized (양자화)"};
    String[] values = {"full", "quant"};
    new AlertDialog.Builder(this)
        .setTitle("모델 선택")
        .setCancelable(false)
        .setItems(items, (dialog, which) -> loadModel(values[which]))
        .show();
  }

  private void loadModel(String modelType) {
    Toast.makeText(this, "모델 로딩 중...", Toast.LENGTH_SHORT).show();
    new Thread(() -> {
      try {
        String prefix = "full".equals(modelType) ? "full" : "quant";
        File filesDir = getFilesDir();
        copyFile(new File(filesDir, "encoder." + prefix + ".onnx"),
            new File(filesDir, "encoder.onnx"));
        copyFile(new File(filesDir, "ctc." + prefix + ".onnx"),
            new File(filesDir, "ctc.onnx"));
        Recognize.init(filesDir.getPath());
        modelLoaded = true;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_MODEL_TYPE, modelType).apply();
        Log.i(LOG_TAG, "Model loaded: " + modelType);
        runOnUiThread(() -> {
          Button btn = findViewById(R.id.button);
          btn.setEnabled(true);
          Toast.makeText(this,
              "모델 로드 완료 (" + ("full".equals(modelType) ? "Full" : "Quantized") + ")",
              Toast.LENGTH_SHORT).show();
        });
      } catch (Exception e) {
        Log.e(LOG_TAG, "Model load error: " + e.getMessage());
        runOnUiThread(() ->
            Toast.makeText(this, "모델 로드 실패", Toast.LENGTH_LONG).show());
      }
    }).start();
  }

  private void copyFile(File src, File dst) throws IOException {
    FileInputStream fis = new FileInputStream(src);
    FileOutputStream fos = new FileOutputStream(dst);
    byte[] buf = new byte[4096];
    int len;
    while ((len = fis.read(buf)) != -1) { fos.write(buf, 0, len); }
    fos.flush();
    fos.close();
    fis.close();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    stopPlayback();
    stopBluetoothMic();
    if (sileroVad != null) { sileroVad.release(); }
    try { unregisterReceiver(scoReceiver); } catch (Exception ignored) {}
    try { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback); } catch (Exception ignored) {}
  }

  private void requestAudioPermissions() {
    List<String> perms = new ArrayList<>();
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      perms.add(Manifest.permission.RECORD_AUDIO);
    }
    if (Build.VERSION.SDK_INT >= 31
        && ContextCompat.checkSelfPermission(this, "android.permission.BLUETOOTH_CONNECT")
            != PackageManager.PERMISSION_GRANTED) {
      perms.add("android.permission.BLUETOOTH_CONNECT");
    }
    if (!perms.isEmpty()) {
      ActivityCompat.requestPermissions(this,
          perms.toArray(new String[0]),
          MY_PERMISSIONS_RECORD_AUDIO);
    } else {
      initRecorder();
    }
  }

  private void startBluetoothMic() {
    try {
      // Android 12 (API 31)+ requires BLUETOOTH_CONNECT runtime permission
      if (Build.VERSION.SDK_INT >= 31
          && ContextCompat.checkSelfPermission(this, "android.permission.BLUETOOTH_CONNECT")
              != PackageManager.PERMISSION_GRANTED) {
        Log.i(LOG_TAG, "BLUETOOTH_CONNECT permission not granted, using phone mic");
        return;
      }

      // Automatically detect and use Bluetooth SCO if available
      if (Build.VERSION.SDK_INT >= 31) {
        List<android.media.AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
        for (android.media.AudioDeviceInfo device : devices) {
          if (device.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            boolean result = audioManager.setCommunicationDevice(device);
            if (result) {
              Log.i(LOG_TAG, "Auto-selected BT SCO device: " + device.getProductName());
              bluetoothScoOn = true;
              return;
            }
          }
        }
      } else {
        // Legacy path auto-detection
        if (audioManager.isBluetoothScoAvailableOffCall()) {
          audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
          audioManager.startBluetoothSco();
          audioManager.setBluetoothScoOn(true);
          bluetoothScoOn = true;
          Log.i(LOG_TAG, "Bluetooth SCO auto-activated (legacy)");
          return;
        }
      }
      Log.i(LOG_TAG, "No BT device found, using phone mic");
    } catch (Exception e) {
      Log.i(LOG_TAG, "Bluetooth SCO not available: " + e.getMessage());
      bluetoothScoOn = false;
    }
  }

  private void stopBluetoothMic() {
    try {
      if (Build.VERSION.SDK_INT >= 31) {
        audioManager.clearCommunicationDevice();
        Log.i(LOG_TAG, "clearCommunicationDevice called");
      }
      // Always stop legacy SCO and reset mode to be safe
      audioManager.setBluetoothScoOn(false);
      audioManager.stopBluetoothSco();
      audioManager.setSpeakerphoneOn(false);
      audioManager.setMode(AudioManager.MODE_NORMAL);
      Log.i(LOG_TAG, "Audio mode reset to NORMAL, speakerphone OFF");
    } catch (Exception e) {
      Log.e(LOG_TAG, "Error stopping Bluetooth mic: " + e.getMessage());
    }
    bluetoothScoOn = false;
  }

  private boolean initRecorder() {
    synchronized (lock) {
      if (record != null) {
        try {
          record.stop();
          record.release();
        } catch (Exception ignored) {
        } finally {
          record = null;
        }
        // Small delay to let system free hardware resources
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
      }

      miniBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT);
      if (miniBufferSize == AudioRecord.ERROR || miniBufferSize == AudioRecord.ERROR_BAD_VALUE) {
        Log.e(LOG_TAG, "Audio buffer can't initialize!");
        return false;
      }
      try {
        record = new AudioRecord(MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            miniBufferSize);
      } catch (Exception e) {
        Log.e(LOG_TAG, "Failed to create AudioRecord: " + e.getMessage());
        return false;
      }

      if (record.getState() != AudioRecord.STATE_INITIALIZED) {
        Log.e(LOG_TAG, "Audio Record can't initialize!");
        return false;
      }

      // Apply audio processing based on settings
      android.content.SharedPreferences prefs =
          getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
      int sessionId = record.getAudioSessionId();

      boolean useAec = prefs.getBoolean("audio_aec", true);
      if (AcousticEchoCanceler.isAvailable()) {
        aec = AcousticEchoCanceler.create(sessionId);
        if (aec != null) {
          aec.setEnabled(useAec);
          Log.i(LOG_TAG, "AEC " + (useAec ? "enabled" : "disabled"));
        }
      }

      boolean useNs = prefs.getBoolean("audio_ns", true);
      if (NoiseSuppressor.isAvailable()) {
        ns = NoiseSuppressor.create(sessionId);
        if (ns != null) {
          ns.setEnabled(useNs);
          Log.i(LOG_TAG, "NS " + (useNs ? "enabled" : "disabled"));
        }
      }

      boolean useAgc = prefs.getBoolean("audio_agc", true);
      if (AutomaticGainControl.isAvailable()) {
        agc = AutomaticGainControl.create(sessionId);
        if (agc != null) {
          agc.setEnabled(useAgc);
          Log.i(LOG_TAG, "AGC " + (useAgc ? "enabled" : "disabled"));
        }
      }

      Log.i(LOG_TAG, "Record init okay");
      return true;
    }
  }

  private void releaseAudioEffects() {
    if (aec != null) { aec.release(); aec = null; }
    if (ns != null) { ns.release(); ns = null; }
    if (agc != null) { agc.release(); agc = null; }
  }

  private void startRecordThread() {
    new Thread(() -> {
      if (!initRecorder()) {
        runOnUiThread(() -> {
          Toast.makeText(this, "녹음 장치를 초기화할 수 없습니다.", Toast.LENGTH_SHORT).show();
          Button button = findViewById(R.id.button);
          button.setText("Record");
          button.setEnabled(true);
        });
        startRecord = false;
        return;
      }

      synchronized (lock) {
        if (record == null) return;
        try {
          record.startRecording();
        } catch (IllegalStateException e) {
          Log.e(LOG_TAG, "Failed to start recording: " + e.getMessage());
          startRecord = false;
          return;
        }
      }

      Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
      short[] buffer = new short[miniBufferSize / 2];
      byte[] pcmBytes = new byte[miniBufferSize]; // pre-allocate for PCM write
      while (startRecord) {
        int read = 0;
        synchronized (lock) {
          if (record != null) {
            read = record.read(buffer, 0, buffer.length);
          }
        }
        if (AudioRecord.ERROR_INVALID_OPERATION != read && read > 0) {
          feedToRealtimeEncoder(buffer, read);
          try {
            short[] copy = new short[read];
            System.arraycopy(buffer, 0, copy, 0, read);
            bufferQueue.put(copy);
          } catch (InterruptedException e) {
            Log.e(LOG_TAG, e.getMessage());
          }
        }
        Button button = findViewById(R.id.button);
        if (!button.isEnabled() && startRecord) {
          runOnUiThread(() -> button.setEnabled(true));
        }
      }
      
      synchronized (lock) {
        if (record != null) {
          try {
            record.stop();
            record.release();
          } catch (Exception ignored) {
          } finally {
            record = null;
          }
        }
      }
      releaseAudioEffects();
      stopBluetoothMic();
      if (useSpectrogram) {
        ((SpectrogramView) findViewById(R.id.spectrogramView)).clear();
      } else {
        ((VoiceRectView) findViewById(R.id.voiceRectView)).zero();
      }
      ((VadProbView) findViewById(R.id.vadProbView)).zero();
      finalizeRealtimeEncoder();
    }).start();
  }

  private double calculateDb(short[] buffer) {
    double energy = 0.0;
    for (short value : buffer) {
      energy += value * value;
    }
    energy /= buffer.length;
    energy = (10 * Math.log10(1 + energy)) / 200;
    energy = Math.min(energy, 1.0);
    return energy;
  }

  private void configureVadFromPrefs() {
    android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    int thresholdProgress = prefs.getInt("vad_threshold", 40);
    float threshold = (thresholdProgress + 10) / 100f;
    sileroVad.setSpeechThreshold(threshold);

    int preBufferProgress = prefs.getInt("vad_prebuffer", 8);
    int preBufferChunks = preBufferProgress + 2;
    sileroVad.setPreBufferSlots(preBufferChunks);

    int trailingProgress = prefs.getInt("vad_trailing", 20);
    int trailingChunks = trailingProgress + 5;
    sileroVad.setTrailingSilenceChunks(trailingChunks);

    // Sync thresholds to VadProbView
    float silenceThreshold = Math.max(0.05f, threshold - 0.2f);
    ((VadProbView) findViewById(R.id.vadProbView)).setThresholds(threshold, silenceThreshold);
  }

  private void updateVisualizationVisibility() {
    VoiceRectView voiceView = findViewById(R.id.voiceRectView);
    SpectrogramView spectrogramView = findViewById(R.id.spectrogramView);
    View dbRangeLayout = findViewById(R.id.dbRangeLayout);
    if (useSpectrogram) {
      voiceView.setVisibility(View.GONE);
      spectrogramView.setVisibility(View.VISIBLE);
      dbRangeLayout.setVisibility(View.VISIBLE);
    } else {
      voiceView.setVisibility(View.VISIBLE);
      spectrogramView.setVisibility(View.GONE);
      dbRangeLayout.setVisibility(View.GONE);
    }
  }

  private void updateResultAndScroll(String text) {
    TextView textView = findViewById(R.id.textView);
    ScrollView scrollView = findViewById(R.id.scrollView);
    // Auto-scroll only if user is already near the bottom
    boolean atBottom = (scrollView.getChildAt(0).getBottom()
        - scrollView.getHeight() - scrollView.getScrollY()) < 100;
    textView.setText(text);
    if (atBottom) {
      scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }
  }

  private void startAsrThread() {
    new Thread(() -> {
      // Track whether we need to snapshot offset before next speech chunk
      final boolean[] needSnapshot = {true};  // true at start (initial segment)
      SileroVad.Callback vadCallback = new SileroVad.Callback() {
        @Override
        public void onSpeechChunk(short[] data, int length) {
          if (needSnapshot[0]) {
            Recognize.snapshotOffset();
            needSnapshot[0] = false;
          }
          if (length == data.length) {
            Recognize.acceptWaveform(data);
          } else {
            short[] trimmed = new short[length];
            System.arraycopy(data, 0, trimmed, 0, length);
            Recognize.acceptWaveform(trimmed);
          }
        }
        @Override
        public void onSkippedSamples(int count) {
          Recognize.addSkippedSamples(count);
          // Removed manual Recognize.pushSilenceForEndpoint(8000) here 
          // to prevent 1-second timestamp shifts in the final results.
          needSnapshot[0] = true;  // next speech chunk starts a new segment
        }
      };

      // Reset VAD state for new recording
      if (useVad && sileroVad.isInitialized()) {
        sileroVad.reset();
      }

      // Send all data — throttle setText (O(n) with text length)
      long lastUiUpdate = 0;
      final long UI_UPDATE_INTERVAL_MS = 500;
      while (startRecord || bufferQueue.size() > 0) {
        try {
          short[] data = bufferQueue.take();
          // VAD processing first (to get prob before visualization)
          if (useVad && sileroVad.isInitialized()) {
            sileroVad.process(data, data.length, vadCallback);
            ((VadProbView) findViewById(R.id.vadProbView)).setCurrentProb(sileroVad.getLastProb());
          } else {
            Recognize.acceptWaveform(data);
          }
          // Feed all visualizations with same sample count for sync
          if (useSpectrogram) {
            ((SpectrogramView) findViewById(R.id.spectrogramView)).addSamples(data, data.length);
          } else {
            ((VoiceRectView) findViewById(R.id.voiceRectView)).addSamples(data, data.length);
          }
          ((VadProbView) findViewById(R.id.vadProbView)).addSamples(data.length);
          long now = System.currentTimeMillis();
          boolean endpointFired = Recognize.hasNewEndpoint();
          if (endpointFired || now - lastUiUpdate >= UI_UPDATE_INTERVAL_MS) {
            lastUiUpdate = now;
            updateLiveDisplayIncremental();
          }
        } catch (InterruptedException e) {
          Log.e(LOG_TAG, e.getMessage());
        }
      }

      // Flush remaining VAD buffers
      if (useVad && sileroVad.isInitialized()) {
        sileroVad.flush(vadCallback);
        sileroVad.flushRemainingAsSkipped(vadCallback);
      }

      // Final UI update after loop ends
      updateLiveDisplayIncremental();

      // Wait for final result — show processing indicator
      runOnUiThread(() -> Toast.makeText(MainActivity.this,
          "처리 중...", Toast.LENGTH_LONG).show());
      while (true) {
        if (!Recognize.getFinished()) {
          try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        } else {
          // Save result.json with timed result
          saveTimedResult();
          // Build timestamped text for copy & Slack
          try {
            timestampedResult = buildTimestampedText(Recognize.getTimedResult());
          } catch (Exception e) {
            timestampedResult = Recognize.getResult();
          }
          // Fallback: use last displayed text if native result is empty
          if ((timestampedResult == null || timestampedResult.trim().isEmpty())
              && !lastDisplayedText.isEmpty()) {
            timestampedResult = lastDisplayedText.trim();
            Log.i(LOG_TAG, "Using lastDisplayedText as timestampedResult fallback");
          }

          SlackWebhookSender.send(
              getApplicationContext(), currentRecordingName, timestampedResult);
          stopService(new Intent(MainActivity.this, RecordingForegroundService.class));
          final String recordingName = currentRecordingName;
          runOnUiThread(() -> {
            Button button = findViewById(R.id.button);
            button.setEnabled(true);
            if (recordingName != null) {
              enterPlaybackMode(recordingName);
            }
          });
          break;
        }
      }
    }).start();
  }

  private void startRealtimeEncoder(String outputPath, String codec) {
    try {
      String mime;
      int bitrate;
      int muxerFormat;
      if ("aac".equals(codec) || "aac_hw".equals(codec)) {
        mime = "audio/mp4a-latm"; bitrate = 16000;
        muxerFormat = android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
      } else if ("amrnb".equals(codec)) {
        mime = "audio/3gpp"; bitrate = 12200;
        muxerFormat = android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP;
      } else {
        mime = "audio/opus"; bitrate = 6000;
        muxerFormat = android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG;
      }
      android.media.MediaFormat fmt = android.media.MediaFormat.createAudioFormat(mime, SAMPLE_RATE, 1);
      fmt.setInteger(android.media.MediaFormat.KEY_BIT_RATE, bitrate);
      if ("aac".equals(codec) || "aac_hw".equals(codec)) {
        fmt.setInteger(android.media.MediaFormat.KEY_AAC_PROFILE,
            android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
      }
      if ("aac_hw".equals(codec)) {
        try {
          realtimeEncoder = android.media.MediaCodec.createByCodecName("c2.sec.aac.encoder");
        } catch (Exception e) {
          Log.w(LOG_TAG, "HW AAC encoder not found, falling back to SW");
          realtimeEncoder = android.media.MediaCodec.createEncoderByType(mime);
        }
      } else {
        realtimeEncoder = android.media.MediaCodec.createEncoderByType(mime);
      }
      realtimeEncoder.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
      realtimeEncoder.start();
      realtimeMuxer = new android.media.MediaMuxer(outputPath, muxerFormat);
      realtimeMuxerTrack = -1;
      realtimeMuxerStarted = false;
      realtimePresentationUs = 0;
      encoderAccumBuf = new short[OPUS_FRAME_SAMPLES];
      encoderAccumFilled = 0;
      Log.i(LOG_TAG, "Realtime Opus encoder started: " + outputPath);
    } catch (Exception e) {
      Log.e(LOG_TAG, "Failed to start realtime encoder: " + e.getMessage());
      realtimeEncoder = null;
      realtimeMuxer = null;
    }
  }

  private void feedToRealtimeEncoder(short[] samples, int count) {
    if (realtimeEncoder == null) return;
    int srcPos = 0;
    while (srcPos < count) {
      int toCopy = Math.min(count - srcPos, OPUS_FRAME_SAMPLES - encoderAccumFilled);
      System.arraycopy(samples, srcPos, encoderAccumBuf, encoderAccumFilled, toCopy);
      srcPos += toCopy;
      encoderAccumFilled += toCopy;
      if (encoderAccumFilled == OPUS_FRAME_SAMPLES) {
        submitFrame(encoderAccumBuf, OPUS_FRAME_SAMPLES, false);
        encoderAccumFilled = 0;
      }
    }
    drainEncoder(0); // non-blocking drain
  }

  private void submitFrame(short[] samples, int count, boolean eos) {
    try {
      int inIdx = realtimeEncoder.dequeueInputBuffer(5000);
      if (inIdx >= 0) {
        java.nio.ByteBuffer inBuf = realtimeEncoder.getInputBuffer(inIdx);
        inBuf.clear();
        byte[] bytes = new byte[count * 2];
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, 0, count);
        inBuf.put(bytes, 0, count * 2);
        int flags = eos ? android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;
        realtimeEncoder.queueInputBuffer(inIdx, 0, count * 2, realtimePresentationUs, flags);
        realtimePresentationUs += count * 1_000_000L / SAMPLE_RATE;
      }
    } catch (Exception e) {
      Log.e(LOG_TAG, "Encoder input error: " + e.getMessage());
    }
  }

  private void drainEncoder(long timeoutUs) {
    if (realtimeEncoder == null || realtimeMuxer == null) return;
    android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
    while (true) {
      int outIdx = realtimeEncoder.dequeueOutputBuffer(info, timeoutUs);
      if (outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        realtimeMuxerTrack = realtimeMuxer.addTrack(realtimeEncoder.getOutputFormat());
        realtimeMuxer.start();
        realtimeMuxerStarted = true;
      } else if (outIdx >= 0) {
        if (realtimeMuxerStarted && info.size > 0) {
          java.nio.ByteBuffer outBuf = realtimeEncoder.getOutputBuffer(outIdx);
          outBuf.position(info.offset);
          outBuf.limit(info.offset + info.size);
          realtimeMuxer.writeSampleData(realtimeMuxerTrack, outBuf, info);
        }
        realtimeEncoder.releaseOutputBuffer(outIdx, false);
        if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
      } else {
        break;
      }
    }
  }

  private void finalizeRealtimeEncoder() {
    if (realtimeEncoder == null) return;
    try {
      // Submit remaining samples + EOS
      if (encoderAccumFilled > 0) {
        java.util.Arrays.fill(encoderAccumBuf, encoderAccumFilled, OPUS_FRAME_SAMPLES, (short) 0);
        submitFrame(encoderAccumBuf, OPUS_FRAME_SAMPLES, true);
      } else {
        int inIdx = realtimeEncoder.dequeueInputBuffer(5000);
        if (inIdx >= 0) {
          realtimeEncoder.queueInputBuffer(inIdx, 0, 0, realtimePresentationUs,
              android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }
      }
      drainEncoder(5000); // blocking drain until EOS
      Log.i(LOG_TAG, "Realtime Opus encoder finalized");
    } catch (Exception e) {
      Log.e(LOG_TAG, "Error finalizing encoder: " + e.getMessage());
    } finally {
      try { realtimeEncoder.stop(); realtimeEncoder.release(); } catch (Exception ignored) {}
      try { realtimeMuxer.stop(); realtimeMuxer.release(); } catch (Exception ignored) {}
      realtimeEncoder = null;
      realtimeMuxer = null;
    }
  }

  private void compressToAac(String recordingName) {
    {
      String pcmPath = RecordingManager.getPcmAudioPath(this, recordingName);
      String aacPath = RecordingManager.getOpusPath(this, recordingName);
      File pcmFile = new File(pcmPath);
      if (!pcmFile.exists() || pcmFile.length() == 0) return;

      android.media.MediaCodec encoder = null;
      android.media.MediaMuxer muxer = null;
      FileInputStream fis = null;
      final int ENCODE_NOTIF_ID = 2;
      final android.app.NotificationManager nm =
          (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      try {
        Log.i(LOG_TAG, "AAC compression started: " + aacPath);

        android.media.MediaFormat fmt = android.media.MediaFormat.createAudioFormat(
            "audio/mp4a-latm", SAMPLE_RATE, 1);
        fmt.setInteger(android.media.MediaFormat.KEY_BIT_RATE, 16000);
        fmt.setInteger(android.media.MediaFormat.KEY_AAC_PROFILE,
            android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);

        encoder = android.media.MediaCodec.createEncoderByType("audio/mp4a-latm");
        encoder.configure(fmt, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        muxer = new android.media.MediaMuxer(
            aacPath, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        fis = new FileInputStream(pcmFile);

        // 20ms frame at 8kHz: 160 samples = 320 bytes
        final int FRAME_BYTES = SAMPLE_RATE * 2 * 20 / 1000;
        byte[] pcmBuf = new byte[FRAME_BYTES];
        android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
        int trackIndex = -1;
        boolean muxerStarted = false;
        boolean inputDone = false;
        boolean outputDone = false;
        long presentationUs = 0;
        final long totalDurationUs = pcmFile.length() * 1_000_000L / (SAMPLE_RATE * 2);
        final String CHANNEL_ID = "wenet_recording_channel";
        final androidx.core.app.NotificationCompat.Builder notifBuilder =
            new androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("인코딩 중...")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, 0, false);
        nm.notify(ENCODE_NOTIF_ID, notifBuilder.build());
        int lastNotifPct = -1;

        while (!outputDone) {
          if (!inputDone) {
            int inIdx = encoder.dequeueInputBuffer(10000);
            if (inIdx >= 0) {
              java.nio.ByteBuffer inBuf = encoder.getInputBuffer(inIdx);
              inBuf.clear();
              int bytesRead = readPcmChunk(fis, pcmBuf);
              if (bytesRead <= 0) {
                encoder.queueInputBuffer(inIdx, 0, 0, presentationUs,
                    android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                inputDone = true;
              } else {
                inBuf.put(pcmBuf, 0, bytesRead);
                encoder.queueInputBuffer(inIdx, 0, bytesRead, presentationUs, 0);
                presentationUs += (long) bytesRead * 1000000 / (SAMPLE_RATE * 2);
                if (totalDurationUs > 0) {
                  int pct = (int) (presentationUs * 100 / totalDurationUs);
                  if (pct != lastNotifPct) {
                    lastNotifPct = pct;
                    notifBuilder.setContentTitle("인코딩 중... " + pct + "%")
                        .setProgress(100, pct, false);
                    nm.notify(ENCODE_NOTIF_ID, notifBuilder.build());
                  }
                }
              }
            }
          }
          int outIdx = encoder.dequeueOutputBuffer(info, 10000);
          if (outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            trackIndex = muxer.addTrack(encoder.getOutputFormat());
            muxer.start();
            muxerStarted = true;
          } else if (outIdx >= 0) {
            if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
              outputDone = true;
            if (muxerStarted && info.size > 0) {
              java.nio.ByteBuffer outBuf = encoder.getOutputBuffer(outIdx);
              outBuf.position(info.offset);
              outBuf.limit(info.offset + info.size);
              muxer.writeSampleData(trackIndex, outBuf, info);
            }
            encoder.releaseOutputBuffer(outIdx, false);
          }
        }
        long aacSize = new File(aacPath).length();
        Log.i(LOG_TAG, "AAC compression done: " + aacSize / 1024 + " KB");
        nm.cancel(ENCODE_NOTIF_ID);
        // Delete original PCM after successful compression
        if (aacSize > 0) {
          new File(pcmPath).delete();
          Log.i(LOG_TAG, "Deleted original PCM: " + pcmPath);
        }
      } catch (Exception e) {
        Log.e(LOG_TAG, "AAC compression failed: " + e.getMessage());
        nm.cancel(ENCODE_NOTIF_ID);
        new File(aacPath).delete();
      } finally {
        try { if (fis != null) fis.close(); } catch (Exception ignored) {}
        try { if (encoder != null) { encoder.stop(); encoder.release(); } } catch (Exception ignored) {}
        try { if (muxer != null) { muxer.stop(); muxer.release(); } } catch (Exception ignored) {}
      }
    }
  }

  private boolean decodeOpusToPcm(String opusPath, String pcmPath) {
    return decodeOpusToPcm(opusPath, pcmPath, null);
  }

  private boolean decodeOpusToPcm(String opusPath, String pcmPath, java.util.function.Consumer<Integer> onProgress) {
    android.media.MediaExtractor extractor = null;
    android.media.MediaCodec decoder = null;
    java.io.FileOutputStream fos = null;
    try {
      extractor = new android.media.MediaExtractor();
      extractor.setDataSource(opusPath);

      // Find audio track
      int trackIndex = -1;
      android.media.MediaFormat trackFormat = null;
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        android.media.MediaFormat fmt = extractor.getTrackFormat(i);
        if (fmt.getString(android.media.MediaFormat.KEY_MIME).startsWith("audio/")) {
          trackIndex = i;
          trackFormat = fmt;
          break;
        }
      }
      if (trackIndex < 0) return false;
      extractor.selectTrack(trackIndex);
      long totalDurationUs = trackFormat.containsKey(android.media.MediaFormat.KEY_DURATION)
          ? trackFormat.getLong(android.media.MediaFormat.KEY_DURATION) : 0;

      decoder = android.media.MediaCodec.createDecoderByType(
          trackFormat.getString(android.media.MediaFormat.KEY_MIME));
      decoder.configure(trackFormat, null, null, 0);
      decoder.start();

      fos = new java.io.FileOutputStream(pcmPath);
      android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
      boolean inputDone = false;
      boolean outputDone = false;
      // Output format: detected after INFO_OUTPUT_FORMAT_CHANGED
      int outSampleRate = SAMPLE_RATE; // default; updated when format changes
      int outChannels = 1;             // default; updated when format changes

      while (!outputDone) {
        if (!inputDone) {
          int inIdx = decoder.dequeueInputBuffer(10000);
          if (inIdx >= 0) {
            java.nio.ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
            inBuf.clear();
            int sampleSize = extractor.readSampleData(inBuf, 0);
            if (sampleSize < 0) {
              decoder.queueInputBuffer(inIdx, 0, 0, 0,
                  android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputDone = true;
            } else {
              long sampleTimeUs = extractor.getSampleTime();
              decoder.queueInputBuffer(inIdx, 0, sampleSize, sampleTimeUs, 0);
              extractor.advance();
              if (onProgress != null && totalDurationUs > 0) {
                int pct = (int) (sampleTimeUs * 100 / totalDurationUs);
                onProgress.accept(pct);
              }
            }
          }
        }
        int outIdx = decoder.dequeueOutputBuffer(info, 10000);
        if (outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          android.media.MediaFormat newFmt = decoder.getOutputFormat();
          outSampleRate = newFmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
          outChannels = newFmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT);
          Log.i(LOG_TAG, "Decoder output format: " + outSampleRate + "Hz ch=" + outChannels);
        } else if (outIdx >= 0) {
          if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
            outputDone = true;
          if (info.size > 0) {
            java.nio.ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
            outBuf.position(info.offset);
            outBuf.limit(info.offset + info.size);
            byte[] pcmBytes = new byte[info.size];
            outBuf.get(pcmBytes);
            // Downsample to SAMPLE_RATE (8kHz) if needed
            int decimation = (outSampleRate > SAMPLE_RATE) ? (outSampleRate / SAMPLE_RATE) : 1;
            int bytesPerSample = 2; // 16-bit PCM
            int frameBytes = bytesPerSample * outChannels;
            if (decimation == 1 && outChannels == 1) {
              fos.write(pcmBytes);
            } else {
              // Pick one sample per 'decimation' frames, left channel only
              java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
              for (int i = 0; i + frameBytes <= pcmBytes.length; i += frameBytes * decimation) {
                // Write left channel (bytes 0-1 of frame)
                baos.write(pcmBytes[i]);
                baos.write(pcmBytes[i + 1]);
              }
              fos.write(baos.toByteArray());
            }
          }
          decoder.releaseOutputBuffer(outIdx, false);
        }
      }
      Log.i(LOG_TAG, "Opus decoded to PCM: " + new File(pcmPath).length() / 1024 + " KB");
      return true;
    } catch (Exception e) {
      Log.e(LOG_TAG, "decodeOpusToPcm failed: " + e.getMessage());
      new File(pcmPath).delete();
      return false;
    } finally {
      try { if (fos != null) fos.close(); } catch (Exception ignored) {}
      try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception ignored) {}
      try { if (extractor != null) extractor.release(); } catch (Exception ignored) {}
    }
  }

  private void saveTimedResult() {
    if (currentRecordingName == null) return;
    try {
      String timedJson = Recognize.getTimedResult();
      Log.i(LOG_TAG, "Timed result: " + timedJson);

      String resultPath = RecordingManager.getResultPath(this, currentRecordingName);
      FileOutputStream fos = new FileOutputStream(resultPath);
      fos.write(timedJson.getBytes("UTF-8"));
      fos.flush();
      fos.close();
      Log.i(LOG_TAG, "Saved result.json to " + resultPath);
    } catch (Exception e) {
      Log.e(LOG_TAG, "Error saving timed result: " + e.getMessage());
    }
  }

  /** Build live display using delta APIs: only new tokens from native, O(1) JNI cost. */
  private void updateLiveDisplayIncremental() {
    try {
      // Process delta tokens (same logic as buildLiveDisplay)
      String deltaJson = Recognize.getTimedResultDelta();
      if (deltaJson != null && !"[]".equals(deltaJson)) {
        org.json.JSONArray arr = new org.json.JSONArray(deltaJson);
        for (int i = 0; i < arr.length(); i++) {
          org.json.JSONObject obj = arr.getJSONObject(i);
          String w = obj.getString("w");
          int startMs = obj.getInt("s");
          if ("\n".equals(w)) {
            if (cachedInProgressSentence.length() > 0) {
              cachedConfirmedText.append("[").append(formatTimeMs(cachedInProgressStartMs)).append("] ")
                  .append(cachedInProgressSentence.toString().trim()).append("\n");
              cachedInProgressSentence = new StringBuilder();
              cachedInProgressStartMs = -1;
            }
            continue;
          }
          if (cachedInProgressStartMs == -1) cachedInProgressStartMs = startMs;
          if ("\u2581".equals(w)) {
            cachedInProgressSentence.append(" ");
          } else {
            cachedInProgressSentence.append(w);
          }
        }
      }

      // Build tail: in-progress sentence + partial
      String deltaResult = Recognize.getResultDelta();
      String partial = "";
      if (deltaResult != null) {
        int sep = deltaResult.indexOf('\n');
        if (sep >= 0) partial = deltaResult.substring(sep + 1).trim();
      }
      lastPartialText = partial;

      StringBuilder tail = new StringBuilder();
      if (cachedInProgressSentence.length() > 0) {
        tail.append("[").append(formatTimeMs(cachedInProgressStartMs)).append("] ")
            .append(cachedInProgressSentence.toString().trim());
      }
      if (!partial.isEmpty()) {
        if (tail.length() > 0) tail.append("\n");
        tail.append(partial);
      }
      final String tailStr = tail.toString();
      final int newConfirmedLen = cachedConfirmedText.length();

      runOnUiThread(() -> {
        TextView textView = findViewById(R.id.textView);
        ScrollView scrollView = findViewById(R.id.scrollView);
        android.text.Editable editable = textView.getEditableText();
        if (editable == null) return;

        // Append only new confirmed sentences (O(delta))
        if (newConfirmedLen > lastAppendedConfirmedLength) {
          String newConfirmed = cachedConfirmedText.substring(lastAppendedConfirmedLength, newConfirmedLen);
          editable.replace(lastAppendedConfirmedLength, editable.length(), newConfirmed + tailStr);
          lastAppendedConfirmedLength = newConfirmedLen;
        } else {
          // Only tail changed: replace from confirmed end to view end (O(tail))
          editable.replace(lastAppendedConfirmedLength, editable.length(), tailStr);
        }

        boolean atBottom = (scrollView.getChildAt(0).getBottom()
            - scrollView.getHeight() - scrollView.getScrollY()) < 100;
        if (atBottom) scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
      });
    } catch (Exception e) {
      Log.e(LOG_TAG, "Error in updateLiveDisplayIncremental: " + e.getMessage());
    }
  }

  private String buildLiveDisplay() {
    try {
      // Get only NEW timed tokens from native (O(delta) not O(total))
      String deltaJson = Recognize.getTimedResultDelta();
      if (deltaJson != null && !"[]".equals(deltaJson)) {
        JSONArray arr = new JSONArray(deltaJson);
        for (int i = 0; i < arr.length(); i++) {
          JSONObject obj = arr.getJSONObject(i);
          String w = obj.getString("w");
          int startMs = obj.getInt("s");
          // Newline marker = endpoint boundary
          if ("\n".equals(w)) {
            if (cachedInProgressSentence.length() > 0) {
              cachedConfirmedText.append("[").append(formatTimeMs(cachedInProgressStartMs)).append("] ")
                  .append(cachedInProgressSentence.toString().trim()).append("\n");
              cachedInProgressSentence = new StringBuilder();
              cachedInProgressStartMs = -1;
            }
            continue;
          }
          if (cachedInProgressStartMs == -1) cachedInProgressStartMs = startMs;
          if ("\u2581".equals(w)) {
            cachedInProgressSentence.append(" ");
          } else {
            cachedInProgressSentence.append(w);
          }
        }
      }

      // Build display string
      String confirmed;
      if (cachedInProgressSentence.length() > 0) {
        confirmed = cachedConfirmedText.toString()
            + "[" + formatTimeMs(cachedInProgressStartMs) + "] "
            + cachedInProgressSentence.toString().trim();
      } else if (cachedConfirmedText.length() > 0) {
        confirmed = cachedConfirmedText.substring(0, cachedConfirmedText.length() - 1);
      } else {
        confirmed = "";
      }

      // Get only partial result from native via delta API
      String deltaResult = Recognize.getResultDelta();
      String partial = "";
      if (deltaResult != null) {
        int sep = deltaResult.indexOf('\n');
        if (sep >= 0) {
          partial = deltaResult.substring(sep + 1).trim();
        }
      }
      lastPartialText = partial;

      String display;
      if (confirmed.isEmpty()) display = partial;
      else if (partial.isEmpty()) display = confirmed;
      else display = confirmed + "\n" + partial;
      lastDisplayedText = display;
      return display;
    } catch (Exception e) {
      Log.e(LOG_TAG, "Error in buildLiveDisplay: " + e.getMessage());
      return "";
    }
  }

  private String buildTimestampedText(String timedJson) {
    try {
      JSONArray arr = new JSONArray(timedJson);
      if (arr.length() == 0) return "";
      StringBuilder result = new StringBuilder();
      StringBuilder sentence = new StringBuilder();
      int sentenceStartMs = -1;
      for (int i = 0; i < arr.length(); i++) {
        JSONObject obj = arr.getJSONObject(i);
        String w = obj.getString("w");
        int startMs = obj.getInt("s");
        // Newline marker = endpoint boundary
        if ("\n".equals(w)) {
          if (sentence.toString().trim().length() > 0) {
            result.append("[").append(formatTimeMs(sentenceStartMs)).append("] ")
                .append(sentence.toString().trim()).append("\n");
            sentence = new StringBuilder();
            sentenceStartMs = -1;
          }
          continue;
        }
        if (sentenceStartMs == -1) sentenceStartMs = startMs;
        if ("\u2581".equals(w)) {
          sentence.append(" ");
        } else {
          sentence.append(w);
        }
      }
      if (sentence.toString().trim().length() > 0) {
        result.append("[").append(formatTimeMs(sentenceStartMs)).append("] ")
            .append(sentence.toString().trim()).append("\n");
      }
      return result.toString().trim();
    } catch (Exception e) {
      Log.e(LOG_TAG, "Error building timestamped text: " + e.getMessage());
      return "";
    }
  }

  private String buildTimestampedTextFromSpans(List<WordSpan> spans) {
    if (spans == null || spans.isEmpty()) return "";
    StringBuilder result = new StringBuilder();
    StringBuilder sentence = new StringBuilder();
    int sentenceStartMs = -1;
    for (WordSpan ws : spans) {
      // Newline marker = endpoint boundary
      if ("\n".equals(ws.word)) {
        if (sentence.toString().trim().length() > 0) {
          result.append("[").append(formatTimeMs(sentenceStartMs)).append("] ")
              .append(sentence.toString().trim()).append("\n");
          sentence = new StringBuilder();
          sentenceStartMs = -1;
        }
        continue;
      }
      if (sentenceStartMs == -1) sentenceStartMs = ws.startMs;
      if ("\u2581".equals(ws.word)) {
        sentence.append(" ");
      } else {
        sentence.append(ws.word);
      }
    }
    if (sentence.toString().trim().length() > 0) {
      result.append("[").append(formatTimeMs(sentenceStartMs)).append("] ")
          .append(sentence.toString().trim()).append("\n");
    }
    return result.toString().trim();
  }

  // --- Playback ---

  private void enterPlaybackMode(String recordingName) {
    currentPlaybackRecording = recordingName;
    if (getSupportActionBar() != null) getSupportActionBar().setTitle(recordingName);

    String opusPath = RecordingManager.findAudioPath(this, recordingName);
    if (opusPath == null) {
      Toast.makeText(this, "Audio file not found", Toast.LENGTH_SHORT).show();
      return;
    }

    // prepare() initialises MediaExtractor + MediaCodec — must run off main thread
    new Thread(() -> {
      OggStreamPlayer player = new OggStreamPlayer(opusPath);
      if (!player.prepare()) {
        runOnUiThread(() -> Toast.makeText(this, "오디오 열기 실패", Toast.LENGTH_SHORT).show());
        return;
      }
      long durationMs = player.getTotalDurationMs();
      runOnUiThread(() -> {
        if (oggPlayer != null) { oggPlayer.release(); }
        oggPlayer = player;
        playbackAudioPath = opusPath;
        playbackDurationMs = durationMs;
        pcmFileLength = durationMs * SAMPLE_RATE * 2 / 1000;

        oggPlayer.setListener(new OggStreamPlayer.Listener() {
          @Override public void onPositionMs(int ms) {
            if (!userSeekingBar && isPlaying) {
              playbackPositionMs = ms;
              updatePlaybackUI(ms);
              updateKaraokeHighlight(ms);
              updateVisualizationCursor(ms);
            }
          }
          @Override public void onPlaybackComplete() {
            isPlaying = false;
            playbackPositionMs = 0;
            Button ppBtn = findViewById(R.id.playPauseButton);
            ppBtn.setText("Play");
            updatePlaybackUI(0);
            updateKaraokeHighlight(0);
            if (useSpectrogram) {
              ((SpectrogramView) findViewById(R.id.spectrogramView)).setCursorPosition(0f);
            } else {
              ((VoiceRectView) findViewById(R.id.voiceRectView)).setCursorPosition(0f);
            }
            ((VadProbView) findViewById(R.id.vadProbView)).setCursorPosition(0f);
          }
        });

        enterPlaybackModeWithOgg(recordingName, opusPath, durationMs);
      });
    }).start();
  }

  private void enterPlaybackModeWithOgg(String recordingName, String opusPath, long durationMs) {
    int durMs = (int) Math.min(durationMs, Integer.MAX_VALUE);

    wordSpans = loadWordSpans(recordingName);
    timestampedResult = buildTimestampedTextFromSpans(wordSpans);

    LinearLayout playbackLayout = findViewById(R.id.playbackLayout);
    playbackLayout.setVisibility(View.VISIBLE);

    SeekBar seekBar = findViewById(R.id.seekBar);
    seekBar.setMax(durMs);
    seekBar.setProgress(0);

    TextView timeText = findViewById(R.id.timeTextView);
    timeText.setText(formatTimeMs(0) + "/" + formatTimeMs(durMs));

    Button playPauseButton = findViewById(R.id.playPauseButton);
    playPauseButton.setText("Play");

    playbackPositionMs = 0;
    isPlaying = false;

    buildKaraokeText();
    updateKaraokeHighlight(0);

    // Load visualization in background (no full decode needed for waveform energy bars)
    new Thread(() -> loadFullVisualizationOgg(opusPath, durationMs)).start();
  }

  /**
   * Loads visualization data from OGG without full pre-decode.
   * Waveform: decoded progressively via PcmDataSource (on-demand per-bar window).
   * Spectrogram: OggPcmDataSource passed directly (decodes on-demand per-column).
   */
  /**
   * Single-pass sequential OGG decode → normalized energy bars [0..1].
   * Creates ONE MediaExtractor+MediaCodec, reads forward without random seek.
   */
  private double[] decodeOggEnergyBars(String opusPath, long totalSamples8kHz) {
    final int MAX_BARS = 5000;
    long samplesPerBar = Math.max(512, totalSamples8kHz / MAX_BARS);
    int barCount = (int) (totalSamples8kHz / samplesPerBar);
    if (barCount == 0) barCount = 1;
    double[] rawDb = new double[barCount];
    double maxDb = -999;
    int barsFilled = 0;

    android.media.MediaExtractor extractor = null;
    android.media.MediaCodec decoder = null;
    try {
      extractor = new android.media.MediaExtractor();
      extractor.setDataSource(opusPath);
      int trackIdx = -1;
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        android.media.MediaFormat fmt = extractor.getTrackFormat(i);
        String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("audio/")) { trackIdx = i; break; }
      }
      if (trackIdx < 0) return new double[0];
      extractor.selectTrack(trackIdx);
      android.media.MediaFormat fmt = extractor.getTrackFormat(trackIdx);
      String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
      decoder = android.media.MediaCodec.createDecoderByType(mime);
      decoder.configure(fmt, null, null, 0);
      decoder.start();

      int decimation = 6; // default 48kHz→8kHz
      boolean formatKnown = false;
      boolean inputDone = false;
      boolean outputDone = false;
      android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();

      // Accumulator for current bar
      double barSum = 0;
      long barSampleCount = 0;

      while (!outputDone && barsFilled < barCount) {
        if (!inputDone) {
          int inIdx = decoder.dequeueInputBuffer(5000);
          if (inIdx >= 0) {
            java.nio.ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
            int n = extractor.readSampleData(inBuf, 0);
            if (n < 0) {
              decoder.queueInputBuffer(inIdx, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputDone = true;
            } else {
              decoder.queueInputBuffer(inIdx, 0, n, extractor.getSampleTime(), 0);
              extractor.advance();
            }
          }
        }
        int outIdx = decoder.dequeueOutputBuffer(info, 5000);
        if (outIdx == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          android.media.MediaFormat nf = decoder.getOutputFormat();
          int outRate = nf.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)
              ? nf.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE) : 48000;
          decimation = Math.max(1, outRate / SAMPLE_RATE);
          formatKnown = true;
        } else if (outIdx >= 0) {
          java.nio.ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
          if (outBuf != null && info.size > 0 && formatKnown) {
            byte[] raw = new byte[info.size];
            outBuf.position(info.offset);
            outBuf.get(raw, 0, info.size);
            int frameBytes = 2; // 16-bit mono out
            for (int i = 0; i + 1 < raw.length && barsFilled < barCount; i += decimation * frameBytes) {
              short s = (short) ((raw[i] & 0xFF) | (raw[i + 1] << 8));
              barSum += (double) s * s;
              barSampleCount++;
              if (barSampleCount >= samplesPerBar) {
                double rms = Math.sqrt(barSum / barSampleCount);
                double db = 20.0 * Math.log10(rms + 1e-10);
                rawDb[barsFilled++] = db;
                if (db > maxDb) maxDb = db;
                barSum = 0; barSampleCount = 0;
              }
            }
          }
          decoder.releaseOutputBuffer(outIdx, false);
          if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
        }
      }
    } catch (Exception e) {
      Log.e(LOG_TAG, "decodeOggEnergyBars error: " + e.getMessage());
    } finally {
      if (decoder != null) { try { decoder.stop(); decoder.release(); } catch (Exception ignored) {} }
      if (extractor != null) { extractor.release(); }
    }

    if (barsFilled == 0) return new double[0];
    double floor = maxDb - 60;
    double range = (maxDb - floor) < 1e-9 ? 1.0 : maxDb - floor;
    double[] energies = new double[barsFilled];
    for (int i = 0; i < barsFilled; i++) {
      energies[i] = Math.max(0, Math.min(1.0, (rawDb[i] - floor) / range));
    }
    return energies;
  }

  private int readPcmChunk(FileInputStream fis, byte[] buf) throws java.io.IOException {
    int total = 0;
    while (total < buf.length) {
      int read = fis.read(buf, total, buf.length - total);
      if (read < 0) break;
      total += read;
    }
    return total;
  }

  private void loadFullVisualizationOgg(String opusPath, long durationMs) {
    try {
      long totalSamples = durationMs * SAMPLE_RATE / 1000;
      int durMs = (int) Math.min(durationMs, Integer.MAX_VALUE);

      if (useSpectrogram) {
        PcmDataSource src = new PcmDataSource.OggPcmDataSource(opusPath, durationMs);
        SpectrogramView sv = findViewById(R.id.spectrogramView);
        runOnUiThread(() -> {
          sv.setDataSource(src, totalSamples);
          sv.setOnPlaybackSeekListener(new SpectrogramView.OnPlaybackSeekListener() {
            @Override public void onSeek(int ms) { seekToMs(ms); }
            @Override public void onZoomChanged(float visibleSeconds) {
              ((VoiceRectView) findViewById(R.id.voiceRectView)).setVisibleSeconds(visibleSeconds);
            }
          });
        });
      } else {
        // Single-pass sequential OGG decode → energy bars (no random seek, one decoder)
        double[] energies = decodeOggEnergyBars(opusPath, totalSamples);
        final double[] finalEnergies = energies;
        runOnUiThread(() -> {
          VoiceRectView vv = findViewById(R.id.voiceRectView);
          vv.setFullWaveform(finalEnergies, durMs);
          vv.setOnPlaybackSeekListener(new VoiceRectView.OnPlaybackSeekListener() {
            @Override public void onSeek(int ms) { seekToMs(ms); }
            @Override public void onZoomChanged(float visibleSeconds) {
              ((SpectrogramView) findViewById(R.id.spectrogramView)).setWindowSizeMs((int) (visibleSeconds * 1000));
            }
          });
        });
      }
    } catch (Exception e) {
      Log.e(LOG_TAG, "loadFullVisualizationOgg error: " + e.getMessage());
    }
  }

  private List<WordSpan> loadWordSpans(String recordingName) {
    List<WordSpan> spans = new ArrayList<>();
    try {
      String resultPath = RecordingManager.getResultPath(this, recordingName);
      File f = new File(resultPath);
      if (!f.exists()) return spans;
      FileInputStream fis = new FileInputStream(f);
      byte[] data = new byte[(int) f.length()];
      fis.read(data);
      fis.close();
      JSONArray arr = new JSONArray(new String(data, "UTF-8"));
      int lastEndMs = 0;
      for (int i = 0; i < arr.length(); i++) {
        JSONObject obj = arr.getJSONObject(i);
        String word = obj.getString("w");
        int start = obj.getInt("s");
        int end = obj.getInt("e");
        
        // Fix for tokens with 0 timestamps (like \n) breaking binary search sorting
        if (start == 0 && end == 0 && i > 0) {
            start = lastEndMs;
            end = lastEndMs;
        }
        
        spans.add(new WordSpan(word, start, end));
        if (end > lastEndMs) {
            lastEndMs = end;
        }
      }
    } catch (Exception e) {
      Log.e(LOG_TAG, "Error loading word spans: " + e.getMessage());
    }
    return spans;
  }

  private void resumePlayback() {
    if (playbackAudioPath == null || oggPlayer == null) return;

    if (startRecord) {
      startRecord = false;
      Recognize.setInputFinished();
      Button button = findViewById(R.id.button);
      button.setText("Record");
      stopBluetoothMic();
      try { Thread.sleep(500); } catch (InterruptedException ignored) {}
    }

    // Reset audio routing
    try {
      audioManager.setMode(AudioManager.MODE_NORMAL);
      audioManager.setBluetoothScoOn(false);
      audioManager.setSpeakerphoneOn(false);
      if (Build.VERSION.SDK_INT >= 31) {
        audioManager.clearCommunicationDevice();
      } else {
        audioManager.stopBluetoothSco();
      }
    } catch (Exception ignored) {}

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      audioManager.requestAudioFocus(new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
          .setAudioAttributes(new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
              .build())
          .build());
    } else {
      audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    isPlaying = true;
    Button playPauseButton = findViewById(R.id.playPauseButton);
    playPauseButton.setText("Pause");

    oggPlayer.resume(playbackPositionMs);
  }

  private void pausePlayback() {
    if (oggPlayer != null) {
      playbackPositionMs = oggPlayer.pause();
    }
    isPlaying = false;
    Button playPauseButton = findViewById(R.id.playPauseButton);
    playPauseButton.setText("Play");
    stopPlaybackUpdater();
  }

  private void stopPlayback() {
    isPlaying = false;
    stopPlaybackUpdater();
    if (oggPlayer != null) {
      oggPlayer.release();
      oggPlayer = null;
    }
    playbackPositionMs = 0;
    playbackAudioPath = null;
    playbackDurationMs = 0;
    pcmFileLength = 0;
    ((VoiceRectView) findViewById(R.id.voiceRectView)).clearPlaybackMode();
    ((SpectrogramView) findViewById(R.id.spectrogramView)).clearPlaybackMode();
    ((VadProbView) findViewById(R.id.vadProbView)).clearPlaybackMode();
  }

  private void seekToMs(int ms) {
    if (ms < 0) ms = 0;
    if (ms > playbackDurationMs) ms = (int) playbackDurationMs;
    boolean wasPlaying = isPlaying;
    if (wasPlaying) pausePlayback();
    playbackPositionMs = ms;
    updatePlaybackUI(ms);
    updateKaraokeHighlight(ms);
    updateVisualizationCursor(ms);
    if (wasPlaying) resumePlayback();
  }

  private void startPlaybackUpdater() {
    // OGG player calls onPositionMs directly; this updater is kept for seekBar drag-only updates
    stopPlaybackUpdater();
  }

  private void stopPlaybackUpdater() {
    if (playbackUpdater != null) {
      uiHandler.removeCallbacks(playbackUpdater);
      playbackUpdater = null;
    }
  }

  private void updateVisualizationCursor(int currentMs) {
    SeekBar seekBar = findViewById(R.id.seekBar);
    int durationMs = seekBar.getMax();
    float fraction = (durationMs > 0) ? (float) currentMs / durationMs : 0f;
    if (useSpectrogram) {
      ((SpectrogramView) findViewById(R.id.spectrogramView)).setCursorPosition(fraction);
    } else {
      ((VoiceRectView) findViewById(R.id.voiceRectView)).setCursorPosition(fraction);
    }
  }

  private void updatePlaybackUI(int currentMs) {
    SeekBar seekBar = findViewById(R.id.seekBar);
    TextView timeText = findViewById(R.id.timeTextView);
    int durationMs = (int) playbackDurationMs;
    seekBar.setProgress(currentMs);
    timeText.setText(formatTimeMs(currentMs) + "/" + formatTimeMs(durationMs));
  }

  private int bytesToMs(long bytes) {
    return (int) (bytes * 1000L / (SAMPLE_RATE * 2));
  }

  private String formatTimeMs(int ms) {
    int totalSec = ms / 1000;
    int h = totalSec / 3600;
    int m = (totalSec % 3600) / 60;
    int s = totalSec % 60;
    return String.format("%02d:%02d:%02d", h, m, s);
  }

  // --- Karaoke ---

  /** Build the SpannableStringBuilder in timestamped format with ClickableSpans. */
  private void buildKaraokeText() {
    if (wordSpans == null || wordSpans.isEmpty()) return;

    karaokeSSB = new SpannableStringBuilder();
    karaokeSpanStarts = new int[wordSpans.size()];
    karaokeSpanEnds = new int[wordSpans.size()];
    lastHighlightIndex = -1;
    currentBgSpan = null;
    currentFgSpan = null;

    // Group words into segments by endpoint boundary markers
    int sentenceStartMs = -1;
    boolean needNewLine = false;
    for (int i = 0; i < wordSpans.size(); i++) {
      WordSpan ws = wordSpans.get(i);

      // Newline marker = endpoint boundary
      if ("\n".equals(ws.word)) {
        if (sentenceStartMs != -1) {
          sentenceStartMs = -1;
          needNewLine = true;
        }
        karaokeSpanStarts[i] = karaokeSSB.length();
        karaokeSpanEnds[i] = karaokeSSB.length();
        continue;
      }

      // Start of a new segment: insert timestamp prefix
      if (sentenceStartMs == -1) {
        if (needNewLine) karaokeSSB.append("\n");
        sentenceStartMs = ws.startMs;
        karaokeSSB.append("[").append(formatTimeMs(sentenceStartMs)).append("] ");
      }

      // Skip space token but add a space character
      if ("\u2581".equals(ws.word)) {
        karaokeSpanStarts[i] = karaokeSSB.length();
        karaokeSSB.append(" ");
        karaokeSpanEnds[i] = karaokeSSB.length();
      } else {
        karaokeSpanStarts[i] = karaokeSSB.length();
        karaokeSSB.append(ws.word);
        karaokeSpanEnds[i] = karaokeSSB.length();
      }
    }

    TextView textView = findViewById(R.id.textView);
    textView.setText(karaokeSSB, TextView.BufferType.SPANNABLE);
  }

  /** Update only the highlight span (called every 50ms). Modifies Spannable in-place, no setText(). */
  private void updateKaraokeHighlight(int currentMs) {
    if (wordSpans == null || wordSpans.isEmpty()) return;
    TextView textView = findViewById(R.id.textView);
    CharSequence cs = textView.getText();
    if (!(cs instanceof android.text.Spannable)) return;
    android.text.Spannable spannable = (android.text.Spannable) cs;

    // Binary search: find word where startMs <= currentMs < endMs
    int currentIndex = -1;
    int lo = 0, hi = wordSpans.size() - 1;
    while (lo <= hi) {
      int mid = (lo + hi) / 2;
      WordSpan ws = wordSpans.get(mid);
      if (currentMs < ws.startMs) { hi = mid - 1; }
      else if (currentMs >= ws.endMs) { lo = mid + 1; }
      else { currentIndex = mid; break; }
    }
    // Fallback: last word with startMs <= currentMs
    if (currentIndex == -1 && currentMs > 0) {
      lo = 0; hi = wordSpans.size() - 1;
      while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (wordSpans.get(mid).startMs <= currentMs) { currentIndex = mid; lo = mid + 1; }
        else { hi = mid - 1; }
      }
    }

    // Skip if same word is already highlighted
    if (currentIndex == lastHighlightIndex) return;

    // Fix: if current index is a special token with no length (like \n) or just a space,
    // find the previous visible word so highlighting doesn't disappear.
    if (currentIndex >= 0) {
      int originalIndex = currentIndex;
      while (currentIndex >= 0 &&
          (karaokeSpanEnds[currentIndex] - karaokeSpanStarts[currentIndex] <= 0
           || "\u2581".equals(wordSpans.get(currentIndex).word))) {
        currentIndex--;
      }
      // If we went too far back, revert to the found index (could be -1 if no visible word before)
      if (currentIndex < 0 && originalIndex >= 0) {
          // If no previous visible word, stay at -1 (no highlight) or try to find first visible word after
          currentIndex = -1;
      }
    }

    // Skip again after potential index adjustment
    if (currentIndex == lastHighlightIndex) return;
    if (currentBgSpan != null) {
      spannable.removeSpan(currentBgSpan);
    }
    if (currentFgSpan != null) {
      spannable.removeSpan(currentFgSpan);
    }

    // Apply new highlight directly on the Spannable (no setText needed)
    if (currentIndex >= 0) {
      currentBgSpan = new BackgroundColorSpan(Color.parseColor("#FFFF00"));
      currentFgSpan = new ForegroundColorSpan(Color.BLACK);
      spannable.setSpan(currentBgSpan,
          karaokeSpanStarts[currentIndex], karaokeSpanEnds[currentIndex],
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      spannable.setSpan(currentFgSpan,
          karaokeSpanStarts[currentIndex], karaokeSpanEnds[currentIndex],
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    } else {
      currentBgSpan = null;
      currentFgSpan = null;
    }

    lastHighlightIndex = currentIndex;

    // Auto-scroll text to show highlighted word
    if (currentIndex >= 0) {
      ScrollView scrollView = findViewById(R.id.scrollView);
      int line = textView.getLayout() != null
          ? textView.getLayout().getLineForOffset(karaokeSpanStarts[currentIndex]) : 0;
      int y = textView.getLayout() != null
          ? textView.getLayout().getLineTop(line) : 0;
      int scrollViewHeight = scrollView.getHeight();
      scrollView.smoothScrollTo(0, Math.max(0, y - scrollViewHeight / 3));
    }
  }

  /** Binary search: find word index whose span contains the given character offset. */
  private int findWordAtCharOffset(int offset) {
    if (karaokeSpanStarts == null || karaokeSpanEnds == null) return -1;
    int lo = 0, hi = karaokeSpanStarts.length - 1;
    while (lo <= hi) {
      int mid = (lo + hi) / 2;
      if (karaokeSpanEnds[mid] <= offset) { lo = mid + 1; }
      else if (karaokeSpanStarts[mid] > offset) { hi = mid - 1; }
      else { return mid; }
    }
    return -1;
  }

  // --- Recordings Dialog ---

  private void showRecordingsDialog() {
    // Step 1: Directory listing only — instant, no I/O
    List<String> allNames = RecordingManager.listRecordings(this);
    if (allNames.isEmpty()) {
      Toast.makeText(this, "No recordings found", Toast.LENGTH_SHORT).show();
      return;
    }

    View dialogView = getLayoutInflater().inflate(R.layout.dialog_recordings, null);
    EditText searchEdit = dialogView.findViewById(R.id.searchEditText);
    ListView listView = dialogView.findViewById(R.id.recordingsListView);
    TextView loadingText = dialogView.findViewById(R.id.loadingText);
    loadingText.setVisibility(View.GONE);
    listView.setVisibility(View.VISIBLE);

    // Detail cache: name → loaded SearchResult (preview + duration)
    java.util.concurrent.ConcurrentHashMap<String, RecordingManager.SearchResult> detailCache =
        new java.util.concurrent.ConcurrentHashMap<>();
    java.util.Set<String> loadingSet =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newFixedThreadPool(4);

    // Display list: initially just names with empty placeholders
    List<RecordingManager.SearchResult> displayList = new ArrayList<>();
    for (String name : allNames) {
      displayList.add(new RecordingManager.SearchResult(name, "", 0));
    }

    // Adapter reference needed inside itself for notifyDataSetChanged callback
    final BaseAdapter[] adapterRef = new BaseAdapter[1];
    adapterRef[0] = new BaseAdapter() {
      @Override public int getCount() { return displayList.size(); }
      @Override public Object getItem(int pos) { return displayList.get(pos); }
      @Override public long getItemId(int pos) { return pos; }
      @Override
      public View getView(int pos, View convertView, android.view.ViewGroup parent) {
        if (convertView == null) {
          convertView = getLayoutInflater().inflate(R.layout.item_recording, parent, false);
        }
        RecordingManager.SearchResult sr = displayList.get(pos);
        String name = sr.name;
        ((TextView) convertView.findViewById(R.id.recordingName)).setText(name);
        TextView previewView = convertView.findViewById(R.id.recordingPreview);
        TextView durationView = convertView.findViewById(R.id.recordingDuration);

        RecordingManager.SearchResult cached = detailCache.get(name);
        if (cached != null) {
          // Already loaded — show details
          previewView.setText(cached.preview);
          if (cached.durationMs > 0) {
            long totalSec = cached.durationMs / 1000;
            int h = (int)(totalSec / 3600), m = (int)((totalSec % 3600) / 60), s = (int)(totalSec % 60);
            durationView.setText(String.format("[%02d:%02d:%02d]", h, m, s));
          } else {
            durationView.setText("");
          }
        } else {
          // Not yet loaded — show empty and trigger async load
          previewView.setText("");
          durationView.setText("");
          if (loadingSet.add(name)) {
            executor.submit(() -> {
              String text = RecordingManager.loadResultText(MainActivity.this, name);
              String preview = text.length() > 60 ? text.substring(0, 60) + "..." : text;
              if (preview.isEmpty()) preview = "(no text)";
              long durationMs = RecordingManager.loadDurationMs(MainActivity.this, name);
              detailCache.put(name, new RecordingManager.SearchResult(name, preview, durationMs));
              runOnUiThread(() -> adapterRef[0].notifyDataSetChanged());
            });
          }
        }

        convertView.setBackgroundColor(
            name.equals(currentPlaybackRecording) ? 0x332196F3 : Color.TRANSPARENT);
        return convertView;
      }
    };
    BaseAdapter adapter = adapterRef[0];
    listView.setAdapter(adapter);

    AlertDialog dialog = new AlertDialog.Builder(this)
        .setTitle("Recordings")
        .setView(dialogView)
        .setNegativeButton("Cancel", (d, w) -> executor.shutdownNow())
        .create();
    dialog.setOnCancelListener(d -> executor.shutdownNow());

    listView.setOnItemClickListener((parent, view, pos, id) -> {
      executor.shutdownNow();
      dialog.dismiss();
      stopPlayback();
      enterPlaybackMode(displayList.get(pos).name);
    });

    listView.setOnItemLongClickListener((parent, view, pos, id) -> {
      RecordingManager.SearchResult sr = displayList.get(pos);
      new AlertDialog.Builder(this)
          .setTitle(sr.name)
          .setItems(new String[]{"이름 변경", "삭제"}, (d, which) -> {
            if (which == 0) {
              // 이름 변경
              EditText input = new EditText(this);
              input.setText(sr.name);
              input.selectAll();
              new AlertDialog.Builder(this)
                  .setTitle("이름 변경")
                  .setView(input)
                  .setPositiveButton("확인", (d2, w2) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(sr.name)) return;
                    String result = RecordingManager.renameRecording(this, sr.name, newName);
                    if (result != null) {
                      RecordingManager.SearchResult updated = detailCache.get(sr.name);
                      String preview = updated != null ? updated.preview : sr.preview;
                      long durMs = updated != null ? updated.durationMs : sr.durationMs;
                      displayList.set(pos, new RecordingManager.SearchResult(newName, preview, durMs));
                      detailCache.remove(sr.name);
                      adapter.notifyDataSetChanged();
                      if (sr.name.equals(currentPlaybackRecording)) currentPlaybackRecording = newName;
                      Toast.makeText(this, "이름 변경 완료", Toast.LENGTH_SHORT).show();
                    } else {
                      Toast.makeText(this, "이름 변경 실패", Toast.LENGTH_SHORT).show();
                    }
                  })
                  .setNegativeButton("취소", null)
                  .show();
            } else {
              // 삭제 확인
              new AlertDialog.Builder(this)
                  .setTitle("삭제")
                  .setMessage("\"" + sr.name + "\" 을 삭제하시겠습니까?")
                  .setPositiveButton("삭제", (d2, w2) -> {
                    RecordingManager.deleteRecording(this, sr.name);
                    displayList.remove(pos);
                    detailCache.remove(sr.name);
                    adapter.notifyDataSetChanged();
                    if (sr.name.equals(currentPlaybackRecording)) currentPlaybackRecording = null;
                    Toast.makeText(this, "삭제 완료", Toast.LENGTH_SHORT).show();
                  })
                  .setNegativeButton("취소", null)
                  .show();
            }
          })
          .show();
      return true;
    });

    searchEdit.setOnEditorActionListener((v, actionId, event) -> {
      String keyword = searchEdit.getText().toString().trim();
      if (keyword.isEmpty()) {
        // Reset to full name list
        displayList.clear();
        for (String name : allNames) {
          RecordingManager.SearchResult cached = detailCache.get(name);
          displayList.add(cached != null ? cached : new RecordingManager.SearchResult(name, "", 0));
        }
        adapter.notifyDataSetChanged();
      } else {
        listView.setVisibility(View.GONE);
        loadingText.setText("검색 중...");
        loadingText.setVisibility(View.VISIBLE);
        new Thread(() -> {
          // Use cached previews where available, otherwise load text for search
          List<RecordingManager.SearchResult> results = new ArrayList<>();
          String kw = keyword.toLowerCase(java.util.Locale.ROOT);
          for (String name : allNames) {
            RecordingManager.SearchResult cached = detailCache.get(name);
            String text = cached != null
                ? cached.preview
                : RecordingManager.loadResultText(MainActivity.this, name);
            if (text.toLowerCase(java.util.Locale.ROOT).contains(kw)) {
              results.add(cached != null ? cached
                  : new RecordingManager.SearchResult(name, text.length() > 60
                      ? text.substring(0, 60) + "..." : text, 0));
            }
          }
          runOnUiThread(() -> {
            loadingText.setVisibility(View.GONE);
            displayList.clear();
            displayList.addAll(results);
            adapter.notifyDataSetChanged();
            listView.setVisibility(View.VISIBLE);
          });
        }).start();
      }
      return true;
    });

    dialog.show();
  }
}
