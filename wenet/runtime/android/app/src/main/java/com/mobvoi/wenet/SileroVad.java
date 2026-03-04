package com.mobvoi.wenet;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

public class SileroVad {

    private static final String TAG = "SileroVad";
    private static final int CHUNK_SIZE = 256;  // 256 samples = 32ms at 8kHz
    private static final int SAMPLE_RATE = 8000;
    // Configurable parameters
    private float speechThreshold = 0.5f;
    private float silenceThreshold = 0.3f;
    private int preBufferSlots = 10;  // ~320ms
    private int trailingSilenceChunks = 25;  // ~800ms (enough for WeNet endpoint)

    public interface Callback {
        void onSpeechChunk(short[] data, int length);
        void onSkippedSamples(int count);
    }

    private enum State { IDLE, SPEAKING, TRAILING_SILENCE }

    private OrtEnvironment ortEnv;
    private OrtSession ortSession;
    private float[][] state;  // [2][128]
    private State vadState = State.IDLE;
    private int trailingSilenceCount = 0;
    private boolean initialized = false;
    private volatile float lastProb = 0f;

    // Pre-buffer: ring buffer of CHUNK_SIZE chunks
    private short[][] preBuffer;
    private int preBufferHead = 0;  // next write position
    private int preBufferCount = 0; // current number of chunks in buffer

    // Residual buffer for incomplete chunks
    private short[] residual = new short[CHUNK_SIZE];
    private int residualLen = 0;

    public void setSpeechThreshold(float threshold) {
        this.speechThreshold = threshold;
        this.silenceThreshold = Math.max(0.05f, threshold - 0.2f);
    }

    public float getSpeechThreshold() {
        return speechThreshold;
    }

    public void setPreBufferSlots(int slots) {
        this.preBufferSlots = Math.max(1, slots);
    }

    public int getPreBufferSlots() {
        return preBufferSlots;
    }

    public void setTrailingSilenceChunks(int chunks) {
        this.trailingSilenceChunks = Math.max(1, chunks);
    }

    public int getTrailingSilenceChunks() {
        return trailingSilenceChunks;
    }

