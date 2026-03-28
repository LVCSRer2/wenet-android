package com.mobvoi.wenet;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "wenet_settings";
    private static final String KEY_WEBHOOK_URL = "slack_webhook_url";
    private static final String KEY_OPENAI_API_KEY = "openai_api_key";
    private static final String KEY_MODEL_TYPE = "model_type";
    private static final String KEY_VIZ_TYPE = "viz_type";
    private static final String KEY_AEC = "audio_aec";
    private static final String KEY_NS = "audio_ns";
    private static final String KEY_AGC = "audio_agc";
    private static final String KEY_VAD = "vad_enabled";
    private static final String KEY_VAD_THRESHOLD = "vad_threshold";
    private static final String KEY_VAD_PREBUFFER = "vad_prebuffer";
    private static final String KEY_VAD_TRAILING = "vad_trailing";
    private static final String KEY_RESULT_FONT_SIZE = "result_font_size";
    private static final String KEY_CODEC = "codec_type";
    private static final String KEY_PERSONAL_VAD_ACTIVATION = "personal_vad_activation";
    private static final String KEY_PERSONAL_VAD_DEACTIVATION = "personal_vad_deactivation";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // OpenAI API Key
        EditText openaiApiKeyEditText = findViewById(R.id.openaiApiKeyEditText);
        openaiApiKeyEditText.setText(prefs.getString(KEY_OPENAI_API_KEY, ""));

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

        // Codec selection (2x2 grid — mutual exclusion handled manually)
        android.widget.RadioButton radioOpus = findViewById(R.id.radioOpus);
        android.widget.RadioButton radioAac = findViewById(R.id.radioAac);
        android.widget.RadioButton radioAmrNb = findViewById(R.id.radioAmrNb);
        android.widget.RadioButton radioAacHw = findViewById(R.id.radioAacHw);
        android.widget.RadioButton[] codecButtons = {radioOpus, radioAac, radioAmrNb, radioAacHw};

        String currentCodec = prefs.getString(KEY_CODEC, "opus");
        if ("aac".equals(currentCodec)) radioAac.setChecked(true);
        else if ("amrnb".equals(currentCodec)) radioAmrNb.setChecked(true);
        else if ("aac_hw".equals(currentCodec)) radioAacHw.setChecked(true);
        else radioOpus.setChecked(true);

        for (android.widget.RadioButton rb : codecButtons) {
            rb.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked) for (android.widget.RadioButton other : codecButtons)
                    if (other != btn) other.setChecked(false);
            });
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

        // Result Font Size: SeekBar 0~10 → 15~22sp
        SeekBar resultFontSizeSeekBar = findViewById(R.id.resultFontSizeSeekBar);
        TextView resultFontSizeLabel = findViewById(R.id.resultFontSizeLabel);
        int savedFontSp = prefs.getInt(KEY_RESULT_FONT_SIZE, 18); // default 18sp (actual sp value)
        int savedFontProgress = Math.max(0, Math.min(7, savedFontSp - 15));
        resultFontSizeSeekBar.setProgress(savedFontProgress);
        resultFontSizeLabel.setText(String.format("Font Size: %dsp", savedFontSp));
        resultFontSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                resultFontSizeLabel.setText(String.format("Font Size: %dsp", progress + 15));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        // Personal VAD Activation Threshold: SeekBar 0~100 → 0.00~1.00
        SeekBar personalVadActivationSeekBar = findViewById(R.id.personalVadActivationSeekBar);
        TextView personalVadActivationLabel = findViewById(R.id.personalVadActivationLabel);
        int savedActivation = prefs.getInt(KEY_PERSONAL_VAD_ACTIVATION, 80); // default 0.80
        personalVadActivationSeekBar.setProgress(savedActivation);
        personalVadActivationLabel.setText(String.format("Activation Threshold: %.2f", savedActivation / 100f));
        personalVadActivationSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                personalVadActivationLabel.setText(String.format("Activation Threshold: %.2f", progress / 100f));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        // Personal VAD Deactivation Threshold: SeekBar 0~100 → 0.00~1.00
        SeekBar personalVadDeactivationSeekBar = findViewById(R.id.personalVadDeactivationSeekBar);
        TextView personalVadDeactivationLabel = findViewById(R.id.personalVadDeactivationLabel);
        int savedDeactivation = prefs.getInt(KEY_PERSONAL_VAD_DEACTIVATION, 70); // default 0.70
        personalVadDeactivationSeekBar.setProgress(savedDeactivation);
        personalVadDeactivationLabel.setText(String.format("Deactivation Threshold: %.2f", savedDeactivation / 100f));
        personalVadDeactivationSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                personalVadDeactivationLabel.setText(String.format("Deactivation Threshold: %.2f", progress / 100f));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        // Enrollment section
        TextView enrollmentStatusText = findViewById(R.id.enrollmentStatusText);
        File embFile = new File(getFilesDir(), "speaker_embedding.bin");
        updateEnrollmentStatus(enrollmentStatusText, embFile);

        Button enrollButton = findViewById(R.id.enrollButton);
        enrollButton.setOnClickListener(v -> showEnrollmentDialog(enrollmentStatusText, embFile));

        Button saveButton = findViewById(R.id.saveButton);
        saveButton.setOnClickListener(v -> {
            String openaiKey = openaiApiKeyEditText.getText().toString().trim();
            prefs.edit().putString(KEY_OPENAI_API_KEY, openaiKey).apply();

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

            // Save codec type
            String newCodec = radioAac.isChecked() ? "aac"
                : radioAmrNb.isChecked() ? "amrnb"
                : radioAacHw.isChecked() ? "aac_hw" : "opus";
            prefs.edit().putString(KEY_CODEC, newCodec).apply();

            // Save audio processing settings
            prefs.edit().putBoolean(KEY_AEC, checkAec.isChecked()).apply();
            prefs.edit().putBoolean(KEY_NS, checkNs.isChecked()).apply();
            prefs.edit().putBoolean(KEY_AGC, checkAgc.isChecked()).apply();
            prefs.edit().putBoolean(KEY_VAD, checkVad.isChecked()).apply();
            prefs.edit().putInt(KEY_VAD_THRESHOLD, vadThresholdSeekBar.getProgress()).apply();
            prefs.edit().putInt(KEY_VAD_PREBUFFER, vadPreBufferSeekBar.getProgress()).apply();
            prefs.edit().putInt(KEY_VAD_TRAILING, vadTrailingSilenceSeekBar.getProgress()).apply();
            prefs.edit().putInt(KEY_RESULT_FONT_SIZE, resultFontSizeSeekBar.getProgress() + 15).apply();
            prefs.edit().putInt(KEY_PERSONAL_VAD_ACTIVATION, personalVadActivationSeekBar.getProgress()).apply();
            prefs.edit().putInt(KEY_PERSONAL_VAD_DEACTIVATION, personalVadDeactivationSeekBar.getProgress()).apply();

            Intent result = new Intent();
            boolean changed = false;
            if (!newModel.equals(oldModel)) {
                result.putExtra("model_type", newModel);
                changed = true;
            }
            result.putExtra("viz_type", newViz);
            changed = true;
            if (changed) {
                setResult(RESULT_OK, result);
            }

            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void updateEnrollmentStatus(TextView statusText, File embFile) {
        if (embFile.exists()) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
            statusText.setText("등록됨: " + sdf.format(new java.util.Date(embFile.lastModified())));
        } else {
            statusText.setText("미등록");
        }
    }

    private void showEnrollmentDialog(TextView statusText, File embFile) {
        List<RecordingManager.SearchResult> recordings =
            RecordingManager.searchRecordings(this, "");
        if (recordings.isEmpty()) {
            Toast.makeText(this, "녹음이 없습니다", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] items = new String[recordings.size()];
        for (int i = 0; i < recordings.size(); i++) {
            RecordingManager.SearchResult sr = recordings.get(i);
            String preview = sr.preview.length() > 40 ? sr.preview.substring(0, 40) + "..." : sr.preview;
            items[i] = sr.name + "  " + preview;
        }

        new AlertDialog.Builder(this)
            .setTitle("내 목소리만 담긴 녹음 선택")
            .setItems(items, (dialog, which) -> {
                String name = recordings.get(which).name;
                Toast.makeText(this, "등록 중...", Toast.LENGTH_SHORT).show();
                SpeakerEnrollment.enroll(this, name, new SpeakerEnrollment.Callback() {
                    @Override public void onSuccess() {
                        updateEnrollmentStatus(statusText, embFile);
                        Toast.makeText(SettingsActivity.this,
                            "등록 완료!", Toast.LENGTH_SHORT).show();
                    }
                    @Override public void onError(String message) {
                        Toast.makeText(SettingsActivity.this,
                            "등록 실패: " + message, Toast.LENGTH_LONG).show();
                    }
                });
            })
            .show();
    }
}
