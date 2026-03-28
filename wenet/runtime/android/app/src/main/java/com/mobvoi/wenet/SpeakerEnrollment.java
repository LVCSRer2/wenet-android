package com.mobvoi.wenet;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * One-time speaker enrollment: decodes an audio recording to 16 kHz PCM,
 * computes power mel-spectrogram partials, runs resemblyzer_encoder.onnx
 * on each partial, averages and L2-normalises the embeddings, then saves
 * the 256-float result to {filesDir}/speaker_embedding.bin.
 */
public class SpeakerEnrollment {

    private static final String TAG = "SpeakerEnrollment";

    private static final int SR = PersonalVadProcessor.SR;          // 16000
    private static final int N_FFT = 400;
    private static final int HOP_LENGTH = 160;
    private static final int N_MELS = PersonalVadProcessor.N_MELS;  // 40
    private static final int PARTIALS_N_FRAMES = 160;
    private static final int PARTIAL_STEP = 80;
    private static final int EMBED_DIM = PersonalVadProcessor.EMBED_DIM; // 256

    public interface Callback {
        void onSuccess();
        void onError(String message);
    }

    /** Enroll directly from raw 16 kHz mono PCM (no file decoding step). */
    public static void enrollFromPcm(Context context, short[] pcm, Callback callback) {
        new Thread(() -> {
            try {
                if (pcm == null || pcm.length < N_FFT) {
                    postError(callback, "녹음이 너무 짧습니다");
                    return;
                }

                OrtEnvironment ortEnv = OrtEnvironment.getEnvironment();
                PersonalVadProcessor proc = new PersonalVadProcessor(context, ortEnv);

                int nFrames = (pcm.length - N_FFT) / HOP_LENGTH + 1;
                if (nFrames < PARTIALS_N_FRAMES) {
                    proc.release();
                    postError(callback, "등록용 녹음이 너무 짧습니다 (최소 " +
                        (PARTIALS_N_FRAMES * HOP_LENGTH / SR) + "초 필요)");
                    return;
                }

                float[][] melFrames = new float[nFrames][];
                for (int i = 0; i < nFrames; i++) {
                    melFrames[i] = PersonalVadProcessor.computePowerMelFrame(
                        pcm, i * HOP_LENGTH, proc.cosTable, proc.sinTable, proc.melFilterbank);
                }
                proc.release();

                InputStream is = context.getAssets().open("resemblyzer_encoder.onnx");
                byte[] modelBytes = PersonalVadProcessor.readStream(is);
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                opts.setIntraOpNumThreads(1);
                OrtSession encoderSession = ortEnv.createSession(modelBytes, opts);

                List<float[]> embeddings = new ArrayList<>();
                for (int start = 0; start + PARTIALS_N_FRAMES <= nFrames; start += PARTIAL_STEP) {
                    float[] partialFlat = new float[PARTIALS_N_FRAMES * N_MELS];
                    for (int f = 0; f < PARTIALS_N_FRAMES; f++) {
                        System.arraycopy(melFrames[start + f], 0, partialFlat, f * N_MELS, N_MELS);
                    }
                    OnnxTensor melsTensor = OnnxTensor.createTensor(ortEnv,
                        java.nio.FloatBuffer.wrap(partialFlat),
                        new long[]{1, PARTIALS_N_FRAMES, N_MELS});
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("mels", melsTensor);
                    OrtSession.Result result = encoderSession.run(inputs);
                    Object embValue = result.get("embedding").get().getValue();
                    float[] emb = extractEmbedding(embValue);
                    if (emb != null) embeddings.add(emb);
                    result.close();
                    melsTensor.close();
                }
                encoderSession.close();

                if (embeddings.isEmpty()) {
                    postError(callback, "임베딩 추출 실패");
                    return;
                }

                float[] avgEmb = new float[EMBED_DIM];
                for (float[] emb : embeddings) {
                    for (int i = 0; i < EMBED_DIM; i++) avgEmb[i] += emb[i];
                }
                for (int i = 0; i < EMBED_DIM; i++) avgEmb[i] /= embeddings.size();

                float norm = 0f;
                for (float v : avgEmb) norm += v * v;
                norm = (float) Math.sqrt(norm);
                if (norm > 1e-8f) {
                    for (int i = 0; i < EMBED_DIM; i++) avgEmb[i] /= norm;
                }

                ByteBuffer bb = ByteBuffer.allocate(EMBED_DIM * 4).order(ByteOrder.LITTLE_ENDIAN);
                for (float v : avgEmb) bb.putFloat(v);
                File outFile = new File(context.getFilesDir(), "speaker_embedding.bin");
                FileOutputStream fos = new FileOutputStream(outFile);
                fos.write(bb.array());
                fos.close();

                Log.i(TAG, "Enrollment complete (from PCM): " + embeddings.size() + " partials");
                new Handler(Looper.getMainLooper()).post(callback::onSuccess);

            } catch (Exception e) {
                Log.e(TAG, "Enrollment failed: " + e.getMessage());
                postError(callback, e.getMessage());
            }
        }, "speaker-enrollment").start();
    }

