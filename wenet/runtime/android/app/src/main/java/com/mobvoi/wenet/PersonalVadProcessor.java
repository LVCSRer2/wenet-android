package com.mobvoi.wenet;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Real-time Personal Voice Activity Detection.
 * Processes 16 kHz mono PCM and detects segments where the enrolled speaker is talking.
 * Uses personal_vad.onnx (stateful 2-layer LSTM, 3-class: silence / non-target / target).
 *
 * Call process() for each incoming PCM chunk (16 kHz).
 * Call flush() when recording ends, then getSegments() for the result.
 */
public class PersonalVadProcessor {

    private static final String TAG = "PersonalVadProcessor";

    // Audio / feature parameters (must match training)
    static final int SR = 16000;
    private static final int N_FFT = 400;
    private static final int HOP_LENGTH = 160;
    static final int N_MELS = 40;
    static final int N_BINS = N_FFT / 2 + 1; // 201

    // Inference chunk: 25 frames × 10 ms = 250 ms
    private static final int VAD_CHUNK_FRAMES = 25;
    private static final int OVERLAP = N_FFT - HOP_LENGTH; // 240 samples tail
    private static final int NEW_SAMPLES_PER_CHUNK = VAD_CHUNK_FRAMES * HOP_LENGTH; // 4000

    // Model dimensions
    static final int EMBED_DIM = 256;
    private static final int LSTM_LAYERS = 2;
    private static final int LSTM_HIDDEN = 64;

    // Hysteresis
    private static final float THRESHOLD_ON = 0.5f;
    private static final float THRESHOLD_OFF = 0.3f;
    private static final int HANGOVER_FRAMES = 4;

    // Precomputed DFT basis (Hann-windowed cosine/sine) [N_BINS × N_FFT]
    // cosTable[k * N_FFT + n] = hann[n] * cos(2π k n / N_FFT)
    final float[] cosTable;
    final float[] sinTable;

    // Mel filterbank [N_MELS × N_BINS] loaded from assets/mel_filterbank.bin
    final float[] melFilterbank;

    // Speaker embedding [EMBED_DIM]
    private float[] speakerEmbedding = null;

    // ONNX
    private final OrtEnvironment ortEnv;
    private OrtSession vadSession = null;
    private boolean initialized = false;

    // Stateful LSTM state — flattened [LSTM_LAYERS × LSTM_HIDDEN]
    private final float[] h = new float[LSTM_LAYERS * LSTM_HIDDEN];
    private final float[] c = new float[LSTM_LAYERS * LSTM_HIDDEN];

    // Audio accumulation buffer
    private final short[] overlapBuf = new short[OVERLAP];       // tail of previous chunk
    private final short[] accumBuf = new short[NEW_SAMPLES_PER_CHUNK];
    private int accumLen = 0;

    // Timing (in 16 kHz samples)
    private long totalSamplesProcessed = 0;

    // Hysteresis state
    private boolean inMyVoice = false;
    private int hangoverCount = 0;
    private long segmentStartMs = -1;

    // Results
    private final List<long[]> segments = new ArrayList<>(); // each entry: [startMs, endMs]

