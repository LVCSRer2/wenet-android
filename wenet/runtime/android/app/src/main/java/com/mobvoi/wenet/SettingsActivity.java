package com.mobvoi.wenet;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "wenet_settings";
    private static final String KEY_WEBHOOK_URL = "slack_webhook_url";
    private static final String KEY_MODEL_TYPE = "model_type";
    private static final String KEY_VIZ_TYPE = "viz_type";
    private static final String KEY_MIC_DEVICE = "mic_device";
    private static final String KEY_PLAY_DEVICE = "play_device";

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