    public boolean init(Context context) {
        try {
            ortEnv = OrtEnvironment.getEnvironment();
            AssetManager assets = context.getAssets();
            InputStream is = assets.open("silero_vad.onnx");
            byte[] modelBytes = new byte[is.available()];
            int offset = 0;
            int read;
            while (offset < modelBytes.length && (read = is.read(modelBytes, offset, modelBytes.length - offset)) != -1) {
                offset += read;
            }
            is.close();

            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(1);
            ortSession = ortEnv.createSession(modelBytes, opts);

            // Initialize state: [2][128] zeros
            state = new float[2][128];
            preBuffer = new short[preBufferSlots][];
            vadState = State.IDLE;
            trailingSilenceCount = 0;
            preBufferHead = 0;
            preBufferCount = 0;
            residualLen = 0;
            initialized = true;
            Log.i(TAG, "Silero VAD initialized: threshold=" + speechThreshold
                    + " preBuffer=" + preBufferSlots + " chunks ("
                    + (preBufferSlots * CHUNK_SIZE * 1000 / SAMPLE_RATE) + "ms)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Silero VAD: " + e.getMessage());
            initialized = false;
            return false;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void process(short[] data, int length, Callback callback) {
        if (!initialized) {
            // Fail-open: pass everything to ASR
            callback.onSpeechChunk(data, length);
            return;
        }

        int offset = 0;

        // If there's residual data, fill it first
        if (residualLen > 0) {
            int needed = CHUNK_SIZE - residualLen;
            int toCopy = Math.min(needed, length);
            System.arraycopy(data, 0, residual, residualLen, toCopy);
            residualLen += toCopy;
            offset = toCopy;

            if (residualLen == CHUNK_SIZE) {
                processChunk(residual, callback);
                residualLen = 0;
            }
        }

        // Process full chunks from remaining data
        while (offset + CHUNK_SIZE <= length) {
            short[] chunk = new short[CHUNK_SIZE];
            System.arraycopy(data, offset, chunk, 0, CHUNK_SIZE);
            processChunk(chunk, callback);
            offset += CHUNK_SIZE;
        }

        // Save remaining as residual
        if (offset < length) {
            System.arraycopy(data, offset, residual, 0, length - offset);
            residualLen = length - offset;
        }
    }

    public float getLastProb() {
        return lastProb;
    }

    private void processChunk(short[] chunk, Callback callback) {
        float prob = infer(chunk);
        lastProb = prob;

        switch (vadState) {
            case IDLE:
                if (prob >= speechThreshold) {
                    // Flush pre-buffer as speech
                    flushPreBuffer(callback);
                    callback.onSpeechChunk(chunk, chunk.length);
                    vadState = State.SPEAKING;
                } else {
                    addToPreBuffer(chunk, callback);
                }
                break;

            case SPEAKING:
                if (prob < silenceThreshold) {
                    callback.onSpeechChunk(chunk, chunk.length);
                    vadState = State.TRAILING_SILENCE;
                    trailingSilenceCount = 1;
                } else {
                    callback.onSpeechChunk(chunk, chunk.length);
                }
                break;

            case TRAILING_SILENCE:
                if (prob >= speechThreshold) {
                    callback.onSpeechChunk(chunk, chunk.length);
                    vadState = State.SPEAKING;
                    trailingSilenceCount = 0;
                } else {
                    callback.onSpeechChunk(chunk, chunk.length);
                    trailingSilenceCount++;
                    if (trailingSilenceCount > trailingSilenceChunks) {
                        vadState = State.IDLE;
                        trailingSilenceCount = 0;
                    }
                }
                break;
        }
    }

    private void addToPreBuffer(short[] chunk, Callback callback) {
        if (preBufferCount == preBufferSlots) {
            // Evict oldest chunk as skipped
            callback.onSkippedSamples(preBuffer[preBufferHead].length);
        }
        preBuffer[preBufferHead] = chunk;
        preBufferHead = (preBufferHead + 1) % preBufferSlots;
        if (preBufferCount < preBufferSlots) {
            preBufferCount++;
        }
    }

    private void flushPreBuffer(Callback callback) {
        if (preBufferCount == 0) return;
        // Read from oldest to newest
        int start = (preBufferHead - preBufferCount + preBufferSlots) % preBufferSlots;
        for (int i = 0; i < preBufferCount; i++) {
            int idx = (start + i) % preBufferSlots;
            callback.onSpeechChunk(preBuffer[idx], preBuffer[idx].length);
        }
        preBufferCount = 0;
        preBufferHead = 0;
    }

    private float infer(short[] chunk) {
        try {
            // Convert short[] to float[] normalized to [-1, 1]
            float[] inputData = new float[CHUNK_SIZE];
            for (int i = 0; i < CHUNK_SIZE; i++) {
                inputData[i] = chunk[i] / 32768.0f;
            }

            // Prepare input tensors
            // input: [1, 256]
            long[] inputShape = {1, CHUNK_SIZE};
            OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv,
                    FloatBuffer.wrap(inputData), inputShape);

            // state: [2, 1, 128]
            float[][][] stateData = new float[2][1][128];
            stateData[0][0] = Arrays.copyOf(state[0], 128);
            stateData[1][0] = Arrays.copyOf(state[1], 128);
            OnnxTensor stateTensor = OnnxTensor.createTensor(ortEnv, stateData);

            // sr: [1] = 8000
            long[] srData = {SAMPLE_RATE};
            OnnxTensor srTensor = OnnxTensor.createTensor(ortEnv,
                    LongBuffer.wrap(srData), new long[]{1});

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input", inputTensor);
            inputs.put("state", stateTensor);
            inputs.put("sr", srTensor);

            OrtSession.Result result = ortSession.run(inputs);

            // output: [1, 1]
            float[][] output = (float[][]) result.get(0).getValue();
            float prob = output[0][0];

            // stateN: [2, 1, 128]
            float[][][] newState = (float[][][]) result.get(1).getValue();
            state[0] = Arrays.copyOf(newState[0][0], 128);
            state[1] = Arrays.copyOf(newState[1][0], 128);

            // Close resources
            result.close();
            inputTensor.close();
            stateTensor.close();
            srTensor.close();

            return prob;
        } catch (Exception e) {
            Log.e(TAG, "VAD inference error: " + e.getMessage());
            // Fail-open: treat as speech
            return 1.0f;
        }
    }

    /**
     * Flush residual audio as speech (call at end of recording if in SPEAKING/TRAILING_SILENCE).
     */
    public void flush(Callback callback) {
        if (!initialized) return;
        if (residualLen > 0) {
            if (vadState == State.SPEAKING || vadState == State.TRAILING_SILENCE) {
                short[] remaining = new short[residualLen];
                System.arraycopy(residual, 0, remaining, 0, residualLen);
                callback.onSpeechChunk(remaining, residualLen);
            } else {
                callback.onSkippedSamples(residualLen);
            }
            residualLen = 0;
        }
    }

    /**
     * At end of recording, report remaining pre-buffer as skipped if still IDLE.
     */
    public void flushRemainingAsSkipped(Callback callback) {
        if (!initialized) return;
        if (vadState == State.IDLE && preBufferCount > 0) {
            int start = (preBufferHead - preBufferCount + preBufferSlots) % preBufferSlots;
            for (int i = 0; i < preBufferCount; i++) {
                int idx = (start + i) % preBufferSlots;
                callback.onSkippedSamples(preBuffer[idx].length);
            }
            preBufferCount = 0;
            preBufferHead = 0;
        }
    }

    public void reset() {
        if (!initialized) return;
        state = new float[2][128];
        preBuffer = new short[preBufferSlots][];
        vadState = State.IDLE;
        trailingSilenceCount = 0;
        preBufferHead = 0;
        preBufferCount = 0;
        residualLen = 0;
        lastProb = 0f;
    }

    public void release() {
        if (ortSession != null) {
            try { ortSession.close(); } catch (Exception ignored) {}
            ortSession = null;
        }
        initialized = false;
    }
}
