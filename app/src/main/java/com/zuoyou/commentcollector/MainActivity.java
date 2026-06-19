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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;

import com.zuoyou.commentcollector.feature.ChatActivity;
import com.zuoyou.commentcollector.feature.MainImageHandler;
import com.zuoyou.commentcollector.feature.MemoryCollector;
import com.zuoyou.commentcollector.feature.MusicPlayer;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ZuoYouMain";

    private DrawerLayout drawerLayout;
    private LinearLayout drawerView;
    private LinearLayout chatDrawerView;

    // 侧边栏合并服务开关
    private TextView drawerServiceStatus;
    private TextView drawerServiceToggle;

    // 记忆收集
    private TextView drawerMemoryToggle;
    private TextView drawerMemoryStatus;

    // 聊天记录
    private LinearLayout chatHistoryContainer;

    // 手势处理器
    private MainImageHandler imageHandler;

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
        chatDrawerView = findViewById(R.id.chatDrawerView);

        // 侧边栏视图绑定
        drawerServiceStatus = findViewById(R.id.drawerServiceStatus);
        drawerServiceToggle = findViewById(R.id.drawerServiceToggle);

        // 记忆收集
        drawerMemoryToggle = findViewById(R.id.drawerMemoryToggle);
        drawerMemoryStatus = findViewById(R.id.drawerMemoryStatus);
        MemoryCollector memoryCollector = new MemoryCollector(this);
        updateMemoryToggle(memoryCollector);
        drawerMemoryToggle.setOnClickListener(v -> {
            boolean newState = !memoryCollector.isEnabled();
            memoryCollector.setEnabled(newState);
            updateMemoryToggle(memoryCollector);
        });

        // 聊天记录
        chatHistoryContainer = findViewById(R.id.chatHistoryContainer);
        TextView newChatButton = findViewById(R.id.newChatButton);
        newChatButton.setOnClickListener(v -> {
            drawerLayout.closeDrawer(chatDrawerView);
            Intent intent = new Intent(this, ChatActivity.class);
            intent.putExtra(ChatActivity.EXTRA_SESSION_ID, -1);
            startActivity(intent);
        });

        // 左侧抽屉：菜单按钮长按打开聊天记录
        // （单击仍打开右侧抽屉，双击图片进入聊天）

        // 双击/左滑手势
        ImageView characterImage = findViewById(R.id.characterImage);
        imageHandler = new MainImageHandler(this, characterImage);

        // 音乐播放器初始化
        MusicPlayer.getInstance().init(this);
        MusicPlayer.getInstance().addListener(new MusicPlayer.MusicListener() {
            @Override
            public void onSongChanged(MusicPlayer.SongInfo song) {
                runOnUiThread(() -> {
                    if (song != null) {
                        characterImage.setImageResource(song.coverResId());
                    } else {
                        characterImage.setImageResource(R.drawable.home_showcase);
                    }
                });
            }

            @Override
            public void onPlayStateChanged(boolean isPlaying) {
                // 播放状态变化时无需特殊处理
            }
        });

        // Android 13+ 需要 POST_NOTIFICATIONS 权限来显示前台通知
        requestNotificationPermission();

        // ── 菜单按钮：单击打开侧边栏，长按导出节点树 ──
        ImageButton menuButton = findViewById(R.id.menuButton);
        menuButton.setOnClickListener(v -> {
            updateDrawerStatus();
            drawerLayout.openDrawer(drawerView);
        });
        menuButton.setOnLongClickListener(v -> {
            DouyinCommentService.requestNodeDump();
            Toast.makeText(this, "已请求节点导出，请切换到抖音", Toast.LENGTH_SHORT).show();
            return true;
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
        refreshChatHistoryList();
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

    /**
     * 更新记忆收集开关 UI。
     */
    private void updateMemoryToggle(MemoryCollector collector) {
        boolean enabled = collector.isEnabled();
        drawerMemoryToggle.setText(enabled ? "关闭" : "开启");
        drawerMemoryToggle.setBackgroundResource(enabled ? R.drawable.bg_button_close : R.drawable.bg_button_primary);
        drawerMemoryStatus.setText(enabled
                ? "已采集 " + collector.getDescriptionCount() + " 简介, " + collector.getHighLikeCommentCount() + " 评论"
                : "关闭");
    }

    /**
     * 刷新左侧抽屉的聊天记录列表。
     */
    private void refreshChatHistoryList() {
        if (chatHistoryContainer == null) return;
        chatHistoryContainer.removeAllViews();

        com.zuoyou.commentcollector.feature.ChatSessionManager sessionMgr =
                new com.zuoyou.commentcollector.feature.ChatSessionManager(this);
        java.util.List<com.zuoyou.commentcollector.feature.ChatSessionManager.SessionInfo> sessions =
                sessionMgr.getSessionList();

        if (sessions.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无聊天记录");
            empty.setTextSize(14);
            empty.setTextColor(0xFFA8C0D0);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dpToPx(32), 0, 0);
            chatHistoryContainer.addView(empty);
            return;
        }

        for (com.zuoyou.commentcollector.feature.ChatSessionManager.SessionInfo session : sessions) {
            LinearLayout item = createChatHistoryItem(session);
            chatHistoryContainer.addView(item);
        }
    }

    private LinearLayout createChatHistoryItem(com.zuoyou.commentcollector.feature.ChatSessionManager.SessionInfo session) {
        int padPx = dpToPx(12);

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(padPx, padPx, padPx, padPx);
        item.setBackgroundResource(R.drawable.selector_drawer_item);
        item.setClickable(true);
        item.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dpToPx(4);
        item.setLayoutParams(lp);

        // 标题行：序号 + 时间
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView idText = new TextView(this);
        idText.setText("#" + session.id());
        idText.setTextSize(14);
        idText.setTextColor(0xFF7EB6D9);
        idText.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(idText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView timeText = new TextView(this);
        timeText.setText(session.updatedAt());
        timeText.setTextSize(11);
        timeText.setTextColor(0xFFA8C0D0);
        header.addView(timeText);

        item.addView(header, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 预览
        TextView preview = new TextView(this);
        preview.setText(session.preview());
        preview.setTextSize(13);
        preview.setTextColor(0xFF6B8A9E);
        preview.setMaxLines(1);
        preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewLp.topMargin = dpToPx(4);
        item.addView(preview, previewLp);

        // 消息数
        TextView countText = new TextView(this);
        countText.setText(session.count() + " 条消息");
        countText.setTextSize(11);
        countText.setTextColor(0xFFA8C0D0);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.topMargin = dpToPx(2);
        item.addView(countText, countLp);

        // 点击打开会话
        item.setOnClickListener(v -> {
            drawerLayout.closeDrawer(chatDrawerView);
            Intent intent = new Intent(this, ChatActivity.class);
            intent.putExtra(ChatActivity.EXTRA_SESSION_ID, session.id());
            startActivity(intent);
        });

        return item;
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (imageHandler != null) {
            imageHandler.release();
        }
    }
}
