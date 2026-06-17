package com.zuoyou.commentcollector;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;

/**
 * Phase 4: API 配置界面。
 *
 * <p>用户在此配置 DeepSeek API Key、地址、模型和 AI 人格风格。
 * API Key 通过 {@link SecurePrefs} 加密存储，其余配置存储到普通 SharedPreferences，
 * 由 {@link AiService} 读取。
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText apiKeyEdit;
    private EditText apiBaseUrlEdit;
    private EditText modelNameEdit;
    private MaterialAutoCompleteTextView personalityDropdown;
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
        personalityDropdown = findViewById(R.id.personalityDropdown);
        saveButton = findViewById(R.id.saveButton);

        // 返回按钮
        TextView backButton = findViewById(R.id.backButton);
        if (backButton != null) {
            backButton.setOnClickListener(v -> finish());
        }

        // 人格下拉
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.personality_options, android.R.layout.simple_dropdown_item_1line);
        personalityDropdown.setAdapter(adapter);

        loadExistingConfig();

        saveButton.setOnClickListener(v -> saveConfig());
    }

    private void loadExistingConfig() {
        SharedPreferences prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE);
        // API Key 从加密存储读取
        apiKeyEdit.setText(securePrefs.getApiKey());
        apiBaseUrlEdit.setText(prefs.getString(Constants.KEY_API_BASE_URL, Constants.DEFAULT_API_BASE_URL));
        modelNameEdit.setText(prefs.getString(Constants.KEY_MODEL_NAME, Constants.DEFAULT_MODEL_NAME));

        String personality = prefs.getString(Constants.KEY_PERSONALITY, Constants.DEFAULT_PERSONALITY);
        String[] displayOptions = getResources().getStringArray(R.array.personality_options);
        String[] valueOptions = getResources().getStringArray(R.array.personality_values);
        for (int i = 0; i < valueOptions.length; i++) {
            if (valueOptions[i].equals(personality)) {
                personalityDropdown.setText(displayOptions[i], false);
                break;
            }
        }
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

        // API Key 写入加密存储
        securePrefs.saveApiKey(apiKey);

        // 其他配置写入普通 SharedPreferences
        SharedPreferences.Editor editor = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(Constants.KEY_API_BASE_URL, apiBaseUrl);

        String model = modelNameEdit.getText().toString().trim();
        if (model.isEmpty()) model = Constants.DEFAULT_MODEL_NAME;
        editor.putString(Constants.KEY_MODEL_NAME, model);

        // 将选中的显示文本转回内部值
        String[] displayOptions = getResources().getStringArray(R.array.personality_options);
        String[] valueOptions = getResources().getStringArray(R.array.personality_values);
        String selectedDisplay = personalityDropdown.getText().toString();
        String selectedValue = Constants.DEFAULT_PERSONALITY;
        for (int i = 0; i < displayOptions.length; i++) {
            if (displayOptions[i].equals(selectedDisplay)) {
                selectedValue = valueOptions[i];
                break;
            }
        }
        editor.putString(Constants.KEY_PERSONALITY, selectedValue);

        editor.apply();

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
