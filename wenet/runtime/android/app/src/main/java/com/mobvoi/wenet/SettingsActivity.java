package com.mobvoi.wenet;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "wenet_settings";
    private static final String KEY_WEBHOOK_URL = "slack_webhook_url";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        EditText urlEditText = findViewById(R.id.webhookUrlEditText);
        Button saveButton = findViewById(R.id.saveButton);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String savedUrl = prefs.getString(KEY_WEBHOOK_URL, "");
        urlEditText.setText(savedUrl);

        saveButton.setOnClickListener(v -> {
            String url = urlEditText.getText().toString().trim();
            prefs.edit().putString(KEY_WEBHOOK_URL, url).apply();
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