    public static void enroll(Context context, String recordingName, Callback callback) {
        new Thread(() -> {
            try {
                String audioPath = RecordingManager.findAudioPath(context, recordingName);
                if (audioPath == null) {
                    postError(callback, "녹음 오디오 파일을 찾을 수 없습니다: " + recordingName);
                    return;
                }

                Log.i(TAG, "Enrollment started: " + audioPath);

                // 1. Decode to 16 kHz PCM
                short[] pcm = decodeAudioTo16kHz(audioPath);
                if (pcm == null || pcm.length < N_FFT) {
                    postError(callback, "오디오 디코딩 실패 또는 녹음이 너무 짧습니다");
                    return;
                }

                // 2. Build feature extractor (reuse precomputed DFT tables)
                OrtEnvironment ortEnv = OrtEnvironment.getEnvironment();
                PersonalVadProcessor proc = new PersonalVadProcessor(context, ortEnv);

                // 3. Compute power mel-spectrogram for all frames
                int nFrames = (pcm.length - N_FFT) / HOP_LENGTH + 1;
                if (nFrames < PARTIALS_N_FRAMES) {
                    proc.release();
                    postError(callback, "등록용 녹음이 너무 짧습니다 (최소 " +
                        (PARTIALS_N_FRAMES * HOP_LENGTH / SR) + "초 필요)");
                    return;
                }

                float[][] melFrames = new float[nFrames][];
                for (int i = 0; i < nFrames; i++) {
                    melFrames[i] = PersonalVadProcessor.computePowerMelFrame(
                        pcm, i * HOP_LENGTH, proc.cosTable, proc.sinTable, proc.melFilterbank);
                }
                proc.release();

                // 4. Load Resemblyzer ONNX
                InputStream is = context.getAssets().open("resemblyzer_encoder.onnx");
                byte[] modelBytes = PersonalVadProcessor.readStream(is);
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                opts.setIntraOpNumThreads(1);
                OrtSession encoderSession = ortEnv.createSession(modelBytes, opts);

                // 5. Extract embeddings for each 160-frame partial (step 80)
                List<float[]> embeddings = new ArrayList<>();
                for (int start = 0; start + PARTIALS_N_FRAMES <= nFrames; start += PARTIAL_STEP) {
                    float[] partialFlat = new float[PARTIALS_N_FRAMES * N_MELS];
                    for (int f = 0; f < PARTIALS_N_FRAMES; f++) {
                        System.arraycopy(melFrames[start + f], 0, partialFlat, f * N_MELS, N_MELS);
                    }

                    OnnxTensor melsTensor = OnnxTensor.createTensor(ortEnv,
                        java.nio.FloatBuffer.wrap(partialFlat),
                        new long[]{1, PARTIALS_N_FRAMES, N_MELS});

                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("mels", melsTensor);

                    OrtSession.Result result = encoderSession.run(inputs);
                    Object embValue = result.get("embedding").get().getValue();
                    float[] emb = extractEmbedding(embValue);
                    if (emb != null) embeddings.add(emb);

                    result.close();
                    melsTensor.close();
                }

                encoderSession.close();

                if (embeddings.isEmpty()) {
                    postError(callback, "임베딩 추출 실패");
                    return;
                }

                // 6. Average and L2-normalise
                float[] avgEmb = new float[EMBED_DIM];
                for (float[] emb : embeddings) {
                    for (int i = 0; i < EMBED_DIM; i++) avgEmb[i] += emb[i];
                }
                for (int i = 0; i < EMBED_DIM; i++) avgEmb[i] /= embeddings.size();

                float norm = 0f;
                for (float v : avgEmb) norm += v * v;
                norm = (float) Math.sqrt(norm);
                if (norm > 1e-8f) {
                    for (int i = 0; i < EMBED_DIM; i++) avgEmb[i] /= norm;
                }

                // 7. Save to speaker_embedding.bin
                ByteBuffer bb = ByteBuffer.allocate(EMBED_DIM * 4).order(ByteOrder.LITTLE_ENDIAN);
                for (float v : avgEmb) bb.putFloat(v);
                File outFile = new File(context.getFilesDir(), "speaker_embedding.bin");
                FileOutputStream fos = new FileOutputStream(outFile);
                fos.write(bb.array());
                fos.close();

                Log.i(TAG, "Enrollment complete: " + embeddings.size() + " partials → "
                    + outFile.getAbsolutePath());
                new Handler(Looper.getMainLooper()).post(callback::onSuccess);

            } catch (Exception e) {
                Log.e(TAG, "Enrollment failed: " + e.getMessage());
                postError(callback, e.getMessage());
            }
        }, "speaker-enrollment").start();
    }

