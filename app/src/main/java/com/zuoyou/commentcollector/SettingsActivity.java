package com.zuoyou.commentcollector;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 设置界面 — 无障碍服务 + API 配置。
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText apiKeyEdit;
    private EditText apiBaseUrlEdit;
    private EditText modelNameEdit;
    private TextView saveButton;
    private SecurePrefs securePrefs;

    // 无障碍服务状态
    private View settingsStatusDot;
    private TextView settingsStatusText;
    private TextView settingsAccessButton;
    private TextView settingsAccessCloseButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        securePrefs = new SecurePrefs(this);

        // 无障碍服务
        settingsStatusDot = findViewById(R.id.settingsStatusDot);
        settingsStatusText = findViewById(R.id.settingsStatusText);
        settingsAccessButton = findViewById(R.id.settingsAccessButton);
        settingsAccessCloseButton = findViewById(R.id.settingsAccessCloseButton);

        // API 配置
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

        // 去开启 → 跳转系统无障碍设置
        settingsAccessButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        // 关闭 → 跳转系统无障碍设置（用户手动关闭）
        settingsAccessCloseButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessibilityStatus();
    }

    /**
     * 更新无障碍服务状态显示 + 按钮切换。
     */
    private void updateAccessibilityStatus() {
        if (MainActivity.isAccessibilityServiceEnabled(this)) {
            settingsStatusText.setText(R.string.settings_accessibility_status_on);
            settingsStatusDot.setBackgroundResource(R.drawable.bg_status_active);
            settingsAccessButton.setVisibility(View.GONE);
            settingsAccessCloseButton.setVisibility(View.VISIBLE);
        } else {
            settingsStatusText.setText(R.string.settings_accessibility_status_off);
            settingsStatusDot.setBackgroundResource(R.drawable.bg_status_inactive);
            settingsAccessButton.setVisibility(View.VISIBLE);
            settingsAccessCloseButton.setVisibility(View.GONE);
        }
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
