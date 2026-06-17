package com.zuoyou.commentcollector;

import android.Manifest;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ZuoYouMain";

    private TextView statusText;
    private TextView aiStatusText;
    private View statusDot;
    private View aiDot;
    private TextView accessibilityButton;
    private TextView screenCaptureButton;
    private TextView floatingWindowButton;
    private TextView settingsButton;
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

    /**
     * 悬浮窗权限回调。
     */
    private final ActivityResultLauncher<Intent> overlayPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (Settings.canDrawOverlays(this)) {
                            startFloatingWindowService();
                        } else {
                            Toast.makeText(this, R.string.overlay_permission_denied, Toast.LENGTH_SHORT).show();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        aiStatusText = findViewById(R.id.aiStatusText);
        statusDot = findViewById(R.id.statusDot);
        aiDot = findViewById(R.id.aiDot);
        accessibilityButton = findViewById(R.id.accessibilityButton);
        screenCaptureButton = findViewById(R.id.screenCaptureButton);
        floatingWindowButton = findViewById(R.id.floatingWindowButton);
        settingsButton = findViewById(R.id.settingsButton);

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

        // ── 悬浮窗按钮 ──
        floatingWindowButton.setOnClickListener(v -> {
            if (FloatingWindowService.isRunning()) {
                // 停止悬浮窗
                Intent intent = new Intent(this, FloatingWindowService.class);
                intent.setAction("STOP");
                startService(intent);
                floatingWindowButton.setText(R.string.floating_window_enable);
                aiStatusText.setText(R.string.ai_status_idle);
            } else {
                // Android 6+ 需要 SYSTEM_ALERT_WINDOW 权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && !Settings.canDrawOverlays(this)) {
                    Intent intent = new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    overlayPermissionLauncher.launch(intent);
                } else {
                    startFloatingWindowService();
                }
            }
        });

        // ── 设置按钮 ──
        settingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    /**
     * 启动悬浮窗服务。
     */
    private void startFloatingWindowService() {
        Intent intent = new Intent(this, FloatingWindowService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        floatingWindowButton.setText(R.string.floating_window_disable);
        aiStatusText.setText(R.string.ai_status_thinking);
        Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show();
    }

    /**
     * 更新 UI 状态：检查无障碍服务是否运行、屏幕捕获是否运行。
     */
    private void updateStatus() {
        // 无障碍服务
        if (isAccessibilityServiceEnabled()) {
            accessibilityButton.setText("✓ 无障碍服务已开启");
            isServiceRunning = true;
        } else {
            accessibilityButton.setText("开启无障碍服务");
            isServiceRunning = false;
        }

        // 屏幕捕获 + 悬浮窗
        boolean screenOn = ScreenCaptureService.isRunning;
        boolean floatOn = FloatingWindowService.isRunning();

        if (floatOn) {
            floatingWindowButton.setText(R.string.floating_window_disable);
        } else {
            floatingWindowButton.setText(R.string.floating_window_enable);
        }

        // AI 状态
        boolean hasApiKey = !new SecurePrefs(this).getApiKey().isEmpty();
        if (hasApiKey) {
            aiStatusText.setText(R.string.ai_status_thinking);
            aiDot.setBackgroundResource(R.drawable.bg_status_active);
        } else {
            aiStatusText.setText(R.string.ai_status_idle);
            aiDot.setBackgroundResource(R.drawable.bg_status_inactive);
        }

        // 状态文字 + 指示灯
        if (screenOn && floatOn) {
            statusText.setText("监听中 · 屏幕捕获 · AI 陪看");
            statusDot.setBackgroundResource(R.drawable.bg_status_active);
        } else if (screenOn) {
            statusText.setText("监听中 · 屏幕捕获");
            statusDot.setBackgroundResource(R.drawable.bg_status_active);
        } else if (isServiceRunning) {
            statusText.setText("监听中");
            statusDot.setBackgroundResource(R.drawable.bg_status_active);
        } else {
            statusText.setText("未开启服务");
            statusDot.setBackgroundResource(R.drawable.bg_status_inactive);
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
