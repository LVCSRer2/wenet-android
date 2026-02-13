package com.mobvoi.wenet;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
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
import android.widget.Button;
import android.widget.LinearLayout;
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
  private static final List<String> resource;
  static {
    if ("libtorch".equals(BuildConfig.BACKEND)) {
      resource = Arrays.asList("final.zip", "units.txt");
    } else {
      resource = Arrays.asList("encoder.onnx", "ctc.onnx", "units.txt");
    }
  }

  private boolean startRecord = false;
  private AudioRecord record = null;
  private int miniBufferSize = 0;
  private final BlockingQueue<short[]> bufferQueue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);

  // Recording save
  private String currentRecordingName = null;
  private FileOutputStream pcmOutputStream = null;

  // Playback
  private AudioTrack audioTrack = null;
  private byte[] pcmData = null;
  private int pcmDataLength = 0;
  private boolean isPlaying = false;
  private int playbackPositionBytes = 0;
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
    requestAudioPermissions();
    try {
      assetsInit(this);
    } catch (IOException e) {
      Log.e(LOG_TAG, "Error process asset files to file path");
    }

    TextView textView = findViewById(R.id.textView);
    textView.setText("");
    textView.setMovementMethod(LinkMovementMethod.getInstance());
    Recognize.init(getFilesDir().getPath());

    Button copyButton = findViewById(R.id.copyButton);
    copyButton.setOnClickListener(view -> {
      TextView tv = findViewById(R.id.textView);
      String text = tv.getText().toString();
      if (!text.isEmpty()) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("wenet_result", text));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
      }
    });

    Button button = findViewById(R.id.button);
    button.setText("Start Record");
    button.setOnClickListener(view -> {
      if (!startRecord) {
        stopPlayback();
        startRecord = true;
        currentRecordingName = RecordingManager.createRecordingDir(this);
        try {
          pcmOutputStream = new FileOutputStream(
              RecordingManager.getAudioPath(this, currentRecordingName));
        } catch (IOException e) {
          Log.e(LOG_TAG, "Failed to open PCM output: " + e.getMessage());
          pcmOutputStream = null;
        }
        Recognize.reset();
        startRecordThread();
        startAsrThread();
        Recognize.startDecode();
        Intent serviceIntent = new Intent(this, RecordingForegroundService.class);
        serviceIntent.setAction(RecordingForegroundService.ACTION_START);
        ContextCompat.startForegroundService(this, serviceIntent);
        button.setText("Stop Record");
      } else {
        startRecord = false;
        Recognize.setInputFinished();
        button.setText("Start Record");
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
        if (fromUser && pcmData != null) {
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
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    stopPlayback();
  }

  private void requestAudioPermissions() {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this,
          new String[]{Manifest.permission.RECORD_AUDIO},
          MY_PERMISSIONS_RECORD_AUDIO);
    } else {
      initRecorder();
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
    record = new AudioRecord(MediaRecorder.AudioSource.DEFAULT,
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        miniBufferSize);
    if (record.getState() != AudioRecord.STATE_INITIALIZED) {
      Log.e(LOG_TAG, "Audio Record can't initialize!");
      return;
    }
    Log.i(LOG_TAG, "Record init okay");
  }

  private void startRecordThread() {
    new Thread(() -> {
      VoiceRectView voiceView = findViewById(R.id.voiceRectView);
      record.startRecording();
      Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
      while (startRecord) {
        short[] buffer = new short[miniBufferSize / 2];
        int read = record.read(buffer, 0, buffer.length);
        voiceView.add(calculateDb(buffer));
        if (AudioRecord.ERROR_INVALID_OPERATION != read) {
          // Save PCM to file
          if (pcmOutputStream != null) {
            try {
              byte[] bytes = new byte[read * 2];
              ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, read);
              pcmOutputStream.write(bytes);
            } catch (IOException e) {
              Log.e(LOG_TAG, "Error writing PCM: " + e.getMessage());
            }
          }
          try {
            bufferQueue.put(buffer);
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
      voiceView.zero();
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
    energy = (10 * Math.log10(1 + energy)) / 100;
    energy = Math.min(energy, 1.0);
    return energy;
  }

  private void updateResultAndScroll(String text) {
    TextView textView = findViewById(R.id.textView);
    ScrollView scrollView = findViewById(R.id.scrollView);
    textView.setText(text);
    scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
  }

  private void startAsrThread() {
    new Thread(() -> {
      // Send all data
      while (startRecord || bufferQueue.size() > 0) {
        try {
          short[] data = bufferQueue.take();
          Recognize.acceptWaveform(data);
          String result = Recognize.getResult();
          runOnUiThread(() -> updateResultAndScroll(result));
        } catch (InterruptedException e) {
          Log.e(LOG_TAG, e.getMessage());
        }
      }

      // Wait for final result
      while (true) {
        if (!Recognize.getFinished()) {
          String result = Recognize.getResult();
          runOnUiThread(() -> updateResultAndScroll(result));
        } else {
          // Save result.json with timed result
          saveTimedResult();
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

  // --- Playback ---

  private void enterPlaybackMode(String recordingName) {
    currentPlaybackRecording = recordingName;

    // Load PCM data
    String audioPath = RecordingManager.getAudioPath(this, recordingName);
    File audioFile = new File(audioPath);
    if (!audioFile.exists()) {
      Toast.makeText(this, "Audio file not found", Toast.LENGTH_SHORT).show();
      return;
    }

    try {
      FileInputStream fis = new FileInputStream(audioFile);
      pcmData = new byte[(int) audioFile.length()];
      pcmDataLength = fis.read(pcmData);
      fis.close();
    } catch (IOException e) {
      Log.e(LOG_TAG, "Error reading PCM: " + e.getMessage());
      return;
    }

    // Load word spans
    wordSpans = loadWordSpans(recordingName);

    // Show playback controls
    LinearLayout playbackLayout = findViewById(R.id.playbackLayout);
    playbackLayout.setVisibility(View.VISIBLE);

    // Set up seekbar
    int durationMs = pcmDataLength / (SAMPLE_RATE * 2) * 1000;
    // more accurate: (pcmDataLength * 1000L) / (SAMPLE_RATE * 2)
    durationMs = (int) ((long) pcmDataLength * 1000L / (SAMPLE_RATE * 2));
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
    if (pcmData == null) return;

    isPlaying = true;
    Button playPauseButton = findViewById(R.id.playPauseButton);
    playPauseButton.setText("Pause");

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

    audioTrack.play();

    // Start playback thread
    playbackThread = new Thread(() -> {
      int chunkSize = bufSize;
      int pos = playbackPositionBytes;
      while (isPlaying && pos < pcmDataLength) {
        int remaining = pcmDataLength - pos;
        int toWrite = Math.min(chunkSize, remaining);
        int written = audioTrack.write(pcmData, pos, toWrite);
        if (written > 0) {
          pos += written;
          if (isPlaying) {
            playbackPositionBytes = pos;
          }
        } else {
          break;
        }
      }
      if (isPlaying && playbackPositionBytes >= pcmDataLength) {
        // Playback finished
        runOnUiThread(() -> {
          isPlaying = false;
          playbackPositionBytes = 0;
          Button ppBtn = findViewById(R.id.playPauseButton);
          ppBtn.setText("Play");
          updatePlaybackUI(0);
          updateKaraokeHighlight(0);
        });
      }
    });
    playbackThread.start();

    // Start UI updater
    startPlaybackUpdater();
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
    pcmData = null;
  }

  private void seekToMs(int ms) {
    // Convert ms to byte position (8kHz, 16-bit, mono = 2 bytes per sample)
    int bytePos = (int) ((long) ms * SAMPLE_RATE * 2 / 1000);
    // Align to 2-byte boundary
    bytePos = bytePos & ~1;
    if (bytePos < 0) bytePos = 0;
    if (pcmData != null && bytePos > pcmDataLength) bytePos = pcmDataLength;

    boolean wasPlaying = isPlaying;
    if (wasPlaying) {
      pausePlayback();  // Stops thread and waits for join
    }
    // Set position AFTER thread is fully stopped
    playbackPositionBytes = bytePos;
    updatePlaybackUI(ms);
    updateKaraokeHighlight(ms);
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
          int ms = bytesToMs(playbackPositionBytes);
          updatePlaybackUI(ms);
          updateKaraokeHighlight(ms);
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

  private void updatePlaybackUI(int currentMs) {
    SeekBar seekBar = findViewById(R.id.seekBar);
    TextView timeText = findViewById(R.id.timeTextView);
    int durationMs = pcmData != null ? (int) ((long) pcmDataLength * 1000L / (SAMPLE_RATE * 2)) : 0;
    seekBar.setProgress(currentMs);
    timeText.setText(formatTimeMs(currentMs) + "/" + formatTimeMs(durationMs));
  }

  private int bytesToMs(int bytes) {
    return (int) ((long) bytes * 1000L / (SAMPLE_RATE * 2));
  }

  private String formatTimeMs(int ms) {
    int sec = ms / 1000;
    int min = sec / 60;
    sec = sec % 60;
    return String.format("%d:%02d", min, sec);
  }

  // --- Karaoke ---

  /** Build the SpannableStringBuilder once with ClickableSpans (called once on entering playback). */
  private void buildKaraokeText() {
    if (wordSpans == null || wordSpans.isEmpty()) return;

    karaokeSSB = new SpannableStringBuilder();
    karaokeSpanStarts = new int[wordSpans.size()];
    karaokeSpanEnds = new int[wordSpans.size()];
    lastHighlightIndex = -1;
    currentBgSpan = null;
    currentFgSpan = null;

    for (int i = 0; i < wordSpans.size(); i++) {
      WordSpan ws = wordSpans.get(i);
      karaokeSpanStarts[i] = karaokeSSB.length();
      karaokeSSB.append(ws.word);
      karaokeSpanEnds[i] = karaokeSSB.length();
      if (i < wordSpans.size() - 1) {
        karaokeSSB.append(" ");
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
    List<String> recordings = RecordingManager.listRecordings(this);
    if (recordings.isEmpty()) {
      Toast.makeText(this, "No recordings found", Toast.LENGTH_SHORT).show();
      return;
    }

    String[] items = recordings.toArray(new String[0]);
    new AlertDialog.Builder(this)
        .setTitle("Recordings")
        .setItems(items, (dialog, which) -> {
          stopPlayback();
          enterPlaybackMode(items[which]);
        })
        .setNegativeButton("Cancel", null)
        .show();
  }
}
