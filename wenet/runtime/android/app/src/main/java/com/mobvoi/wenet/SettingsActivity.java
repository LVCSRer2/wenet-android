package com.mobvoi.wenet;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "wenet_settings";
    private static final String KEY_WEBHOOK_URL = "slack_webhook_url";
    private static final String KEY_MODEL_TYPE = "model_type";
    private static final String KEY_VIZ_TYPE = "viz_type";
    private static final String KEY_MIC_DEVICE = "mic_device";
    private static final String KEY_PLAY_DEVICE = "play_device";
    private static final String KEY_AEC = "audio_aec";
    private static final String KEY_NS = "audio_ns";
    private static final String KEY_AGC = "audio_agc";
    private static final String KEY_VAD = "vad_enabled";
    private static final String KEY_VAD_THRESHOLD = "vad_threshold";
    private static final String KEY_VAD_PREBUFFER = "vad_prebuffer";
    private static final String KEY_VAD_TRAILING = "vad_trailing";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Webhook URL
        EditText urlEditText = findViewById(R.id.webhookUrlEditText);
        urlEditText.setText(prefs.getString(KEY_WEBHOOK_URL, ""));

        // Model selection
        RadioGroup modelGroup = findViewById(R.id.modelRadioGroup);
        String currentModel = prefs.getString(KEY_MODEL_TYPE, "");
        if ("full".equals(currentModel)) {
            modelGroup.check(R.id.radioFull);
        } else if ("quant".equals(currentModel)) {
            modelGroup.check(R.id.radioQuant);
        }

        // Visualization selection
        RadioGroup vizGroup = findViewById(R.id.vizRadioGroup);
        String currentViz = prefs.getString(KEY_VIZ_TYPE, "waveform");
        if ("spectrogram".equals(currentViz)) {
            vizGroup.check(R.id.radioSpectrogram);
        } else {
            vizGroup.check(R.id.radioWaveform);
        }

        // Mic device selection
        RadioGroup micGroup = findViewById(R.id.micRadioGroup);
        String currentMic = prefs.getString(KEY_MIC_DEVICE, "bluetooth");
        if ("phone".equals(currentMic)) {
            micGroup.check(R.id.radioMicPhone);
        } else {
            micGroup.check(R.id.radioMicBluetooth);
        }

        // Play device selection
        RadioGroup playGroup = findViewById(R.id.playRadioGroup);
        String currentPlay = prefs.getString(KEY_PLAY_DEVICE, "phone");
        if ("bluetooth".equals(currentPlay)) {
            playGroup.check(R.id.radioPlayBluetooth);
        } else {
            playGroup.check(R.id.radioPlayPhone);
        }

        // Audio processing checkboxes
        CheckBox checkAec = findViewById(R.id.checkAec);
        CheckBox checkNs = findViewById(R.id.checkNs);
        CheckBox checkAgc = findViewById(R.id.checkAgc);
        checkAec.setChecked(prefs.getBoolean(KEY_AEC, true));
        checkNs.setChecked(prefs.getBoolean(KEY_NS, true));
        checkAgc.setChecked(prefs.getBoolean(KEY_AGC, true));

        CheckBox checkVad = findViewById(R.id.checkVad);
        checkVad.setChecked(prefs.getBoolean(KEY_VAD, true));

        // VAD Threshold: SeekBar 0~80 → 0.10~0.90
        SeekBar vadThresholdSeekBar = findViewById(R.id.vadThresholdSeekBar);
        TextView vadThresholdLabel = findViewById(R.id.vadThresholdLabel);
        int savedThresholdProgress = prefs.getInt(KEY_VAD_THRESHOLD, 40); // default 0.50
        vadThresholdSeekBar.setProgress(savedThresholdProgress);
        float initThreshold = (savedThresholdProgress + 10) / 100f;
        vadThresholdLabel.setText(String.format("Speech Threshold: %.2f", initThreshold));
        vadThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                float val = (progress + 10) / 100f;
                vadThresholdLabel.setText(String.format("Speech Threshold: %.2f", val));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        // VAD Pre-buffer: SeekBar 0~18 → 2~20 chunks
        SeekBar vadPreBufferSeekBar = findViewById(R.id.vadPreBufferSeekBar);
        TextView vadPreBufferLabel = findViewById(R.id.vadPreBufferLabel);
        int savedPreBufferProgress = prefs.getInt(KEY_VAD_PREBUFFER, 8); // default 10 chunks
        vadPreBufferSeekBar.setProgress(savedPreBufferProgress);
        int initChunks = savedPreBufferProgress + 2;
        int initMs = initChunks * 256 * 1000 / 8000;
        vadPreBufferLabel.setText(String.format("Pre-buffer: %d chunks (%dms)", initChunks, initMs));
        vadPreBufferSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int chunks = progress + 2;
                int ms = chunks * 256 * 1000 / 8000;
                vadPreBufferLabel.setText(String.format("Pre-buffer: %d chunks (%dms)", chunks, ms));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        // VAD Trailing Silence: SeekBar 0~35 → 5~40 chunks
        SeekBar vadTrailingSilenceSeekBar = findViewById(R.id.vadTrailingSilenceSeekBar);
        TextView vadTrailingSilenceLabel = findViewById(R.id.vadTrailingSilenceLabel);
        int savedTrailingProgress = prefs.getInt(KEY_VAD_TRAILING, 20); // default 25 chunks
        vadTrailingSilenceSeekBar.setProgress(savedTrailingProgress);
        int initTrailing = savedTrailingProgress + 5;
        int initTrailingMs = initTrailing * 256 * 1000 / 8000;
        vadTrailingSilenceLabel.setText(String.format("Trailing Silence: %d chunks (%dms)", initTrailing, initTrailingMs));
        vadTrailingSilenceSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                int chunks = progress + 5;
                int ms = chunks * 256 * 1000 / 8000;
                vadTrailingSilenceLabel.setText(String.format("Trailing Silence: %d chunks (%dms)", chunks, ms));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        Button saveButton = findViewById(R.id.saveButton);
        saveButton.setOnClickListener(v -> {
            String url = urlEditText.getText().toString().trim();
            prefs.edit().putString(KEY_WEBHOOK_URL, url).apply();

            // Check if model changed
            String newModel = modelGroup.getCheckedRadioButtonId() == R.id.radioFull
                ? "full" : "quant";
            String oldModel = prefs.getString(KEY_MODEL_TYPE, "");
            prefs.edit().putString(KEY_MODEL_TYPE, newModel).apply();

            // Save viz type
            String newViz = vizGroup.getCheckedRadioButtonId() == R.id.radioSpectrogram
                ? "spectrogram" : "waveform";
            prefs.edit().putString(KEY_VIZ_TYPE, newViz).apply();

            // Save mic/play device
            String newMic = micGroup.getCheckedRadioButtonId() == R.id.radioMicBluetooth
                ? "bluetooth" : "phone";
            prefs.edit().putString(KEY_MIC_DEVICE, newMic).apply();

            String newPlay = playGroup.getCheckedRadioButtonId() == R.id.radioPlayBluetooth
                ? "bluetooth" : "phone";
            prefs.edit().putString(KEY_PLAY_DEVICE, newPlay).apply();

            // Save audio processing settings
            prefs.edit().putBoolean(KEY_AEC, checkAec.isChecked()).apply();
            prefs.edit().putBoolean(KEY_NS, checkNs.isChecked()).apply();
            prefs.edit().putBoolean(KEY_AGC, checkAgc.isChecked()).apply();
            prefs.edit().putBoolean(KEY_VAD, checkVad.isChecked()).apply();
            prefs.edit().putInt(KEY_VAD_THRESHOLD, vadThresholdSeekBar.getProgress()).apply();
            prefs.edit().putInt(KEY_VAD_PREBUFFER, vadPreBufferSeekBar.getProgress()).apply();
            prefs.edit().putInt(KEY_VAD_TRAILING, vadTrailingSilenceSeekBar.getProgress()).apply();

            Intent result = new Intent();
            boolean changed = false;
            if (!newModel.equals(oldModel)) {
                result.putExtra("model_type", newModel);
                changed = true;
            }
            result.putExtra("viz_type", newViz);
            result.putExtra("mic_device", newMic);
            result.putExtra("play_device", newPlay);
            changed = true;
            if (changed) {
                setResult(RESULT_OK, result);
            }

            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
