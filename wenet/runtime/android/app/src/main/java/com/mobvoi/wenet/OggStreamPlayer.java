package com.mobvoi.wenet;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Streams OGG/Opus audio without full pre-decode.
 * MediaExtractor + MediaCodec → BlockingQueue → AudioTrack (16kHz mono PCM).
 * 48kHz decoder output is decimated 3:1 → 16kHz.
 */
public class OggStreamPlayer {

  private static final String TAG = "OggStreamPlayer";
  private static final int OUTPUT_SAMPLE_RATE = 8000;
  // Sentinel: empty array signals decoder thread finished
  private static final byte[] EOF_SENTINEL = new byte[0];
  // Queue capacity: ~20 chunks × 4096 bytes = ~80KB buffer
  private static final int QUEUE_CAPACITY = 20;

  public interface Listener {
    void onPositionMs(int ms);
    void onPlaybackComplete();
  }

  private final String opusPath;
  private Listener listener;

  private MediaExtractor extractor;
  private MediaCodec decoder;
  private AudioTrack audioTrack;
  private Thread decoderThread;
  private Thread playerThread;

  private volatile boolean active = false;
  private volatile boolean paused = false;
  private volatile long seekTargetUs = -1;
  private volatile boolean needsReinit = false; // true after natural EOF — recreate extractor/decoder on next start()

  // Position tracking
  private volatile long playbackStartMs = 0;
  private volatile long trackStartFrames = 0; // AudioTrack head at playback start
  private int decimation = 6; // default 48000/8000

  private long totalDurationMs = 0;
  private long totalDurationUs = 0;

  private final BlockingQueue<byte[]> pcmQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

  public OggStreamPlayer(String opusPath) {
    this.opusPath = opusPath;
  }

  public void setListener(Listener l) {
    this.listener = l;
  }

  /** Must be called before start(). Returns false if file can't be opened. */
  public boolean prepare() {
    try {
      extractor = new MediaExtractor();
      extractor.setDataSource(opusPath);
      int trackIndex = -1;
      for (int i = 0; i < extractor.getTrackCount(); i++) {
        MediaFormat fmt = extractor.getTrackFormat(i);
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("audio/")) {
          trackIndex = i;
          if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
            totalDurationUs = fmt.getLong(MediaFormat.KEY_DURATION);
            totalDurationMs = totalDurationUs / 1000;
          }
          break;
        }
      }
      if (trackIndex < 0) {
        Log.e(TAG, "No audio track found");
        return false;
      }
      extractor.selectTrack(trackIndex);

      MediaFormat fmt = extractor.getTrackFormat(trackIndex);
      String mime = fmt.getString(MediaFormat.KEY_MIME);
      decoder = MediaCodec.createDecoderByType(mime);
      decoder.configure(fmt, null, null, 0);
      decoder.start();

