package com.zuoyou.commentcollector;

import android.Manifest;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private Button accessibilityButton;
    private Button screenCaptureButton;
    private boolean isServiceRunning = false;

    /**
     * Android 13+ 通知权限请求（前台服务必需）。
     */
    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> { /* 通知权限结果已由系统 Toast 提示 */ }
            );

    /**
     * 屏幕捕获授权回调：用户确认授权后启动 ScreenCaptureService。
     */
    private final ActivityResultLauncher<Intent> screenCaptureLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Intent serviceIntent = new Intent(this, ScreenCaptureService.class);
                            serviceIntent.setAction("START_CAPTURE");
                            serviceIntent.putExtra("resultCode", result.getResultCode());
                            serviceIntent.putExtra("data", result.getData());
                            startForegroundService(serviceIntent);
                            screenCaptureButton.setText("停止屏幕捕获");
                        } else {
                            Toast.makeText(this, "屏幕捕获权限被拒绝", Toast.LENGTH_SHORT).show();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        accessibilityButton = findViewById(R.id.accessibilityButton);
        screenCaptureButton = findViewById(R.id.screenCaptureButton);

        // Android 13+ 需要 POST_NOTIFICATIONS 权限来显示前台通知
        requestNotificationPermission();

        // ── 无障碍服务按钮 ──
        accessibilityButton.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        // ── 屏幕捕获按钮 ──
        screenCaptureButton.setOnClickListener(v -> {
            if (ScreenCaptureService.isRunning) {
                // 停止捕获
                Intent intent = new Intent(this, ScreenCaptureService.class);
                intent.setAction("STOP_CAPTURE");
                startService(intent);
                screenCaptureButton.setText("开启屏幕捕获");
            } else {
                // 请求授权
                MediaProjectionManager manager =
                        (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                if (manager != null) {
                    screenCaptureLauncher.launch(manager.createScreenCaptureIntent());
                } else {
                    Toast.makeText(this, "设备不支持屏幕捕获", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    /**
     * 更新 UI 状态：检查无障碍服务是否运行、屏幕捕获是否运行。
     */
    private void updateStatus() {
        // 无障碍服务
        if (isAccessibilityServiceEnabled()) {
            accessibilityButton.setText("服务已开启");
            isServiceRunning = true;
        } else {
            accessibilityButton.setText("开启无障碍服务");
            isServiceRunning = false;
        }

        // 屏幕捕获
        if (ScreenCaptureService.isRunning) {
            screenCaptureButton.setText("停止屏幕捕获");
            statusText.setText("状态：正在监听抖音 + 屏幕捕获");
        } else if (isServiceRunning) {
            statusText.setText("状态：正在监听抖音");
        } else {
            statusText.setText("状态：未开启服务");
            screenCaptureButton.setText("开启屏幕捕获");
        }
    }

    /**
     * 检查无障碍服务是否已启用。
     */
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

    /**
     * Android 13+ 请求通知权限（前台服务必需）。
     */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }
}
