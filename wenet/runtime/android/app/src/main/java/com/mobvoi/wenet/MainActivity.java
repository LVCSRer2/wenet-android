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
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
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
import androidx.annotation.NonNull;
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
          // Pre-establish SCO link so next recording will use BT
          String micDevice = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
              .getString("mic_device", "bluetooth");
          if (!"phone".equals(micDevice)) {
            startBluetoothMic();
          }
          if (startRecord) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this,
                "블루투스 연결됨. 녹음을 재시작하세요.", Toast.LENGTH_LONG).show());
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
  private String currentRecordingName = null;
  private FileOutputStream pcmOutputStream = null;

  // Playback (file-streaming, no full load into memory)
  private AudioTrack audioTrack = null;
  private String playbackAudioPath = null;
  private long pcmFileLength = 0;
  private boolean isPlaying = false;
  private volatile long playbackPositionBytes = 0;
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
    textView.setText("");
    textView.setMovementMethod(LinkMovementMethod.getInstance());
    int fontSp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt("result_font_size", 18);
    textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSp);

    Button button = findViewById(R.id.button);
    button.setEnabled(false);

    showModelSelectionDialog();

    // Visualization preference
    useSpectrogram = "spectrogram".equals(
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_VIZ_TYPE, "waveform"));
    updateVisualizationVisibility();

    // dB range sliders for spectrogram
    // Both SeekBars: 0..140 → dB -40..100, Floor <= Ceil enforced
    final int DB_OFFSET = -40;
    SeekBar dbFloorSeekBar = findViewById(R.id.dbFloorSeekBar);
    SeekBar dbCeilSeekBar = findViewById(R.id.dbCeilSeekBar);
    TextView dbRangeLabel = findViewById(R.id.dbRangeLabel);
    SpectrogramView spectrogramView = findViewById(R.id.spectrogramView);

    int savedFloorProgress = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        .getInt("db_floor_progress", 20);
    int savedCeilProgress = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        .getInt("db_ceil_progress", 120);
    dbFloorSeekBar.setProgress(savedFloorProgress);
    dbCeilSeekBar.setProgress(savedCeilProgress);
    double initFloor = savedFloorProgress + DB_OFFSET;
    double initCeil = savedCeilProgress + DB_OFFSET;
    spectrogramView.setDbFloor(initFloor);
    spectrogramView.setDbCeil(initCeil);
    dbRangeLabel.setText(String.format("dB: %.0f ~ %.0f", initFloor, initCeil));

    dbFloorSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
        SeekBar ceilBar = findViewById(R.id.dbCeilSeekBar);
        if (fromUser && progress >= ceilBar.getProgress()) {
          sb.setProgress(ceilBar.getProgress() - 1);
          return;
        }
        double floor = progress + DB_OFFSET;
        double ceil = ceilBar.getProgress() + DB_OFFSET;
        ((SpectrogramView) findViewById(R.id.spectrogramView)).setDbFloor(floor);
        ((TextView) findViewById(R.id.dbRangeLabel))
            .setText(String.format("dB: %.0f ~ %.0f", floor, ceil));
      }
      @Override
      public void onStartTrackingTouch(SeekBar sb) {}
      @Override
      public void onStopTrackingTouch(SeekBar sb) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putInt("db_floor_progress", sb.getProgress()).apply();
      }
    });

    dbCeilSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
        SeekBar floorBar = findViewById(R.id.dbFloorSeekBar);
        if (fromUser && progress <= floorBar.getProgress()) {
          sb.setProgress(floorBar.getProgress() + 1);
          return;
        }
        double ceil = progress + DB_OFFSET;
        double floor = floorBar.getProgress() + DB_OFFSET;
        ((SpectrogramView) findViewById(R.id.spectrogramView)).setDbCeil(ceil);
        ((TextView) findViewById(R.id.dbRangeLabel))
            .setText(String.format("dB: %.0f ~ %.0f", floor, ceil));
      }
      @Override
      public void onStartTrackingTouch(SeekBar sb) {}
      @Override
      public void onStopTrackingTouch(SeekBar sb) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putInt("db_ceil_progress", sb.getProgress()).apply();
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
          seekToMs(progress);
        }
      }
      @Override
      public void onStartTrackingTouch(SeekBar sb) {}
      @Override
      public void onStopTrackingTouch(SeekBar sb) {}
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
      int fp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt("result_font_size", 5);
      ((TextView) findViewById(R.id.textView)).setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fp + 10);
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
      // Check mic device setting
      String micDevice = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
          .getString("mic_device", "bluetooth");
      if ("phone".equals(micDevice)) {
        Log.i(LOG_TAG, "Mic device set to phone, skipping Bluetooth");
        return;
      }

      // Android 12 (API 31)+ requires BLUETOOTH_CONNECT runtime permission
      if (Build.VERSION.SDK_INT >= 31
          && ContextCompat.checkSelfPermission(this, "android.permission.BLUETOOTH_CONNECT")
              != PackageManager.PERMISSION_GRANTED) {
        Log.i(LOG_TAG, "BLUETOOTH_CONNECT permission not granted, using phone mic");
        return;
      }

      // Android 12+ (API 31): use setCommunicationDevice API
      if (Build.VERSION.SDK_INT >= 31) {
        List<android.media.AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
        for (android.media.AudioDeviceInfo device : devices) {
          Log.i(LOG_TAG, "Available comm device: type=" + device.getType()
              + " name=" + device.getProductName());
          if (device.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
            boolean result = audioManager.setCommunicationDevice(device);
            Log.i(LOG_TAG, "setCommunicationDevice(BT_SCO) = " + result
                + " name=" + device.getProductName());
            if (result) {
              bluetoothScoOn = true;
              return;
            }
          }
        }
        Log.i(LOG_TAG, "No BT SCO device found in available communication devices");
        return;
      }

      // Legacy path for Android < 12
      if (!audioManager.isBluetoothScoAvailableOffCall()) {
        Log.i(LOG_TAG, "Bluetooth SCO not available off call, using phone mic");
        return;
      }
      audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
      audioManager.startBluetoothSco();
      audioManager.setBluetoothScoOn(true);
      bluetoothScoOn = true;
      Log.i(LOG_TAG, "Bluetooth SCO requested (legacy)");
    } catch (Exception e) {
      Log.i(LOG_TAG, "Bluetooth SCO not available: " + e.getMessage());
      bluetoothScoOn = false;
    }
  }

  private void stopBluetoothMic() {
    if (bluetoothScoOn) {
      try {
        if (Build.VERSION.SDK_INT >= 31) {
          audioManager.clearCommunicationDevice();
          Log.i(LOG_TAG, "clearCommunicationDevice called");
        } else {
          audioManager.setBluetoothScoOn(false);
          audioManager.stopBluetoothSco();
          audioManager.setMode(AudioManager.MODE_NORMAL);
          Log.i(LOG_TAG, "Bluetooth SCO stopped (legacy)");
        }
      } catch (Exception ignored) {}
      bluetoothScoOn = false;
    }
  }

  private void initRecorder() {
    miniBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT);
    if (miniBufferSize == AudioRecord.ERROR || miniBufferSize == AudioRecord.ERROR_BAD_VALUE) {
      Log.e(LOG_TAG, "Audio buffer can't initialize!");
      return;
    }
    record = new AudioRecord(MediaRecorder.AudioSource.MIC,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        miniBufferSize);
    if (record.getState() != AudioRecord.STATE_INITIALIZED) {
      Log.e(LOG_TAG, "Audio Record can't initialize!");
      return;
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
    } else {
      Log.w(LOG_TAG, "AEC not available on this device");
    }

    boolean useNs = prefs.getBoolean("audio_ns", true);
    if (NoiseSuppressor.isAvailable()) {
      ns = NoiseSuppressor.create(sessionId);
      if (ns != null) {
        ns.setEnabled(useNs);
        Log.i(LOG_TAG, "NS " + (useNs ? "enabled" : "disabled"));
      }
    } else {
      Log.w(LOG_TAG, "NS not available on this device");
    }

    boolean useAgc = prefs.getBoolean("audio_agc", true);
    if (AutomaticGainControl.isAvailable()) {
      agc = AutomaticGainControl.create(sessionId);
      if (agc != null) {
        agc.setEnabled(useAgc);
        Log.i(LOG_TAG, "AGC " + (useAgc ? "enabled" : "disabled"));
      }
    } else {
      Log.w(LOG_TAG, "AGC not available on this device");
    }

    Log.i(LOG_TAG, "Record init okay");
  }

  private void releaseAudioEffects() {
    if (aec != null) { aec.release(); aec = null; }
    if (ns != null) { ns.release(); ns = null; }
    if (agc != null) { agc.release(); agc = null; }
  }

  private void startRecordThread() {
    new Thread(() -> {
      record.startRecording();
      Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
      short[] buffer = new short[miniBufferSize / 2];
      byte[] pcmBytes = new byte[miniBufferSize]; // pre-allocate for PCM write
      while (startRecord) {
        int read = record.read(buffer, 0, buffer.length);
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
      record.stop();
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
          if (!needSnapshot[0]) {
            // First skip after speech: push extra silence so WeNet detects endpoint immediately
            Recognize.pushSilenceForEndpoint(8000); // 1 second of silence
          }
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
            final String d = buildLiveDisplay();
            runOnUiThread(() -> updateResultAndScroll(d));
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
      final String finalDisplay = buildLiveDisplay();
      runOnUiThread(() -> updateResultAndScroll(finalDisplay));

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

    // Load full PCM for DAW visualization
    loadFullVisualization(audioPath);
  }

  private void loadFullVisualization(String audioPath) {
    try {
      File f = new File(audioPath);
      if (!f.exists() || f.length() == 0) return;
      byte[] pcmBytes = new byte[(int) f.length()];
      FileInputStream fis = new FileInputStream(f);
      fis.read(pcmBytes);
      fis.close();
      int totalSamples = pcmBytes.length / 2;
      short[] pcmShorts = new short[totalSamples];
      java.nio.ByteBuffer.wrap(pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
          .asShortBuffer().get(pcmShorts);

      int durationMs = (int) (totalSamples * 1000L / SAMPLE_RATE);

      if (useSpectrogram) {
        SpectrogramView sv = findViewById(R.id.spectrogramView);
        sv.setFullSpectrogram(pcmShorts, totalSamples);
        sv.setOnPlaybackSeekListener(ms -> {
          long bytePos = ms * SAMPLE_RATE * 2L / 1000;
          bytePos = Math.max(0, Math.min(bytePos, pcmFileLength));
          playbackPositionBytes = bytePos;
          updatePlaybackUI(ms);
          updateKaraokeHighlight(ms);
          updateVisualizationCursor(ms);
          if (isPlaying) {
            pausePlayback();
            resumePlayback();
          }
        });
      } else {
        // Compute energy bars at native resolution (512 samples/bar)
        int samplesPerBar = 512;
        int barCount = totalSamples / samplesPerBar;
        if (barCount == 0) barCount = 1;
        double[] energies = new double[barCount];
        double maxDb = -999;
        double[] rawDb = new double[barCount];
        for (int i = 0; i < barCount; i++) {
          double sum = 0;
          int start = i * samplesPerBar;
          int end = Math.min(start + samplesPerBar, totalSamples);
          for (int j = start; j < end; j++) {
            sum += (double) pcmShorts[j] * pcmShorts[j];
          }
          double rms = Math.sqrt(sum / (end - start));
          double db = 20.0 * Math.log10(rms + 1e-10);
          rawDb[i] = db;
          if (db > maxDb) maxDb = db;
        }
        // Normalize to 0~1
        double floor = maxDb - 60; // 60dB dynamic range
        for (int i = 0; i < barCount; i++) {
          energies[i] = Math.max(0, Math.min(1.0, (rawDb[i] - floor) / (maxDb - floor)));
        }
        VoiceRectView vv = findViewById(R.id.voiceRectView);
        vv.setFullWaveform(energies, durationMs);
        vv.setOnPlaybackSeekListener(ms -> {
          long bytePos = ms * SAMPLE_RATE * 2L / 1000;
          bytePos = Math.max(0, Math.min(bytePos, pcmFileLength));
          playbackPositionBytes = bytePos;
          updatePlaybackUI(ms);
          updateKaraokeHighlight(ms);
          updateVisualizationCursor(ms);
          if (isPlaying) {
            pausePlayback();
            resumePlayback();
          }
        });
      }
    } catch (Exception e) {
      Log.e(LOG_TAG, "Error loading full visualization: " + e.getMessage());
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
      for (int i = 0; i < arr.length(); i++) {
        JSONObject obj = arr.getJSONObject(i);
        spans.add(new WordSpan(
            obj.getString("w"),
            obj.getInt("s"),
            obj.getInt("e")));
      }
    } catch (Exception e) {
      Log.e(LOG_TAG, "Error loading word spans: " + e.getMessage());
    }
    return spans;
  }

  private void resumePlayback() {
    if (playbackAudioPath == null) return;

    isPlaying = true;
    Button playPauseButton = findViewById(R.id.playPauseButton);
    playPauseButton.setText("Pause");

    resumePlaybackPcm();

    // Start UI updater
    startPlaybackUpdater();
  }



  private void resumePlaybackPcm() {
    int bufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);

    audioTrack = new AudioTrack.Builder()
        .setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build())
        .setAudioFormat(new AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build())
        .setBufferSizeInBytes(bufSize)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build();

    // Route playback to Bluetooth if setting is "bluetooth"
    String playDevice = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        .getString("play_device", "phone");
    if ("bluetooth".equals(playDevice)) {
      android.media.AudioDeviceInfo[] outputDevices =
          audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
      android.media.AudioDeviceInfo btDevice = null;
      for (android.media.AudioDeviceInfo device : outputDevices) {
        if (device.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            || device.getType() == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
          btDevice = device;
        }
      }
      if (btDevice != null) {
        audioTrack.setPreferredDevice(btDevice);
        Log.i(LOG_TAG, "Playback routed to BT: type=" + btDevice.getType()
            + " name=" + btDevice.getProductName());
      }
    } else {
      android.media.AudioDeviceInfo[] outputDevices =
          audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
      for (android.media.AudioDeviceInfo device : outputDevices) {
        if (device.getType() == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
          audioTrack.setPreferredDevice(device);
          break;
        }
      }
    }

    audioTrack.play();

    // Start playback thread — streams from file
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
        while (isPlaying && pos < pcmFileLength) {
          int toRead = (int) Math.min(chunk.length, pcmFileLength - pos);
          int bytesRead = fis.read(chunk, 0, toRead);
          if (bytesRead <= 0) break;
          int written = audioTrack.write(chunk, 0, bytesRead);
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
          int ms;
          ms = bytesToMs(playbackPositionBytes);
          updatePlaybackUI(ms);
          updateKaraokeHighlight(ms);
          updateVisualizationCursor(ms);
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

    // Apply ClickableSpan to each word (set once, never removed)
    for (int i = 0; i < wordSpans.size(); i++) {
      final int wordStartMs = wordSpans.get(i).startMs;
      karaokeSSB.setSpan(new ClickableSpan() {
        @Override
        public void onClick(@NonNull View widget) {
          seekToMs(wordStartMs);
          if (!isPlaying) {
            resumePlayback();
          }
        }

        @Override
        public void updateDrawState(@NonNull android.text.TextPaint ds) {
          ds.setUnderlineText(false);
        }
      }, karaokeSpanStarts[i], karaokeSpanEnds[i], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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

    int currentIndex = -1;
    for (int i = 0; i < wordSpans.size(); i++) {
      WordSpan ws = wordSpans.get(i);
      if (currentMs >= ws.startMs && currentMs < ws.endMs) {
        currentIndex = i;
        break;
      }
    }

    if (currentIndex == -1 && currentMs > 0) {
      for (int i = wordSpans.size() - 1; i >= 0; i--) {
        if (currentMs >= wordSpans.get(i).startMs) {
          currentIndex = i;
          break;
        }
      }
    }

    // Skip if same word is already highlighted
    if (currentIndex == lastHighlightIndex) return;

    // Remove old highlight
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