    private static float[] extractEmbedding(Object value) {
        if (value instanceof float[]) return (float[]) value;
        if (value instanceof float[][]) return ((float[][]) value)[0];
        Log.w(TAG, "Unexpected embedding type: " + (value != null ? value.getClass() : "null"));
        return null;
    }

    /** Decode the given audio file to 16 kHz mono PCM (short[]). */
    static short[] decodeAudioTo16kHz(String audioPath) {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        List<short[]> chunks = new ArrayList<>();
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(audioPath);

            int trackIdx = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { trackIdx = i; break; }
            }
            if (trackIdx < 0) return null;

            extractor.selectTrack(trackIdx);
            MediaFormat fmt = extractor.getTrackFormat(trackIdx);
            decoder = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            decoder.configure(fmt, null, null, 0);
            decoder.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            int outSampleRate = 48000, outChannels = 1;
            boolean formatRead = false;

            while (!outputDone) {
                if (!inputDone) {
                    int inIdx = decoder.dequeueInputBuffer(5000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                        int sz = extractor.readSampleData(inBuf, 0);
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outIdx = decoder.dequeueOutputBuffer(info, 5000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = decoder.getOutputFormat();
                    outSampleRate = nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                        ? nf.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
                    outChannels = nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                        ? nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
                    formatRead = true;
                } else if (outIdx >= 0) {
                    ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0 && formatRead) {
                        int decimation = Math.max(1, outSampleRate / SR); // 48000/16000 = 3
                        int frameBytes = 2 * outChannels;
                        int totalFrames = info.size / frameBytes;
                        int outFrames = (totalFrames + decimation - 1) / decimation;
                        short[] chunk = new short[outFrames];
                        byte[] raw = new byte[info.size];
                        outBuf.position(info.offset);
                        outBuf.get(raw);
                        int dst = 0;
                        for (int f = 0; f < totalFrames && dst < outFrames; f += decimation) {
                            int b = f * frameBytes;
                            chunk[dst++] = (short) ((raw[b] & 0xFF) | (raw[b + 1] << 8));
                        }
                        chunks.add(chunk);
                    }
                    decoder.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }

            // Concatenate all chunks
            int total = 0;
            for (short[] ch : chunks) total += ch.length;
            short[] result = new short[total];
            int pos = 0;
            for (short[] ch : chunks) { System.arraycopy(ch, 0, result, pos, ch.length); pos += ch.length; }
            return result;

        } catch (Exception e) {
            Log.e(TAG, "Audio decode failed: " + e.getMessage());
            return null;
        } finally {
            if (decoder != null) { try { decoder.stop(); decoder.release(); } catch (Exception ignored) {} }
            if (extractor != null) extractor.release();
        }
    }

    private static void postError(Callback callback, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> callback.onError(msg));
    }
}
