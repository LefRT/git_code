package com.zuoyou.commentcollector;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private Button toggleButton;
    private boolean isServiceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        toggleButton = findViewById(R.id.toggleButton);

        toggleButton.setOnClickListener(v -> {
            if (!isAccessibilityServiceEnabled()) {
                // 引导用户开启无障碍服务
                Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
            } else {
                Toast.makeText(this, "无障碍服务已开启，正在监听抖音", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        if (isAccessibilityServiceEnabled()) {
            statusText.setText("状态：正在监听抖音");
            toggleButton.setText("服务已开启");
            isServiceRunning = true;
        } else {
            statusText.setText("状态：未开启服务");
            toggleButton.setText("开启无障碍服务");
            isServiceRunning = false;
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String serviceName = getPackageName() + "/" + DouyinCommentService.class.getCanonicalName();
        try {
            int accessibilityEnabled = Settings.Secure.getInt(
                    getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED
            );
            if (accessibilityEnabled == 1) {
                String settingValue = Settings.Secure.getString(
                        getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                );
                if (settingValue != null) {
                    return settingValue.toLowerCase().contains(serviceName.toLowerCase());
                }
            }
        } catch (Settings.SettingNotFoundException e) {
            e.printStackTrace();
        }
        return false;
    }
}