    public PersonalVadProcessor(Context context, OrtEnvironment ortEnv) {
        this.ortEnv = ortEnv;

        // Build DFT tables with Hann window folded in
        cosTable = new float[N_BINS * N_FFT];
        sinTable = new float[N_BINS * N_FFT];
        double twoPiOverN = 2.0 * Math.PI / N_FFT;
        for (int n = 0; n < N_FFT; n++) {
            double hann = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * n / (N_FFT - 1)));
            for (int k = 0; k < N_BINS; k++) {
                double angle = twoPiOverN * k * n;
                cosTable[k * N_FFT + n] = (float) (hann * Math.cos(angle));
                sinTable[k * N_FFT + n] = (float) (hann * Math.sin(angle));
            }
        }

        melFilterbank = loadMelFilterbank(context);
        initialized = loadVadModel(context);
    }

    private float[] loadMelFilterbank(Context context) {
        try {
            InputStream is = context.getAssets().open("mel_filterbank.bin");
            byte[] data = readStream(is);
            FloatBuffer fb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            float[] fb2 = new float[fb.remaining()];
            fb.get(fb2);
            Log.i(TAG, "Mel filterbank loaded: " + fb2.length + " floats");
            return fb2;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load mel filterbank: " + e.getMessage());
            return new float[N_MELS * N_BINS];
        }
    }

    private boolean loadVadModel(Context context) {
        try {
            InputStream is = context.getAssets().open("personal_vad.onnx");
            byte[] model = readStream(is);
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(1);
            vadSession = ortEnv.createSession(model, opts);
            Log.i(TAG, "PersonalVadProcessor model loaded");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load personal_vad.onnx: " + e.getMessage());
            return false;
        }
    }

    static byte[] readStream(InputStream is) throws Exception {
        byte[] data = new byte[is.available()];
        int off = 0, r;
        while (off < data.length && (r = is.read(data, off, data.length - off)) != -1) off += r;
        is.close();
        return data;
    }

    /** Load speaker embedding (256 float32 LE) from file. */
    public boolean loadSpeakerEmbedding(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();
            FloatBuffer fb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            if (fb.remaining() != EMBED_DIM) {
                Log.e(TAG, "Embedding size mismatch: " + fb.remaining());
                return false;
            }
            speakerEmbedding = new float[EMBED_DIM];
            fb.get(speakerEmbedding);
            Log.i(TAG, "Speaker embedding loaded");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load embedding: " + e.getMessage());
            return false;
        }
    }

    public boolean isReady() {
        return initialized && speakerEmbedding != null;
    }

    /** Feed 16 kHz PCM samples. Thread-safe if called from a single thread. */
    public void process(short[] pcm, int length) {
        if (!isReady()) return;
        int offset = 0;
        while (offset < length) {
            int toCopy = Math.min(length - offset, NEW_SAMPLES_PER_CHUNK - accumLen);
            System.arraycopy(pcm, offset, accumBuf, accumLen, toCopy);
            offset += toCopy;
            accumLen += toCopy;
            if (accumLen == NEW_SAMPLES_PER_CHUNK) {
                processChunk();
                accumLen = 0;
            }
        }
    }

    /** Finalize any open segment. Call after recording ends. */
    public void flush() {
        if (!isReady()) return;
        // Process remaining buffered samples (< 4000)
        if (accumLen > 0) {
            // Zero-pad to full chunk
            Arrays.fill(accumBuf, accumLen, NEW_SAMPLES_PER_CHUNK, (short) 0);
            processChunk();
            accumLen = 0;
        }
        // Close any open segment
        if (inMyVoice && segmentStartMs >= 0) {
            long endMs = totalSamplesProcessed * 1000L / SR;
            segments.add(new long[]{segmentStartMs, endMs});
            inMyVoice = false;
            segmentStartMs = -1;
        }
    }

    public List<long[]> getSegments() {
        return segments;
    }

    public void reset() {
        accumLen = 0;
        Arrays.fill(overlapBuf, (short) 0);
        Arrays.fill(h, 0f);
        Arrays.fill(c, 0f);
        inMyVoice = false;
        hangoverCount = 0;
        segmentStartMs = -1;
        segments.clear();
        totalSamplesProcessed = 0;
    }

    public void release() {
        if (vadSession != null) {
            try { vadSession.close(); } catch (Exception ignored) {}
            vadSession = null;
        }
        initialized = false;
    }

    // ── Private processing ──────────────────────────────────────────────────

    private void processChunk() {
        // Build 4240-sample frame buffer: tail (240) + new (4000)
        short[] buf = new short[OVERLAP + NEW_SAMPLES_PER_CHUNK];
        System.arraycopy(overlapBuf, 0, buf, 0, OVERLAP);
        System.arraycopy(accumBuf, 0, buf, OVERLAP, NEW_SAMPLES_PER_CHUNK);

        // Save new tail for next chunk
        System.arraycopy(accumBuf, NEW_SAMPLES_PER_CHUNK - OVERLAP, overlapBuf, 0, OVERLAP);

        // Compute 25 log-mel frames → build [1, 25, 296] input
        float[] inputFlat = new float[VAD_CHUNK_FRAMES * (N_MELS + EMBED_DIM)];
        float[] powerSpec = new float[N_BINS];
        float[] melFrame = new float[N_MELS];

        for (int t = 0; t < VAD_CHUNK_FRAMES; t++) {
            int startSample = t * HOP_LENGTH;

            // Power spectrum at N_BINS frequencies
            for (int k = 0; k < N_BINS; k++) {
                float re = 0f, im = 0f;
                int base = k * N_FFT;
                for (int n = 0; n < N_FFT; n++) {
                    float s = buf[startSample + n] / 32768.0f;
                    re += cosTable[base + n] * s;
                    im += sinTable[base + n] * s;
                }
                powerSpec[k] = re * re + im * im;
            }

            // Mel filterbank → log10
            int frameBase = t * (N_MELS + EMBED_DIM);
            for (int m = 0; m < N_MELS; m++) {
                float val = 0f;
                int fbBase = m * N_BINS;
                for (int k = 0; k < N_BINS; k++) val += melFilterbank[fbBase + k] * powerSpec[k];
                inputFlat[frameBase + m] = (float) Math.log10(Math.max(val, 1e-6f));
            }

            // Append speaker embedding
            System.arraycopy(speakerEmbedding, 0, inputFlat, frameBase + N_MELS, EMBED_DIM);
        }

        long chunkStartMs = totalSamplesProcessed * 1000L / SR;
        totalSamplesProcessed += NEW_SAMPLES_PER_CHUNK;
        long chunkEndMs = totalSamplesProcessed * 1000L / SR;

        float targetProb = inferVad(inputFlat);
        updateHysteresis(targetProb, chunkStartMs, chunkEndMs);
    }

    private float inferVad(float[] inputFlat) {
        if (vadSession == null) return 0f;
        try {
            OnnxTensor inputT = OnnxTensor.createTensor(ortEnv,
                FloatBuffer.wrap(inputFlat),
                new long[]{1, VAD_CHUNK_FRAMES, N_MELS + EMBED_DIM});

            // LSTM state: flatten [LSTM_LAYERS, 1, LSTM_HIDDEN] into a 3-D float array
            float[][][] hArr = new float[LSTM_LAYERS][1][LSTM_HIDDEN];
            float[][][] cArr = new float[LSTM_LAYERS][1][LSTM_HIDDEN];
            for (int l = 0; l < LSTM_LAYERS; l++) {
                System.arraycopy(h, l * LSTM_HIDDEN, hArr[l][0], 0, LSTM_HIDDEN);
                System.arraycopy(c, l * LSTM_HIDDEN, cArr[l][0], 0, LSTM_HIDDEN);
            }
            OnnxTensor h0T = OnnxTensor.createTensor(ortEnv, hArr);
            OnnxTensor c0T = OnnxTensor.createTensor(ortEnv, cArr);

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input", inputT);
            inputs.put("h0", h0T);
            inputs.put("c0", c0T);

            OrtSession.Result result = vadSession.run(inputs);

            // logits: [1, 25, 3]
            float[][][] logits = (float[][][]) result.get("logits").get().getValue();
            float[] lastLogit = logits[0][VAD_CHUNK_FRAMES - 1]; // last frame

            // Update LSTM state
            float[][][] hn = (float[][][]) result.get("hn").get().getValue();
            float[][][] cn = (float[][][]) result.get("cn").get().getValue();
            for (int l = 0; l < LSTM_LAYERS; l++) {
                System.arraycopy(hn[l][0], 0, h, l * LSTM_HIDDEN, LSTM_HIDDEN);
                System.arraycopy(cn[l][0], 0, c, l * LSTM_HIDDEN, LSTM_HIDDEN);
            }

            result.close();
            inputT.close(); h0T.close(); c0T.close();

            return softmaxTarget(lastLogit);

        } catch (Exception e) {
            Log.e(TAG, "VAD inference error: " + e.getMessage());
            return 0f;
        }
    }

    private float softmaxTarget(float[] logits3) {
        float max = Math.max(logits3[0], Math.max(logits3[1], logits3[2]));
        float e0 = (float) Math.exp(logits3[0] - max);
        float e1 = (float) Math.exp(logits3[1] - max);
        float e2 = (float) Math.exp(logits3[2] - max);
        return e2 / (e0 + e1 + e2);
    }

    private void updateHysteresis(float prob, long startMs, long endMs) {
        if (!inMyVoice) {
            if (prob >= THRESHOLD_ON) {
                inMyVoice = true;
                segmentStartMs = startMs;
                hangoverCount = 0;
            }
        } else {
            if (prob < THRESHOLD_OFF) {
                hangoverCount++;
                if (hangoverCount > HANGOVER_FRAMES) {
                    segments.add(new long[]{segmentStartMs, startMs});
                    inMyVoice = false;
                    hangoverCount = 0;
                    segmentStartMs = -1;
                }
            } else {
                hangoverCount = 0;
            }
        }
    }

    // ── Package-accessible helpers for SpeakerEnrollment ───────────────────

    /**
     * Compute power mel-spectrogram frame (no log) for one 400-sample window
     * starting at buf[startSample]. Used by SpeakerEnrollment for Resemblyzer.
     */
    static float[] computePowerMelFrame(short[] buf, int startSample,
                                        float[] cosT, float[] sinT, float[] melFB) {
        float[] power = new float[N_BINS];
        for (int k = 0; k < N_BINS; k++) {
            float re = 0f, im = 0f;
            int base = k * N_FFT;
            for (int n = 0; n < N_FFT; n++) {
                float s = buf[startSample + n] / 32768.0f;
                re += cosT[base + n] * s;
                im += sinT[base + n] * s;
            }
            power[k] = re * re + im * im;
        }
        float[] mel = new float[N_MELS];
        for (int m = 0; m < N_MELS; m++) {
            float val = 0f;
            int fb = m * N_BINS;
            for (int k = 0; k < N_BINS; k++) val += melFB[fb + k] * power[k];
            mel[m] = val;
        }
        return mel;
    }
}
