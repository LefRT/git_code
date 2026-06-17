package com.zuoyou.commentcollector;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;

/**
 * Phase 4: API 配置界面。
 *
 * <p>用户在此配置 DeepSeek API Key、地址、模型和 AI 人格风格。
 * 配置存储到 SharedPreferences，由 {@link AiService} 读取。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "zuoyou_prefs";

    private EditText apiKeyEdit;
    private EditText apiBaseUrlEdit;
    private EditText modelNameEdit;
    private MaterialAutoCompleteTextView personalityDropdown;
    private Button saveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        setTitle(getString(R.string.settings_title));

        apiKeyEdit = findViewById(R.id.apiKeyEdit);
        apiBaseUrlEdit = findViewById(R.id.apiBaseUrlEdit);
        modelNameEdit = findViewById(R.id.modelNameEdit);
        personalityDropdown = findViewById(R.id.personalityDropdown);
        saveButton = findViewById(R.id.saveButton);

        // 人格下拉
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.personality_options, android.R.layout.simple_dropdown_item_1line);
        personalityDropdown.setAdapter(adapter);

        loadExistingConfig();

        saveButton.setOnClickListener(v -> saveConfig());
    }

    private void loadExistingConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        apiKeyEdit.setText(prefs.getString("api_key", ""));
        apiBaseUrlEdit.setText(prefs.getString("api_base_url", "https://api.deepseek.com/v1"));
        modelNameEdit.setText(prefs.getString("model_name", "deepseek-chat"));

        String personality = prefs.getString("personality", "ROAST");
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

        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString("api_key", apiKey);
        editor.putString("api_base_url", apiBaseUrlEdit.getText().toString().trim());

        String model = modelNameEdit.getText().toString().trim();
        if (model.isEmpty()) model = "deepseek-chat";
        editor.putString("model_name", model);

        // 将选中的显示文本转回内部值
        String[] displayOptions = getResources().getStringArray(R.array.personality_options);
        String[] valueOptions = getResources().getStringArray(R.array.personality_values);
        String selectedDisplay = personalityDropdown.getText().toString();
        String selectedValue = "ROAST"; // 默认
        for (int i = 0; i < displayOptions.length; i++) {
            if (displayOptions[i].equals(selectedDisplay)) {
                selectedValue = valueOptions[i];
                break;
            }
        }
        editor.putString("personality", selectedValue);

        editor.apply();

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