      // AudioTrack
      int bufSize = AudioTrack.getMinBufferSize(OUTPUT_SAMPLE_RATE,
          AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
      audioTrack = new AudioTrack.Builder()
          .setAudioAttributes(new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
              .build())
          .setAudioFormat(new AudioFormat.Builder()
              .setSampleRate(OUTPUT_SAMPLE_RATE)
              .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
              .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
              .build())
          .setBufferSizeInBytes(bufSize * 4)
          .setTransferMode(AudioTrack.MODE_STREAM)
          .build();

      return true;
    } catch (IOException e) {
      Log.e(TAG, "prepare failed: " + e.getMessage());
      return false;
    }
  }

  public long getTotalDurationMs() { return totalDurationMs; }

  /** Start playback from the given position. */
  public void start(long startMs) {
    if (active) return;
    active = true;
    paused = false;
    playbackStartMs = startMs;

    pcmQueue.clear();

    if (needsReinit) {
      needsReinit = false;
      // Release stale extractor/decoder and recreate
      try { decoder.stop(); decoder.release(); } catch (Exception ignored) {}
      try { extractor.release(); } catch (Exception ignored) {}
      try {
        extractor = new MediaExtractor();
        extractor.setDataSource(opusPath);
        int trackIndex = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
          MediaFormat fmt = extractor.getTrackFormat(i);
          String mime = fmt.getString(MediaFormat.KEY_MIME);
          if (mime != null && mime.startsWith("audio/")) { trackIndex = i; break; }
        }
        if (trackIndex >= 0) {
          extractor.selectTrack(trackIndex);
          MediaFormat fmt = extractor.getTrackFormat(trackIndex);
          String mime = fmt.getString(MediaFormat.KEY_MIME);
          decoder = MediaCodec.createDecoderByType(mime);
          decoder.configure(fmt, null, null, 0);
          decoder.start();
        }
      } catch (IOException e) {
        Log.e(TAG, "reinit failed: " + e.getMessage());
        active = false;
        return;
      }
    }

    extractor.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
    try { decoder.flush(); } catch (Exception ignored) {}

    if (audioTrack != null) {
      try { audioTrack.pause(); audioTrack.flush(); } catch (Exception ignored) {}
    }
    audioTrack.play();
    trackStartFrames = audioTrack.getPlaybackHeadPosition() & 0xFFFFFFFFL;

    decoderThread = new Thread(this::runDecoder, "ogg-decoder");
    playerThread = new Thread(this::runPlayer, "ogg-player");
    decoderThread.start();
    playerThread.start();
  }

  /** Pause playback. Returns current position in ms. */
  public long pause() {
    paused = true;
    long posMs = getCurrentPositionMs();
    if (audioTrack != null) {
      try {
        audioTrack.pause();
        audioTrack.flush();
      } catch (Exception ignored) {}
    }
    pcmQueue.clear();
    return posMs;
  }

  /** Resume after pause (or start if never started) from the given ms position. */
  public void resume(long fromMs) {
    if (!active) {
      // First play — start decoder/player threads
      start(fromMs);
      return;
    }
    pcmQueue.clear();
    playbackStartMs = fromMs;
    // Delegate seek+flush to the decoder thread via seekTargetUs — avoids calling
    // decoder.flush() from the main thread while the decoder thread may be in queueInputBuffer.
    seekTargetUs = fromMs * 1000L;
    if (audioTrack != null) {
      audioTrack.play();
      trackStartFrames = audioTrack.getPlaybackHeadPosition() & 0xFFFFFFFFL;
    }
    paused = false;
  }

  /** Seek to ms. Caller should call resume() or seekTo re-triggers internally. */
  public void seekTo(long ms) {
    seekTargetUs = ms * 1000L;
  }

  public long getCurrentPositionMs() {
    if (audioTrack == null) return playbackStartMs;
    long head = audioTrack.getPlaybackHeadPosition() & 0xFFFFFFFFL;
    long framesPlayed = head - trackStartFrames;
    if (framesPlayed < 0) framesPlayed = 0;
    return playbackStartMs + framesPlayed * 1000L / OUTPUT_SAMPLE_RATE;
  }

  public void release() {
    active = false;
    paused = false;
    pcmQueue.clear();
    pcmQueue.offer(EOF_SENTINEL); // unblock player thread if waiting
    if (decoderThread != null) { decoderThread.interrupt(); }
    if (playerThread != null) { try { playerThread.join(500); } catch (InterruptedException ignored) {} }
    if (audioTrack != null) {
      try { audioTrack.stop(); audioTrack.release(); } catch (Exception ignored) {}
      audioTrack = null;
    }
    if (decoder != null) {
      try { decoder.stop(); decoder.release(); } catch (Exception ignored) {}
      decoder = null;
    }
    if (extractor != null) {
      extractor.release();
      extractor = null;
    }
  }

  // ── Decoder thread ────────────────────────────────────────────────────────

  private void runDecoder() {
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    boolean inputDone = false;
    boolean outputDone = false;
    int outSampleRate = 48000;
    int outChannels = 1;
    boolean formatRead = false;

    while (active && !outputDone) {
      // Handle seek
      long seekUs = seekTargetUs;
      if (seekUs >= 0) {
        seekTargetUs = -1;
        extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        decoder.flush();
        inputDone = false;
        pcmQueue.clear();
      }

      if (paused) {
        try { Thread.sleep(20); } catch (InterruptedException e) { break; }
        continue;
      }

      // Feed input
      if (!inputDone) {
        int inputIdx = decoder.dequeueInputBuffer(5000);
        if (inputIdx >= 0) {
          ByteBuffer inputBuf = decoder.getInputBuffer(inputIdx);
          if (inputBuf != null) {
            int sampleSize = extractor.readSampleData(inputBuf, 0);
            if (sampleSize < 0) {
              decoder.queueInputBuffer(inputIdx, 0, 0, 0,
                  MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputDone = true;
            } else {
              long pts = extractor.getSampleTime();
              decoder.queueInputBuffer(inputIdx, 0, sampleSize, pts, 0);
              extractor.advance();
            }
          }
        }
      }

      // Drain output
      int outputIdx = decoder.dequeueOutputBuffer(info, 5000);
      if (outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        MediaFormat newFmt = decoder.getOutputFormat();
        outSampleRate = newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
            ? newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
        outChannels = newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
            ? newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
        decimation = Math.max(1, outSampleRate / OUTPUT_SAMPLE_RATE);
        formatRead = true;
        Log.d(TAG, "Output format: " + outSampleRate + "Hz ch=" + outChannels
            + " decimation=" + decimation);
      } else if (outputIdx >= 0) {
        ByteBuffer outBuf = decoder.getOutputBuffer(outputIdx);
        if (outBuf != null && info.size > 0 && formatRead) {
          byte[] pcmRaw = new byte[info.size];
          outBuf.position(info.offset);
          outBuf.get(pcmRaw, 0, info.size);

          // Decimate: outSampleRate → OUTPUT_SAMPLE_RATE
          int frameBytes = 2 * outChannels;
          int totalFrames = info.size / frameBytes;
          int outFrames = (totalFrames + decimation - 1) / decimation;
          byte[] decimated = new byte[outFrames * 2]; // mono 16-bit output
          int dstIdx = 0;
          for (int f = 0; f < totalFrames && dstIdx + 1 < decimated.length; f += decimation) {
            int srcByte = f * frameBytes;
            // Left channel (or mono) sample
            decimated[dstIdx]     = pcmRaw[srcByte];
            decimated[dstIdx + 1] = pcmRaw[srcByte + 1];
            dstIdx += 2;
          }
          byte[] out = new byte[dstIdx];
          System.arraycopy(decimated, 0, out, 0, dstIdx);

          try { pcmQueue.put(out); } catch (InterruptedException e) { break; }
        }
        decoder.releaseOutputBuffer(outputIdx, false);
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
          outputDone = true;
        }
      }
    }

    try { pcmQueue.put(EOF_SENTINEL); } catch (InterruptedException ignored) {}
  }

  // ── Player thread ─────────────────────────────────────────────────────────

  private void runPlayer() {
    while (active) {
      byte[] chunk;
      try {
        chunk = pcmQueue.take();
      } catch (InterruptedException e) {
        break;
      }

      if (chunk == EOF_SENTINEL) {
        // Playback complete — reset active so resume() can restart via start()
        needsReinit = true;
        active = false;
        runOnMainThread(() -> {
          if (listener != null) listener.onPlaybackComplete();
        });
        break;
      }

      AudioTrack at = audioTrack;
      if (at == null) break;
      int written = 0;
      while (written < chunk.length && active && !paused) {
        int w = at.write(chunk, written, chunk.length - written);
        if (w <= 0) break;
        written += w;
      }

      if (!paused && listener != null) {
        int ms = (int) getCurrentPositionMs();
        runOnMainThread(() -> { if (listener != null) listener.onPositionMs(ms); });
      }
    }
  }

  private void runOnMainThread(Runnable r) {
    android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    h.post(r);
  }
}
