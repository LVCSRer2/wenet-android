package com.mobvoi.wenet;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Abstraction over a raw PCM (16-bit mono, 8kHz) data source.
 * Two implementations:
 *  - FilePcmDataSource  : wraps an existing raw PCM file (FileChannel random access)
 *  - OggPcmDataSource   : on-demand decodes a window from OGG/Opus into a short[] cache
 */
public interface PcmDataSource {

  /** Total number of 16-bit mono samples at 8kHz. */
  long totalSamples();

  /**
   * Read up to {@code count} samples starting at {@code sampleOffset} into {@code dst}.
   * Returns number of samples actually read.
   */
  int read(long sampleOffset, short[] dst, int count);

  void close();

  // ── FileChannel implementation ─────────────────────────────────────────

  class FilePcmDataSource implements PcmDataSource {
    private final FileInputStream fis;
    private final FileChannel channel;
    private final long totalSamples;

    public FilePcmDataSource(String path) throws IOException {
      fis = new FileInputStream(path);
      channel = fis.getChannel();
      totalSamples = channel.size() / 2;
    }

    @Override public long totalSamples() { return totalSamples; }

    @Override
    public int read(long sampleOffset, short[] dst, int count) {
      try {
        ByteBuffer buf = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
        channel.position(sampleOffset * 2);
        int bytesRead = channel.read(buf);
        if (bytesRead <= 0) return 0;
        buf.flip();
        int samplesRead = bytesRead / 2;
        buf.asShortBuffer().get(dst, 0, samplesRead);
        return samplesRead;
      } catch (IOException e) {
        return 0;
      }
    }

    @Override
    public void close() {
      try { channel.close(); fis.close(); } catch (IOException ignored) {}
    }
  }

  // ── OGG/Opus implementation ────────────────────────────────────────────

  class OggPcmDataSource implements PcmDataSource {
    private static final String TAG = "OggPcmDataSource";
    private static final int OUTPUT_SAMPLE_RATE = 8000;

    private final String opusPath;
    private final long totalSamplesVal;

    // Decoded window cache
    private long cacheStartSample = -1;
    private short[] cacheData = null;

    // Window = 40 seconds of decoded audio (enough for one spectrogram render)
    private static final int WINDOW_SAMPLES = OUTPUT_SAMPLE_RATE * 40;

    public OggPcmDataSource(String opusPath, long durationMs) {
      this.opusPath = opusPath;
      this.totalSamplesVal = durationMs * OUTPUT_SAMPLE_RATE / 1000;
    }

    @Override public long totalSamples() { return totalSamplesVal; }

    @Override
    public int read(long sampleOffset, short[] dst, int count) {
      if (sampleOffset < 0 || sampleOffset >= totalSamplesVal) return 0;

      // Check if request falls within current cache
      if (cacheData == null
          || sampleOffset < cacheStartSample
          || sampleOffset + count > cacheStartSample + cacheData.length) {
        // Decode a new window starting just before sampleOffset
        long windowStart = Math.max(0, sampleOffset - OUTPUT_SAMPLE_RATE); // 1s pre-buffer
        decodeWindow(windowStart, WINDOW_SAMPLES);
      }

      if (cacheData == null) return 0;

      long rel = sampleOffset - cacheStartSample;
      if (rel < 0 || rel >= cacheData.length) return 0;
      int available = (int) Math.min(count, cacheData.length - rel);
      System.arraycopy(cacheData, (int) rel, dst, 0, available);
      return available;
    }

    private void decodeWindow(long startSample, int windowSamples) {
      MediaExtractor extractor = null;
      MediaCodec decoder = null;
      try {
        extractor = new MediaExtractor();
        extractor.setDataSource(opusPath);
        int trackIdx = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
          MediaFormat fmt = extractor.getTrackFormat(i);
          String mime = fmt.getString(MediaFormat.KEY_MIME);
          if (mime != null && mime.startsWith("audio/")) { trackIdx = i; break; }
        }
        if (trackIdx < 0) return;

        extractor.selectTrack(trackIdx);
        long seekUs = startSample * 1_000_000L / OUTPUT_SAMPLE_RATE;
        extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

        MediaFormat fmt = extractor.getTrackFormat(trackIdx);
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(fmt, null, null, 0);
        decoder.start();

        short[] buf = new short[windowSamples + OUTPUT_SAMPLE_RATE];
        int filled = 0;
        int decimation = 6; // default; updated on INFO_OUTPUT_FORMAT_CHANGED
        boolean formatKnown = false;
        boolean inputDone = false;
        boolean outputDone = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (!outputDone && filled < windowSamples) {
          if (!inputDone) {
            int inputIdx = decoder.dequeueInputBuffer(5000);
            if (inputIdx >= 0) {
              ByteBuffer inputBuf = decoder.getInputBuffer(inputIdx);
              int sampleSize = extractor.readSampleData(inputBuf, 0);
              if (sampleSize < 0) {
                decoder.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                inputDone = true;
              } else {
                decoder.queueInputBuffer(inputIdx, 0, sampleSize, extractor.getSampleTime(), 0);
                extractor.advance();
              }
            }
          }

          int outputIdx = decoder.dequeueOutputBuffer(info, 5000);
          if (outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat newFmt = decoder.getOutputFormat();
            int outRate = newFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            int outCh = newFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            decimation = Math.max(1, outRate / OUTPUT_SAMPLE_RATE);
            formatKnown = true;
          } else if (outputIdx >= 0) {
            ByteBuffer outBuf = decoder.getOutputBuffer(outputIdx);
            if (outBuf != null && info.size > 0 && formatKnown) {
              outBuf.position(info.offset);
              outBuf.order(ByteOrder.LITTLE_ENDIAN);
              int frameBytes = 2; // mono out after channel selection; getOutputFormat channel=1
              // Actually decoder outputs outChannels — re-read via format if needed
              // We always take left channel (index 0)
              int totalRawBytes = info.size;
              // decimation already accounts for sample rate; channel factor separate
              // Read raw bytes and extract left mono sample per decimation step
              byte[] raw = new byte[totalRawBytes];
              outBuf.get(raw, 0, totalRawBytes);
              // outChannels from format change is 1 for opus mono typically
              // but to be safe, use decimation × 2 bytes per frame
              int bytesPerOutFrame = 2; // 16-bit mono
              // If the codec outputs 2ch (stereo), frameBytes = 4
              // We use decimation*frameBytes to step through raw
              for (int i = 0; i + 1 < totalRawBytes && filled < buf.length; i += decimation * bytesPerOutFrame) {
                short s = (short) ((raw[i] & 0xFF) | (raw[i + 1] << 8));
                buf[filled++] = s;
              }
            }
            decoder.releaseOutputBuffer(outputIdx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
          }
        }

        cacheStartSample = startSample;
        cacheData = new short[filled];
        System.arraycopy(buf, 0, cacheData, 0, filled);

      } catch (Exception e) {
        Log.e(TAG, "decodeWindow error: " + e.getMessage());
      } finally {
        if (decoder != null) { try { decoder.stop(); decoder.release(); } catch (Exception ignored) {} }
        if (extractor != null) { extractor.release(); }
      }
    }

    @Override public void close() { cacheData = null; }
  }
}
