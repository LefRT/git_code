package com.zuoyou.commentcollector;

import android.Manifest;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ZuoYouMain";

    private DrawerLayout drawerLayout;
    private LinearLayout drawerView;

    // 侧边栏合并服务开关
    private TextView drawerServiceStatus;
    private TextView drawerServiceToggle;

    /** 用于延迟更新抽屉状态（服务停止是异步的） */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
                            Log.d(TAG, "屏幕捕获已启动");
                        } else {
                            Log.d(TAG, "屏幕捕获授权被拒绝");
                            Toast.makeText(this, "屏幕捕获权限被拒绝", Toast.LENGTH_SHORT).show();
                        }
                        updateDrawerStatus();
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
                            // 悬浮窗启动后，继续请求屏幕捕获
                            requestScreenCapture();
                        } else {
                            Toast.makeText(this, R.string.overlay_permission_denied, Toast.LENGTH_SHORT).show();
                        }
                        updateDrawerStatus();
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        drawerLayout = findViewById(R.id.drawerLayout);
        drawerView = findViewById(R.id.drawerView);

        // 侧边栏视图绑定
        drawerServiceStatus = findViewById(R.id.drawerServiceStatus);
        drawerServiceToggle = findViewById(R.id.drawerServiceToggle);

        // Android 13+ 需要 POST_NOTIFICATIONS 权限来显示前台通知
        requestNotificationPermission();

        // ── 菜单按钮：打开侧边栏 ──
        ImageButton menuButton = findViewById(R.id.menuButton);
        menuButton.setOnClickListener(v -> {
            updateDrawerStatus();
            drawerLayout.openDrawer(drawerView);
        });

        // ── 合并服务开关：悬浮窗 + 屏幕捕获 ──
        drawerServiceToggle.setOnClickListener(v -> {
            if (FloatingWindowService.isRunning() || ScreenCaptureService.isRunning) {
                // 关闭：依次停止屏幕捕获 → 悬浮窗
                if (ScreenCaptureService.isRunning) {
                    Intent intent = new Intent(this, ScreenCaptureService.class);
                    intent.setAction("STOP_CAPTURE");
                    startService(intent);
                }
                if (FloatingWindowService.isRunning()) {
                    Intent intent = new Intent(this, FloatingWindowService.class);
                    intent.setAction("STOP");
                    startService(intent);
                }
                Toast.makeText(this, "服务已关闭", Toast.LENGTH_SHORT).show();
                // 乐观更新 UI（服务停止异步，但状态已确定）
                drawerServiceStatus.setText("未开启");
                drawerServiceToggle.setText("开启");
                drawerServiceToggle.setBackgroundResource(R.drawable.bg_button_primary);
                // 延迟同步确认（等待服务处理器执行完毕）
                mainHandler.postDelayed(this::updateDrawerStatus, 150);
            } else {
                // 开启：先悬浮窗，再屏幕捕获
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && !Settings.canDrawOverlays(this)) {
                    Intent intent = new Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    overlayPermissionLauncher.launch(intent);
                    return;
                }
                startFloatingWindowService();
                requestScreenCapture();
            }
            updateDrawerStatus();
        });

        // ── 设置按钮 ──
        LinearLayout drawerSettingsButton = findViewById(R.id.drawerSettingsButton);
        drawerSettingsButton.setOnClickListener(v -> {
            drawerLayout.closeDrawer(drawerView);
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDrawerStatus();
    }

    /**
     * 更新侧边栏合并服务的状态显示。
     * 同时检查无障碍服务状态，提醒用户基础服务未运行。
     */
    private void updateDrawerStatus() {
        boolean screenOrFloat = ScreenCaptureService.isRunning || FloatingWindowService.isRunning();
        boolean a11yEnabled = isAccessibilityServiceEnabled(this);
        if (screenOrFloat) {
            if (!a11yEnabled) {
                drawerServiceStatus.setText("⚠ 运行中（无障碍服务未开启）");
            } else {
                drawerServiceStatus.setText("运行中");
            }
            drawerServiceToggle.setText("关闭");
            drawerServiceToggle.setBackgroundResource(R.drawable.bg_button_close);
        } else {
            drawerServiceStatus.setText("未开启");
            drawerServiceToggle.setText("开启");
            drawerServiceToggle.setBackgroundResource(R.drawable.bg_button_primary);
        }
    }

    /**
     * 请求屏幕捕获权限。
     */
    private void requestScreenCapture() {
        if (ScreenCaptureService.isRunning) {
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager != null) {
            screenCaptureLauncher.launch(manager.createScreenCaptureIntent());
        } else {
            Toast.makeText(this, "设备不支持屏幕捕获", Toast.LENGTH_SHORT).show();
        }
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
        Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show();
        Log.d(TAG, "悬浮窗已启动");
    }

    /**
     * 检查无障碍服务是否已启用（供设置页使用）。
     */
    static boolean isAccessibilityServiceEnabled(android.content.Context context) {
        String serviceName = context.getPackageName() + "/" + DouyinCommentService.class.getCanonicalName();
        try {
            int accessibilityEnabled = Settings.Secure.getInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED
            );
            if (accessibilityEnabled == 1) {
                String settingValue = Settings.Secure.getString(
                        context.getContentResolver(),
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
