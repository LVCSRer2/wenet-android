package com.mobvoi.wenet;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Real-time PCM → compressed audio encoder.
 * Accepts 8 kHz mono PCM chunks, encodes via MediaCodec, writes to MediaMuxer.
 * Supported codecs: opus (OGG), aac / aac_hw (M4A), amrnb (3GP).
 */
public class RealtimeEncoder {

  private static final String TAG = "RealtimeEncoder";
  private static final int SAMPLE_RATE = 8000;
  private static final int FRAME_SAMPLES = 160; // 20 ms at 8 kHz

  private MediaCodec codec = null;
  private MediaMuxer muxer = null;
  private int muxerTrack = -1;
  private boolean muxerStarted = false;
  private long presentationUs = 0;
  private short[] accumBuf = null;
  private int accumFilled = 0;

  /** Initialize and start the encoder. Throws IOException on failure. */
  public void start(String outputPath, String codecType) throws IOException {
    String mime;
    int bitrate;
    int muxerFormat;
    if ("aac".equals(codecType) || "aac_hw".equals(codecType)) {
      mime = "audio/mp4a-latm";
      bitrate = 16000;
      muxerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
    } else if ("amrnb".equals(codecType)) {
      mime = "audio/3gpp";
      bitrate = 12200;
      muxerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP;
    } else {
      mime = "audio/opus";
      bitrate = 6000;
      muxerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG;
    }

    MediaFormat fmt = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, 1);
    fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
    if ("aac".equals(codecType) || "aac_hw".equals(codecType)) {
      fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
          MediaCodecInfo.CodecProfileLevel.AACObjectLC);
    }

    if ("aac_hw".equals(codecType)) {
      try {
        codec = MediaCodec.createByCodecName("c2.sec.aac.encoder");
      } catch (Exception e) {
        Log.w(TAG, "HW AAC encoder not found, falling back to SW");
        codec = MediaCodec.createEncoderByType(mime);
      }
    } else {
      codec = MediaCodec.createEncoderByType(mime);
    }

    codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
    codec.start();
    muxer = new MediaMuxer(outputPath, muxerFormat);
    muxerTrack = -1;
    muxerStarted = false;
    presentationUs = 0;
    accumBuf = new short[FRAME_SAMPLES];
    accumFilled = 0;
    Log.i(TAG, "Started: " + outputPath + " codec=" + codecType);
  }

  /** Feed PCM samples. Call from a single recording thread. */
  public void feed(short[] samples, int count) {
    if (codec == null) return;
    int srcPos = 0;
    while (srcPos < count) {
      int toCopy = Math.min(count - srcPos, FRAME_SAMPLES - accumFilled);
      System.arraycopy(samples, srcPos, accumBuf, accumFilled, toCopy);
      srcPos += toCopy;
      accumFilled += toCopy;
      if (accumFilled == FRAME_SAMPLES) {
        submitFrame(accumBuf, FRAME_SAMPLES, false);
        accumFilled = 0;
      }
    }
    drain(0); // non-blocking
  }

  /** Flush remaining samples, finalize file, release resources. */
  public void release() {
    if (codec == null) return;
    try {
      if (accumFilled > 0) {
        Arrays.fill(accumBuf, accumFilled, FRAME_SAMPLES, (short) 0);
        submitFrame(accumBuf, FRAME_SAMPLES, true);
      } else {
        int inIdx = codec.dequeueInputBuffer(5000);
        if (inIdx >= 0) {
          codec.queueInputBuffer(inIdx, 0, 0, presentationUs,
              MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }
      }
      drain(5000);
      Log.i(TAG, "Finalized");
    } catch (Exception e) {
      Log.e(TAG, "Error finalizing: " + e.getMessage());
    } finally {
      try { codec.stop(); codec.release(); } catch (Exception ignored) {}
      try { muxer.stop(); muxer.release(); } catch (Exception ignored) {}
      codec = null;
      muxer = null;
    }
  }

  private void submitFrame(short[] samples, int count, boolean eos) {
    try {
      int inIdx = codec.dequeueInputBuffer(5000);
      if (inIdx >= 0) {
        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
        inBuf.clear();
        byte[] bytes = new byte[count * 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer().put(samples, 0, count);
        inBuf.put(bytes, 0, count * 2);
        int flags = eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;
        codec.queueInputBuffer(inIdx, 0, count * 2, presentationUs, flags);
        presentationUs += count * 1_000_000L / SAMPLE_RATE;
      }
    } catch (Exception e) {
      Log.e(TAG, "Input error: " + e.getMessage());
    }
  }

  private void drain(long timeoutUs) {
    if (codec == null || muxer == null) return;
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    while (true) {
      int outIdx = codec.dequeueOutputBuffer(info, timeoutUs);
      if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        muxerTrack = muxer.addTrack(codec.getOutputFormat());
        muxer.start();
        muxerStarted = true;
      } else if (outIdx >= 0) {
        if (muxerStarted && info.size > 0) {
          ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
          outBuf.position(info.offset);
          outBuf.limit(info.offset + info.size);
          muxer.writeSampleData(muxerTrack, outBuf, info);
        }
        codec.releaseOutputBuffer(outIdx, false);
        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
      } else {
        break;
      }
    }
  }
}
