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
  private FileOutputStream pcmOutputStream = null;

  // Playback (file-streaming, no full load into memory)
  private AudioTrack audioTrack = null;
  private String playbackAudioPath = null;
  private long pcmFileLength = 0;
  private boolean isPlaying = false;
  private volatile long playbackPositionBytes = 0;
  private volatile long playbackStartBytes = 0; // file offset where current playback started
  private boolean userSeekingBar = false;
  private Thread playbackThread = null;
  private final Handler uiHandler = new Handler(Looper.getMainLooper());
  private Runnable playbackUpdater;

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
        try {
          pcmOutputStream = new FileOutputStream(
              RecordingManager.getPcmAudioPath(this, currentRecordingName));
        } catch (IOException e) {
          Log.e(LOG_TAG, "Failed to open PCM output: " + e.getMessage());
          pcmOutputStream = null;
        }
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
          // Save PCM to file
          if (pcmOutputStream != null) {
            try {
              ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, read);
              pcmOutputStream.write(pcmBytes, 0, read * 2);
            } catch (IOException e) {
              Log.e(LOG_TAG, "Error writing PCM: " + e.getMessage());
            }
          }
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
      // Close PCM file
      if (pcmOutputStream != null) {
        try {
          pcmOutputStream.flush();
          pcmOutputStream.close();
        } catch (IOException e) {
          Log.e(LOG_TAG, "Error closing PCM: " + e.getMessage());
        }
        pcmOutputStream = null;
      }
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

    String audioPath = RecordingManager.getAudioPath(this, recordingName);
    File audioFile = new File(audioPath);
    if (!audioFile.exists()) {
      Toast.makeText(this, "Audio file not found", Toast.LENGTH_SHORT).show();
      return;
    }

    playbackAudioPath = audioPath;
    pcmFileLength = audioFile.length();
    int durationMs = (int) (pcmFileLength * 1000L / (SAMPLE_RATE * 2));

    // Load word spans
    wordSpans = loadWordSpans(recordingName);

    // Build timestamped text from word spans
    timestampedResult = buildTimestampedTextFromSpans(wordSpans);

    // Show playback controls
    LinearLayout playbackLayout = findViewById(R.id.playbackLayout);
    playbackLayout.setVisibility(View.VISIBLE);

    // Set up seekbar
    SeekBar seekBar = findViewById(R.id.seekBar);
    seekBar.setMax(durationMs);
    seekBar.setProgress(0);

    TextView timeText = findViewById(R.id.timeTextView);
    timeText.setText(formatTimeMs(0) + "/" + formatTimeMs(durationMs));

    Button playPauseButton = findViewById(R.id.playPauseButton);
    playPauseButton.setText("Play");

    playbackPositionBytes = 0;
    isPlaying = false;

    // Build karaoke text once, then highlight
    buildKaraokeText();
    updateKaraokeHighlight(0);

    // Load full PCM for DAW visualization (background thread to avoid ANR)
    new Thread(() -> loadFullVisualization(audioPath)).start();
  }

  private void loadFullVisualization(String audioPath) {
    try {
      File f = new File(audioPath);
      if (!f.exists() || f.length() == 0) return;

      long fileSize = f.length();
      long totalSamples = fileSize / 2;
      long durationMs = totalSamples * 1000L / SAMPLE_RATE;

      if (useSpectrogram) {
        // setFullSpectrogramFromFile does its own streaming
        SpectrogramView sv = findViewById(R.id.spectrogramView);
        sv.setFullSpectrogramFromFile(audioPath, totalSamples);
        runOnUiThread(() -> sv.setOnPlaybackSeekListener(new SpectrogramView.OnPlaybackSeekListener() {
          @Override
          public void onSeek(int ms) {
            seekToMs(ms);
          }

          @Override
          public void onZoomChanged(float visibleSeconds) {
            // Sync waveform view zoom if it exists
            VoiceRectView vv = findViewById(R.id.voiceRectView);
            vv.setVisibleSeconds(visibleSeconds);
          }
        }));
      } else {
        // Stream file to compute energy bars; cap to 10000 bars for performance
        final int MAX_BARS = 10000;
        long samplesPerBar = Math.max(512, totalSamples / MAX_BARS);
        int barCount = (int) (totalSamples / samplesPerBar);
        if (barCount == 0) barCount = 1;
        double[] rawDb = new double[barCount];
        double maxDb = -999;
        try (FileInputStream fis = new FileInputStream(f)) {
          byte[] chunk = new byte[(int) (samplesPerBar * 2)];
          for (int i = 0; i < barCount; i++) {
            int read = readPcmChunk(fis, chunk);
            if (read < chunk.length) break;
            double sum = 0;
            for (int j = 0; j < (int) samplesPerBar; j++) {
              short s = (short) ((chunk[j * 2] & 0xFF) | (chunk[j * 2 + 1] << 8));
              sum += (double) s * s;
            }
            double rms = Math.sqrt(sum / samplesPerBar);
            double db = 20.0 * Math.log10(rms + 1e-10);
            rawDb[i] = db;
            if (db > maxDb) maxDb = db;
          }
        }
        double floor = maxDb - 60;
        double range = (maxDb - floor) < 1e-9 ? 1.0 : maxDb - floor;
        double[] energies = new double[barCount];
        for (int i = 0; i < barCount; i++) {
          energies[i] = Math.max(0, Math.min(1.0, (rawDb[i] - floor) / range));
        }
        final double[] finalEnergies = energies;
        final int finalDurationMs = (int) Math.min(durationMs, Integer.MAX_VALUE);
        runOnUiThread(() -> {
          VoiceRectView vv = findViewById(R.id.voiceRectView);
          vv.setFullWaveform(finalEnergies, finalDurationMs);
          vv.setOnPlaybackSeekListener(new VoiceRectView.OnPlaybackSeekListener() {
            @Override
            public void onSeek(int ms) {
              seekToMs(ms);
            }

            @Override
            public void onZoomChanged(float visibleSeconds) {
              // Sync spectrogram if it's being used
              SpectrogramView sv = findViewById(R.id.spectrogramView);
              sv.setWindowSizeMs((int) (visibleSeconds * 1000));
            }
          });
        });
      }
    } catch (Exception e) {
      Log.e(LOG_TAG, "Error loading full visualization: " + e.getMessage());
    }
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
    if (playbackAudioPath == null) return;

    if (startRecord) {
      startRecord = false;
      Recognize.setInputFinished();
      Button button = findViewById(R.id.button);
      button.setText("Record");
      
      // Stop BT mic immediately to trigger profile switch
      stopBluetoothMic();
      
      // Wait longer for hardware to switch from HFP/SCO to A2DP
      try { Thread.sleep(500); } catch (InterruptedException ignored) {}
    }

    isPlaying = true;
    Button playPauseButton = findViewById(R.id.playPauseButton);
    playPauseButton.setText("Pause");

    resumePlaybackPcm();

    // Start UI updater
    startPlaybackUpdater();
  }



  private void resumePlaybackPcm() {
    // 1. Reset system audio state and release SCO
    try {
      audioManager.setMode(AudioManager.MODE_NORMAL);
      audioManager.setBluetoothScoOn(false);
      audioManager.setSpeakerphoneOn(false);
      if (Build.VERSION.SDK_INT >= 31) {
        audioManager.clearCommunicationDevice();
      } else {
        audioManager.stopBluetoothSco();
      }
      Log.i(LOG_TAG, "Audio state reset to NORMAL, speakerphone OFF for playback");
    } catch (Exception e) {
      Log.e(LOG_TAG, "Error resetting audio state: " + e.getMessage());
    }

    // 2. Request Audio Focus (Crucial for system to route audio correctly)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      android.media.AudioFocusRequest focusRequest = new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
          .setAudioAttributes(new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
              .build())
          .build();
      audioManager.requestAudioFocus(focusRequest);
    } else {
      audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
    }

    // 3. Release existing AudioTrack
    if (audioTrack != null) {
      try {
        audioTrack.stop();
        audioTrack.release();
      } catch (Exception ignored) {}
      audioTrack = null;
    }

    // 4. Create new AudioTrack with standard MEDIA attributes
    // Let the system handle routing automatically (usually more reliable than manual)
    int bufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);

    audioTrack = new AudioTrack.Builder()
        .setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build())
        .setAudioFormat(new AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build())
        .setBufferSizeInBytes(bufSize)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build();

    Log.i(LOG_TAG, "AudioTrack created with standard USAGE_MEDIA");
    audioTrack.play();
    playbackStartBytes = playbackPositionBytes;

    // Start playback thread — streams from file
    final AudioTrack myTrack = audioTrack; // capture locally to avoid null race
    playbackThread = new Thread(() -> {
      FileInputStream fis = null;
      try {
        fis = new FileInputStream(playbackAudioPath);
        long skipped = 0;
        long toSkip = playbackPositionBytes;
        while (skipped < toSkip) {
          long s = fis.skip(toSkip - skipped);
          if (s <= 0) break;
          skipped += s;
        }
        byte[] chunk = new byte[bufSize];
        long pos = playbackPositionBytes;
        while (isPlaying && pos < pcmFileLength && playbackThread == Thread.currentThread()) {
          int toRead = (int) Math.min(chunk.length, pcmFileLength - pos);
          int bytesRead = fis.read(chunk, 0, toRead);
          if (bytesRead <= 0) break;
          if (myTrack == null) break;
          int written = myTrack.write(chunk, 0, bytesRead);
          if (written > 0) {
            pos += written;
            if (isPlaying) {
              playbackPositionBytes = pos;
            }
          } else {
            break;
          }
        }
      } catch (IOException e) {
        Log.e(LOG_TAG, "Playback stream error: " + e.getMessage());
      } finally {
        if (fis != null) {
          try { fis.close(); } catch (IOException ignored) {}
        }
      }
      if (isPlaying && playbackPositionBytes >= pcmFileLength) {
        runOnUiThread(() -> {
          isPlaying = false;
          playbackPositionBytes = 0;
          Button ppBtn = findViewById(R.id.playPauseButton);
          ppBtn.setText("Play");
          updatePlaybackUI(0);
          updateKaraokeHighlight(0);
          // Reset cursor to start, keep visualization intact
          if (useSpectrogram) {
            ((SpectrogramView) findViewById(R.id.spectrogramView)).setCursorPosition(0f);
          } else {
            ((VoiceRectView) findViewById(R.id.voiceRectView)).setCursorPosition(0f);
          }
          ((VadProbView) findViewById(R.id.vadProbView)).setCursorPosition(0f);
        });
      }
    });
    playbackThread.start();
  }

  private void pausePlayback() {
    isPlaying = false;
    Button playPauseButton = findViewById(R.id.playPauseButton);
    playPauseButton.setText("Play");
    stopPlaybackUpdater();
    if (audioTrack != null) {
      try {
        // Sync position to actual hardware playback head before stopping
        long framesPlayed = audioTrack.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        playbackPositionBytes = playbackStartBytes + framesPlayed * 2;
        audioTrack.pause();
        audioTrack.flush();
        audioTrack.stop();
        audioTrack.release();
      } catch (IllegalStateException e) {
        Log.e(LOG_TAG, "Error stopping AudioTrack: " + e.getMessage());
      }
      audioTrack = null;
    }
    if (playbackThread != null) {
      try { playbackThread.join(500); } catch (InterruptedException ignored) {}
      playbackThread = null;
    }
  }

  private void stopPlayback() {
    isPlaying = false;
    stopPlaybackUpdater();
    if (audioTrack != null) {
      try {
        audioTrack.pause();
        audioTrack.flush();
        audioTrack.stop();
        audioTrack.release();
      } catch (IllegalStateException e) {
        Log.e(LOG_TAG, "Error stopping AudioTrack: " + e.getMessage());
      }
      audioTrack = null;
    }
    playbackPositionBytes = 0;
    playbackAudioPath = null;
    pcmFileLength = 0;
    // Clear DAW visualization
    ((VoiceRectView) findViewById(R.id.voiceRectView)).clearPlaybackMode();
    ((SpectrogramView) findViewById(R.id.spectrogramView)).clearPlaybackMode();
    ((VadProbView) findViewById(R.id.vadProbView)).clearPlaybackMode();
  }

  private void seekToMs(int ms) {
    long bytePos = (long) ms * SAMPLE_RATE * 2 / 1000;
    bytePos = bytePos & ~1L;
    if (bytePos < 0) bytePos = 0;
    if (bytePos > pcmFileLength) bytePos = pcmFileLength;

    boolean wasPlaying = isPlaying;
    if (wasPlaying) {
      pausePlayback();
    }
    playbackPositionBytes = bytePos;
    updatePlaybackUI(ms);
    updateKaraokeHighlight(ms);
    updateVisualizationCursor(ms);
    if (wasPlaying) {
      resumePlayback();
    }
  }

  private void startPlaybackUpdater() {
    stopPlaybackUpdater();
    playbackUpdater = new Runnable() {
      @Override
      public void run() {
        if (isPlaying) {
          if (!userSeekingBar) {
            int ms;
            AudioTrack at = audioTrack;
            if (at != null) {
              // Use hardware playback head for accurate position
              long framesPlayed = at.getPlaybackHeadPosition() & 0xFFFFFFFFL;
              long actualBytes = playbackStartBytes + framesPlayed * 2; // 16-bit mono = 2 bytes/frame
              ms = bytesToMs(actualBytes);
            } else {
              ms = bytesToMs(playbackPositionBytes);
            }
            updatePlaybackUI(ms);
            updateKaraokeHighlight(ms);
            updateVisualizationCursor(ms);
          }
          uiHandler.postDelayed(this, PLAYBACK_UPDATE_MS);
        }
      }
    };
    uiHandler.post(playbackUpdater);
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
    int durationMs = (int) (pcmFileLength * 1000L / (SAMPLE_RATE * 2));
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
    List<RecordingManager.SearchResult> allResults =
        RecordingManager.searchRecordings(this, "");
    if (allResults.isEmpty()) {
      Toast.makeText(this, "No recordings found", Toast.LENGTH_SHORT).show();
      return;
    }

    View dialogView = getLayoutInflater().inflate(R.layout.dialog_recordings, null);
    EditText searchEdit = dialogView.findViewById(R.id.searchEditText);
    ListView listView = dialogView.findViewById(R.id.recordingsListView);

    List<RecordingManager.SearchResult> displayList = new ArrayList<>(allResults);

    BaseAdapter adapter = new BaseAdapter() {
      @Override public int getCount() { return displayList.size(); }
      @Override public Object getItem(int pos) { return displayList.get(pos); }
      @Override public long getItemId(int pos) { return pos; }
      @Override
      public View getView(int pos, View convertView, android.view.ViewGroup parent) {
        if (convertView == null) {
          convertView = getLayoutInflater().inflate(R.layout.item_recording, parent, false);
        }
        RecordingManager.SearchResult sr = displayList.get(pos);
        ((TextView) convertView.findViewById(R.id.recordingName)).setText(sr.name);
        ((TextView) convertView.findViewById(R.id.recordingPreview)).setText(sr.preview);
        // Show audio duration
        TextView durationView = convertView.findViewById(R.id.recordingDuration);
        File audioFile = new File(RecordingManager.getAudioPath(MainActivity.this, sr.name));
        if (audioFile.exists() && audioFile.length() > 0) {
          long totalSec = audioFile.length() / (SAMPLE_RATE * 2);
          int h = (int)(totalSec / 3600);
          int m = (int)((totalSec % 3600) / 60);
          int s = (int)(totalSec % 60);
          durationView.setText(String.format("[%02d:%02d:%02d]", h, m, s));
        } else {
          durationView.setText("");
        }
        // Highlight currently selected recording
        if (sr.name.equals(currentPlaybackRecording)) {
          convertView.setBackgroundColor(0x332196F3); // light blue tint
        } else {
          convertView.setBackgroundColor(Color.TRANSPARENT);
        }
        return convertView;
      }
    };
    listView.setAdapter(adapter);

    AlertDialog dialog = new AlertDialog.Builder(this)
        .setTitle("Recordings")
        .setView(dialogView)
        .setNegativeButton("Cancel", null)
        .create();

    listView.setOnItemClickListener((parent, view, pos, id) -> {
      dialog.dismiss();
      stopPlayback();
      enterPlaybackMode(displayList.get(pos).name);
    });

    listView.setOnItemLongClickListener((parent, view, pos, id) -> {
      RecordingManager.SearchResult sr = displayList.get(pos);
      EditText input = new EditText(this);
      input.setText(sr.name);
      input.selectAll();
      new AlertDialog.Builder(this)
          .setTitle("이름 변경")
          .setView(input)
          .setPositiveButton("확인", (d, which) -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty() || newName.equals(sr.name)) return;
            String result = RecordingManager.renameRecording(this, sr.name, newName);
            if (result != null) {
              // Update display list
              displayList.set(pos, new RecordingManager.SearchResult(newName, sr.preview));
              adapter.notifyDataSetChanged();
              // Update current playback if renamed
              if (sr.name.equals(currentPlaybackRecording)) {
                currentPlaybackRecording = newName;
              }
              Toast.makeText(this, "이름 변경 완료", Toast.LENGTH_SHORT).show();
            } else {
              Toast.makeText(this, "이름 변경 실패", Toast.LENGTH_SHORT).show();
            }
          })
          .setNegativeButton("취소", null)
          .show();
      return true;
    });

    searchEdit.setOnEditorActionListener((v, actionId, event) -> {
      String keyword = searchEdit.getText().toString().trim();
      displayList.clear();
      displayList.addAll(RecordingManager.searchRecordings(
          MainActivity.this, keyword));
      adapter.notifyDataSetChanged();
      return true;
    });

    dialog.show();
  }
}
