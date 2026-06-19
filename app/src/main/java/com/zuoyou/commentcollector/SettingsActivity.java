package com.zuoyou.commentcollector;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * API 配置界面 — 配置 DeepSeek API Key、地址和模型。
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText apiKeyEdit;
    private EditText apiBaseUrlEdit;
    private EditText modelNameEdit;
    private TextView saveButton;
    private SecurePrefs securePrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        securePrefs = new SecurePrefs(this);

        apiKeyEdit = findViewById(R.id.apiKeyEdit);
        apiBaseUrlEdit = findViewById(R.id.apiBaseUrlEdit);
        modelNameEdit = findViewById(R.id.modelNameEdit);
        saveButton = findViewById(R.id.saveButton);

        TextView backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        loadExistingConfig();
        saveButton.setOnClickListener(v -> saveConfig());
    }

    private void loadExistingConfig() {
        SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
        apiKeyEdit.setText(securePrefs.getApiKey());
        apiBaseUrlEdit.setText(prefs.getString(Constants.KEY_API_BASE_URL, Constants.DEFAULT_API_BASE_URL));
        modelNameEdit.setText(prefs.getString(Constants.KEY_MODEL_NAME, Constants.DEFAULT_MODEL_NAME));
    }

    private void saveConfig() {
        String apiKey = apiKeyEdit.getText().toString().trim();
        if (apiKey.isEmpty()) {
            Toast.makeText(this, R.string.api_key_missing, Toast.LENGTH_SHORT).show();
            return;
        }

        String apiBaseUrl = apiBaseUrlEdit.getText().toString().trim();
        if (apiBaseUrl.isEmpty()) {
            apiBaseUrl = Constants.DEFAULT_API_BASE_URL;
        }

        securePrefs.saveApiKey(apiKey);

        SharedPreferences.Editor editor = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(Constants.KEY_API_BASE_URL, apiBaseUrl);

        String model = modelNameEdit.getText().toString().trim();
        if (model.isEmpty()) model = Constants.DEFAULT_MODEL_NAME;
        editor.putString(Constants.KEY_MODEL_NAME, model);

        editor.apply();

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
