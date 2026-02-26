package com.mobvoi.wenet;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Converts raw PCM (16-bit LE, mono) to M4A (AAC-LC) using MediaCodec + MediaMuxer.
 */
public class AudioConverter {

    private static final String TAG = "AudioConverter";
    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int BIT_RATE = 64000;
    private static final long TIMEOUT_US = 10000;

    /**
     * Convert a raw PCM file to M4A (AAC).
     *
     * @param pcmPath    Input PCM file path (16-bit LE, mono)
     * @param m4aPath    Output M4A file path
     * @param sampleRate Sample rate (e.g. 8000)
     * @return true on success
     */
    public static boolean convertPcmToM4a(String pcmPath, String m4aPath, int sampleRate) {
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        FileInputStream fis = null;
        int trackIndex = -1;
        boolean muxerStarted = false;

        try {
            // Configure AAC encoder
            MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            muxer = new MediaMuxer(m4aPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            fis = new FileInputStream(pcmPath);
            byte[] readBuf = new byte[8192];
            boolean inputDone = false;
            boolean outputDone = false;
            long presentationTimeUs = 0;
            // bytes per second = sampleRate * 2 (16-bit mono)
            double usPerByte = 1_000_000.0 / (sampleRate * 2);

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (!outputDone) {
                // Feed input
                if (!inputDone) {
                    int inputBufIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuf = encoder.getInputBuffer(inputBufIndex);
                        inputBuf.clear();
                        int capacity = Math.min(inputBuf.remaining(), readBuf.length);
                        int bytesRead = fis.read(readBuf, 0, capacity);
                        if (bytesRead <= 0) {
                            encoder.queueInputBuffer(inputBufIndex, 0, 0,
                                    presentationTimeUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            inputBuf.put(readBuf, 0, bytesRead);
                            encoder.queueInputBuffer(inputBufIndex, 0, bytesRead,
                                    presentationTimeUs, 0);
                            presentationTimeUs += (long) (bytesRead * usPerByte);
                        }
                    }
                }

                // Drain output
                int outputBufIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = encoder.getOutputFormat();
                    trackIndex = muxer.addTrack(outputFormat);
                    muxer.start();
                    muxerStarted = true;
                    Log.i(TAG, "Muxer started, format: " + outputFormat);
                } else if (outputBufIndex >= 0) {
                    ByteBuffer outputBuf = encoder.getOutputBuffer(outputBufIndex);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0;
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        outputBuf.position(bufferInfo.offset);
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size);
                        muxer.writeSampleData(trackIndex, outputBuf, bufferInfo);
                    }
                    encoder.releaseOutputBuffer(outputBufIndex, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }

            Log.i(TAG, "PCM → M4A conversion complete: " + m4aPath);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Conversion failed: " + e.getMessage(), e);
            return false;
        } finally {
            try { if (fis != null) fis.close(); } catch (IOException ignored) {}
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) {}
                encoder.release();
            }
            if (muxer != null && muxerStarted) {
                try { muxer.stop(); } catch (Exception ignored) {}
                muxer.release();
            }
        }
    }
}
