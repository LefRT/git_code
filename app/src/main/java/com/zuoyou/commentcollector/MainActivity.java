package com.zuoyou.commentcollector;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.Manifest;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.graphics.drawable.GradientDrawable;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.zuoyou.commentcollector.feature.ChatAdapter;
import com.zuoyou.commentcollector.feature.ChatAiService;
import com.zuoyou.commentcollector.feature.ChatSessionManager;
import com.zuoyou.commentcollector.feature.MainImageHandler;
import com.zuoyou.commentcollector.feature.MemoryCollector;
import com.zuoyou.commentcollector.feature.MemoryInfoPopup;
import com.zuoyou.commentcollector.feature.MusicMenuPopup;
import com.zuoyou.commentcollector.feature.MusicPlayer;
import com.zuoyou.commentcollector.feature.ScheduleDataManager;
import com.zuoyou.commentcollector.feature.ScheduleSecretaryService;
import com.zuoyou.commentcollector.feature.StarFieldView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

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

    // 深色模式
    private TextView drawerDarkModeToggle;
    private TextView drawerDarkModeStatus;

    // 时段问候
    private TextView greetingText;
    private TextView greetingMessage;
    private final Random greetingRandom = new Random();

    // 聊天记录
    private LinearLayout chatHistoryContainer;
    private boolean chatSelectMode = false;
    private final java.util.Set<Integer> selectedChatSessions = new java.util.HashSet<>();
    private TextView chatEditButton;
    private TextView chatDeleteButton;
    private TextView newChatButton;

    // 手势处理器
    private MainImageHandler imageHandler;
    private ImageView characterImage;

    // 记忆面板
    private MemoryInfoPopup memoryPanel;

    // 星空背景
    private StarFieldView starFieldView;

    // ─── 内嵌聊天 ───
    private LinearLayout chatContainer;
    private RecyclerView chatMessageList;
    private EditText chatInput;
    private TextView chatSendButton;
    private ChatAdapter chatAdapter;
    private ChatSessionManager chatSessionManager;
    private ChatAiService chatAiService;
    private int chatSessionId = -1;
    private final List<ChatSessionManager.ChatMessage> chatMessages = new ArrayList<>();
    private boolean chatWaitingForReply = false;
    private boolean isChatMode = false;
    private boolean isAnimating = false;
    private static final float CHAT_IMAGE_SCALE = 100f / 280f;  // 280dp → 100dp
    private ValueAnimator imageAnimator;  // stored for cancellation in onDestroy
    private ValueAnimator breathingAnimator;  // idle breathing animation
    private static final long BREATHING_IDLE_DELAY_MS = 5000L;  // 5秒无操作后启动
    private final Handler breathingHandler = new Handler(Looper.getMainLooper());
    private final Runnable breathingStarter = this::startBreathingAnimation;
    private MusicPlayer.MusicListener musicListener;  // stored for removal in onDestroy

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
                            requestScreenCapture();
                        } else {
                            Toast.makeText(this, R.string.overlay_permission_denied, Toast.LENGTH_SHORT).show();
                        }
                        updateDrawerStatus();
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 应用保存的夜间模式（在 setContentView 之前）
        boolean darkSaved = ThemeHelper.isDarkMode(this);
        AppCompatDelegate.setDefaultNightMode(
                darkSaved ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);

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

        // 深色模式
        drawerDarkModeToggle = findViewById(R.id.drawerDarkModeToggle);
        drawerDarkModeStatus = findViewById(R.id.drawerDarkModeStatus);
        updateDarkModeToggle();
        drawerDarkModeToggle.setOnClickListener(v -> {
            boolean isDark = ThemeHelper.isDarkMode(this);
            ThemeHelper.setDarkMode(this, !isDark);
            AppCompatDelegate.setDefaultNightMode(
                    !isDark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        });

        // 时段问候
        greetingText = findViewById(R.id.greetingText);
        greetingMessage = findViewById(R.id.greetingMessage);
        updateGreeting();
        // 点击刷新随机关心语
        findViewById(R.id.greetingContainer).setOnClickListener(v -> updateGreeting());

        // 星空背景（深色模式）
        starFieldView = findViewById(R.id.starFieldView);
        updateStarField();

        // 打开设置抽屉时刷新记忆统计和深色模式状态
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                if (drawerView == MainActivity.this.drawerView) {
                    updateMemoryToggle(memoryCollector);
                    updateDarkModeToggle();
                }
            }
        });

        // 聊天记录
        chatHistoryContainer = findViewById(R.id.chatHistoryContainer);
        chatEditButton = findViewById(R.id.chatEditButton);
        chatDeleteButton = findViewById(R.id.chatDeleteButton);
        newChatButton = findViewById(R.id.newChatButton);
        newChatButton.setOnClickListener(v -> {
            drawerLayout.closeDrawer(chatDrawerView);
            enterChatMode(-1);
        });

        // 编辑按钮：切换多选模式
        chatEditButton.setOnClickListener(v -> toggleChatSelectMode());

        // 删除按钮：删除选中的会话
        chatDeleteButton.setOnClickListener(v -> deleteSelectedChatSessions());

        // 音乐播放器初始化（必须在 MusicMenuPopup 之前）
        MusicPlayer.getInstance().init(this);

        // 双击/拖拽手势 + 音乐面板
        characterImage = findViewById(R.id.characterImage);
        FrameLayout mainWrapper = findViewById(R.id.mainContentWrapper);

        // ── 记忆面板（z-order 最低，在图片下方） ──
        memoryPanel = new MemoryInfoPopup(this);
        memoryPanel.attachTo(mainWrapper);
        // FrameLayout 后添加的子 View z-order 更高，所以移到索引 0 确保在最底层
        View memView = memoryPanel.getView();
        mainWrapper.removeView(memView);
        mainWrapper.addView(memView, 0);

        // 图片居中 + 记忆面板位置（等布局完成后计算）
        characterImage.post(() -> {
            int wrapperH = mainWrapper.getHeight();
            int imgH = characterImage.getHeight();
            int centeredTop = Math.max(0, (wrapperH - imgH) / 2);

            // 图片居中
            ViewGroup.MarginLayoutParams imgLp = (ViewGroup.MarginLayoutParams) characterImage.getLayoutParams();
            imgLp.topMargin = centeredTop;
            characterImage.setLayoutParams(imgLp);

            // 计算记忆面板位置：完全打开时面板在收起图片的下方
            // 图片 pivot 在中心，缩放后视觉底部 = centeredTop + imgH*(1+scale)/2 + translationY
            int maxTranslateY = dpToPx(MainImageHandler.V_MAX_TRANSLATE_Y_DP);
            float vMinScale = MainImageHandler.V_MIN_SCALE;
            int imgVisualBottom = centeredTop
                    + (int) (imgH * (1 + vMinScale) / 2)  // 中心 pivot 缩放后下半部分
                    - maxTranslateY;                        // translationY 上移
            int panelTopMargin = imgVisualBottom + dpToPx(16);

            FrameLayout.LayoutParams memParams = (FrameLayout.LayoutParams) memoryPanel.getView().getLayoutParams();
            memParams.topMargin = panelTopMargin;
            memoryPanel.getView().setLayoutParams(memParams);

            // 初始化：面板隐藏在屏幕下方（translationY 推下去）
            float hiddenOffset = wrapperH - panelTopMargin;
            memoryPanel.getView().setTranslationY(hiddenOffset);

            // 通知手势处理器面板位置
            if (imageHandler != null) {
                imageHandler.setMemoryPanelPosition(panelTopMargin, wrapperH);
            }
        });
        MusicMenuPopup musicPanel = new MusicMenuPopup(this);
        musicPanel.attachTo(mainWrapper);
        FrameLayout.LayoutParams panelLp = (FrameLayout.LayoutParams) musicPanel.getView().getLayoutParams();
        panelLp.gravity = Gravity.CENTER_VERTICAL;
        musicPanel.getView().setLayoutParams(panelLp);
        musicPanel.getView().setTranslationX(-dpToPx(200));

        // 双击回调 → 播放动画 → 进入聊天
        imageHandler = new MainImageHandler(this, characterImage, musicPanel, memoryPanel, () -> {
            if (!isChatMode && !isAnimating) {
                enterChatMode(-1);
            }
        });

        musicListener = new MusicPlayer.MusicListener() {
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
        };
        MusicPlayer.getInstance().addListener(musicListener);

        // 初次启动：延迟 5 秒后开始呼吸
        scheduleBreathingAfterIdle();

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

        // ── 内嵌聊天初始化 ──
        initChat();

        // ── 合并服务开关：悬浮窗 + 屏幕捕获 ──
        drawerServiceToggle.setOnClickListener(v -> {
            if (FloatingWindowService.isRunning() || ScreenCaptureService.isRunning) {
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
                drawerServiceStatus.setText("未开启");
                drawerServiceToggle.setText("开启");
                drawerServiceToggle.setBackgroundResource(R.drawable.bg_button_primary);
                mainHandler.postDelayed(this::updateDrawerStatus, 150);
            } else {
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

        // ── 启动日程秘书服务 ──
        startScheduleSecretaryService();

        // 检查是否从通知跳转需打开秘书聊天
        if (getIntent().getBooleanExtra("open_secretary_chat", false)) {
            openSecretaryChat();
        }
    }

    // ═══════════════════════════════════════════════════════
    // 内嵌聊天
    // ═══════════════════════════════════════════════════════

    private TextView chatTitle;

    private void initChat() {
        chatContainer = findViewById(R.id.chatContainer);
        chatMessageList = findViewById(R.id.chatMessageList);
        chatInput = findViewById(R.id.chatInput);
        chatSendButton = findViewById(R.id.chatSendButton);
        chatTitle = findViewById(R.id.chatTitle);
        ImageButton chatBackButton = findViewById(R.id.chatBackButton);

        chatSessionManager = ChatSessionManager.getInstance(this);
        chatAiService = new ChatAiService(this);

        chatAdapter = new ChatAdapter(chatMessages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        chatMessageList.setLayoutManager(lm);
        chatMessageList.setAdapter(chatAdapter);

        chatSendButton.setOnClickListener(v -> sendChatMessage());
        chatInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendChatMessage();
                return true;
            }
            return false;
        });

        // 返回按钮
        chatBackButton.setOnClickListener(v -> exitChatMode());

        // 聊天记录按钮（打开右侧抽屉）
        TextView chatHistoryBtn = findViewById(R.id.chatHistoryButton);
        chatHistoryBtn.setOnClickListener(v -> {
            refreshChatHistoryList();
            drawerLayout.openDrawer(chatDrawerView);
        });
    }

    /**
     * 进入聊天模式：播放 MP4 → 图片上移 + 聊天展开。
     *
     * @param sessionId 会话 ID（-1 = 新建会话）
     */
    private void enterChatMode(int sessionId) {
        if (isAnimating) return;

        // Already in chat mode — just switch session (no animation needed)
        if (isChatMode) {
            chatAiService.cancel();  // cancel in-flight request from old session
            if (sessionId == -1) {
                chatSessionId = chatSessionManager.createNewSession();
            } else {
                chatSessionId = sessionId;
            }
            chatMessages.clear();
            chatMessages.addAll(chatSessionManager.loadSession(chatSessionId));
            chatAdapter.notifyDataSetChanged();

            boolean sec = isSecretarySession(chatSessionId);
            chatAdapter.setSecretaryMode(sec);
            ScheduleSecretaryService.setSecretaryChatVisible(sec);
            chatTitle.setText(sec ? "📅 日程秘书" : "可不 · 聊天");
            chatAiService.setSystemPrompt(sec ? Constants.SECRETARY_SYSTEM_PROMPT : null);
            chatWaitingForReply = false;
            chatInput.setEnabled(true);
            chatSendButton.setAlpha(1f);
            chatMessageList.post(() -> scrollToChatBottom());
            return;
        }

        isAnimating = true;

        // 加载或创建会话
        if (sessionId == -1) {
            chatSessionId = chatSessionManager.createNewSession();
        } else {
            chatSessionId = sessionId;
        }
        chatMessages.clear();
        chatMessages.addAll(chatSessionManager.loadSession(chatSessionId));
        chatAdapter.notifyDataSetChanged();

        // 秘书会话特殊处理
        boolean isSecretary = isSecretarySession(chatSessionId);
        chatAdapter.setSecretaryMode(isSecretary);
        ScheduleSecretaryService.setSecretaryChatVisible(isSecretary);
        if (isSecretary) {
            chatTitle.setText("📅 日程秘书");
            chatAiService.setSystemPrompt(Constants.SECRETARY_SYSTEM_PROMPT);
        } else {
            chatTitle.setText("可不 · 聊天");
            chatAiService.setSystemPrompt(null);  // 恢复默认
        }

        // TODO: MP4 动画暂跳过，直接展开聊天（AnimationPlayer 回调可能不触发）
        stopBreathingAnimation();
        animateEnterChat();
    }

    /**
     * 图片上移 + 聊天从底部滑入。
     */
    private void animateEnterChat() {
        int imgHeight = characterImage.getHeight();  // 980px (280dp)

        // 菜单按钮顶部 = 16dp（FrameLayout 坐标系）
        int targetTopPx = dpToPx(16);

        // pivotY=0 → 从图片顶部缩放，缩放后底部 = targetTop + 280dp * scale
        int imgFinalBottom = targetTopPx + (int) (imgHeight * CHAT_IMAGE_SCALE);
        int chatTopMargin = imgFinalBottom + dpToPx(8);

        Log.d(TAG, "=== animateEnterChat ===");
        Log.d(TAG, "targetTopPx=" + targetTopPx + ", imgHeight=" + imgHeight);
        Log.d(TAG, "CHAT_IMAGE_SCALE=" + CHAT_IMAGE_SCALE + ", imgFinalBottom=" + imgFinalBottom);
        Log.d(TAG, "chatTopMargin=" + chatTopMargin);

        // 聊天容器定位
        int screenHeight = getScreenHeight();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) chatContainer.getLayoutParams();
        lp.topMargin = chatTopMargin;
        chatContainer.setLayoutParams(lp);
        chatContainer.setTranslationY(screenHeight);
        chatContainer.setVisibility(View.VISIBLE);

        // 设置 pivot 到图片顶部中央（缩放从顶部开始）
        characterImage.setPivotY(0);
        characterImage.setPivotX(characterImage.getWidth() / 2f);

        // 用 ObjectAnimator 同时驱动 topMargin + scale
        // topMargin: 从当前居中位置 → targetTopPx
        ViewGroup.MarginLayoutParams imgLp = (ViewGroup.MarginLayoutParams) characterImage.getLayoutParams();
        int currentTopMargin = imgLp.topMargin;

        // Cancel any in-flight image animator
        if (imageAnimator != null) {
            imageAnimator.cancel();
        }

        imageAnimator = ValueAnimator.ofFloat(0f, 1f);
        imageAnimator.setDuration(350);
        imageAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        imageAnimator.addUpdateListener(anim -> {
            float fraction = anim.getAnimatedFraction();

            // 图片 topMargin 动画
            int newTop = (int) (currentTopMargin + (targetTopPx - currentTopMargin) * fraction);
            ViewGroup.MarginLayoutParams lp2 = (ViewGroup.MarginLayoutParams) characterImage.getLayoutParams();
            if (lp2.topMargin != newTop) {
                lp2.topMargin = newTop;
                characterImage.setLayoutParams(lp2);
            }

            // 图片缩放动画（从顶部缩放）
            float scale = 1f - (1f - CHAT_IMAGE_SCALE) * fraction;
            characterImage.setScaleX(scale);
            characterImage.setScaleY(scale);
        });
        imageAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isAnimating = false;
            }
        });
        imageAnimator.start();

        // 聊天从底部滑入
        chatContainer.animate()
                .translationY(0)
                .setDuration(350)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();

        imageHandler.setChatMode(true, CHAT_IMAGE_SCALE);
        isChatMode = true;
        chatMessageList.post(() -> scrollToChatBottom());
    }

    /**
     * 退出聊天模式：聊天下滑 + 图片回原位。
     */
    private void exitChatMode() {
        if (!isChatMode || isAnimating) return;
        isAnimating = true;

        ScheduleSecretaryService.setSecretaryChatVisible(false);

        // Cancel in-flight AI request
        if (chatAiService != null) {
            chatAiService.cancel();
        }

        // 聊天下滑出屏幕
        chatContainer.animate()
                .translationY(getScreenHeight())
                .setDuration(300)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> chatContainer.setVisibility(View.INVISIBLE))
                .start();

        // 图片回原位：topMargin 恢复居中 + scale 恢复 1.0
        ViewGroup.MarginLayoutParams imgLp = (ViewGroup.MarginLayoutParams) characterImage.getLayoutParams();
        int currentTopMargin = imgLp.topMargin;
        // 计算居中位置：(FrameLayout高度 - 图片高度) / 2
        FrameLayout wrapper = findViewById(R.id.mainContentWrapper);
        int centeredTopMargin = (wrapper.getHeight() - characterImage.getHeight()) / 2;

        // Cancel any in-flight image animator
        if (imageAnimator != null) {
            imageAnimator.cancel();
        }

        imageAnimator = ValueAnimator.ofFloat(0f, 1f);
        imageAnimator.setDuration(300);
        imageAnimator.setInterpolator(new android.view.animation.AccelerateInterpolator());
        imageAnimator.addUpdateListener(anim -> {
            float fraction = anim.getAnimatedFraction();

            // topMargin 动画
            int newTop = (int) (currentTopMargin + (centeredTopMargin - currentTopMargin) * fraction);
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) characterImage.getLayoutParams();
            if (lp.topMargin != newTop) {
                lp.topMargin = newTop;
                characterImage.setLayoutParams(lp);
            }

            // 缩放恢复
            float scale = CHAT_IMAGE_SCALE + (1f - CHAT_IMAGE_SCALE) * fraction;
            characterImage.setScaleX(scale);
            characterImage.setScaleY(scale);
        });
        imageAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                isAnimating = false;
                // Restore pivot AFTER animation completes (was top-left during chat mode)
                characterImage.setPivotY(characterImage.getHeight() / 2f);
                characterImage.setPivotX(characterImage.getWidth() / 2f);
                imageHandler.setChatMode(false, 1f);
                isChatMode = false;
                scheduleBreathingAfterIdle();
                // 恢复封面图
                MusicPlayer.SongInfo song = MusicPlayer.getInstance().getCurrentSong();
                if (song != null) {
                    characterImage.setImageResource(song.coverResId());
                } else {
                    characterImage.setImageResource(R.drawable.home_showcase);
                }
            }
        });
        imageAnimator.start();
    }

    private void sendChatMessage() {
        String text = chatInput.getText().toString().trim();
        if (TextUtils.isEmpty(text) || chatWaitingForReply) return;

        // Capture sessionId at send-time — the callback must persist to the session
        // the user was in when they sent the message, even if they switch sessions later.
        final int savedSessionId = chatSessionId;

        // 保存用户消息
        chatSessionManager.saveMessage(savedSessionId, "user", text);
        chatMessages.add(new ChatSessionManager.ChatMessage("user", text, ""));
        chatAdapter.notifyItemInserted(chatMessages.size() - 1);
        scrollToChatBottom();

        chatInput.setText("");
        setChatWaiting(true);

        // 添加打字指示器
        chatMessages.add(new ChatSessionManager.ChatMessage("typing", "", ""));
        chatAdapter.notifyItemInserted(chatMessages.size() - 1);
        scrollToChatBottom();

        // 调用 AI
        chatAiService.chat(savedSessionId, text, new ChatAiService.ChatCallback() {
            @Override
            public void onResponse(String reply) {
                removeChatTypingIndicator();
                chatSessionManager.saveMessage(savedSessionId, "assistant", reply);
                // Only add to UI if we're still viewing the same session
                if (chatSessionId == savedSessionId) {
                    chatMessages.add(new ChatSessionManager.ChatMessage("assistant", reply, ""));
                    chatAdapter.notifyItemInserted(chatMessages.size() - 1);
                    scrollToChatBottom();
                }
                setChatWaiting(false);
            }

            @Override
            public void onError(String error) {
                removeChatTypingIndicator();
                String errorText = "（出错了: " + error + "）";
                if (chatSessionId == savedSessionId) {
                    chatMessages.add(new ChatSessionManager.ChatMessage("assistant", errorText, ""));
                    chatAdapter.notifyItemInserted(chatMessages.size() - 1);
                    scrollToChatBottom();
                }
                setChatWaiting(false);
                Log.e(TAG, "AI 错误: " + error);
            }
        });
    }

    private void removeChatTypingIndicator() {
        for (int i = chatMessages.size() - 1; i >= 0; i--) {
            if ("typing".equals(chatMessages.get(i).role())) {
                chatMessages.remove(i);
                chatAdapter.notifyItemRemoved(i);
                break;
            }
        }
    }

    private void setChatWaiting(boolean waiting) {
        chatWaitingForReply = waiting;
        chatSendButton.setAlpha(waiting ? 0.5f : 1f);
        chatInput.setEnabled(!waiting);
    }

    private void scrollToChatBottom() {
        if (!chatMessages.isEmpty()) {
            chatMessageList.post(() -> chatMessageList.smoothScrollToPosition(chatMessages.size() - 1));
        }
    }

    private int getScreenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private int getStatusBarHeight() {
        int result = 0;
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            result = getResources().getDimensionPixelSize(resId);
        }
        return result;
    }

    // ═══════════════════════════════════════════════════════
    // 触摸检测（Activity 级别，不干扰子 View 手势）
    // ═══════════════════════════════════════════════════════

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (ev.getAction() == android.view.MotionEvent.ACTION_DOWN) {
            stopBreathingAnimation();
        } else if (ev.getAction() == android.view.MotionEvent.ACTION_UP
                || ev.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
            scheduleBreathingAfterIdle();
        }
        return super.dispatchTouchEvent(ev);
    }

    // ═══════════════════════════════════════════════════════
    // 呼吸动画
    // ═══════════════════════════════════════════════════════

    private void scheduleBreathingAfterIdle() {
        breathingHandler.removeCallbacks(breathingStarter);
        if (!isChatMode && !isAnimating
                && (imageHandler == null || !imageHandler.isMemoryPanelOpen())) {
            breathingHandler.postDelayed(breathingStarter, BREATHING_IDLE_DELAY_MS);
        }
    }

    private void startBreathingAnimation() {
        if (breathingAnimator != null) return;
        if (isChatMode || isAnimating) return;
        if (imageHandler != null && imageHandler.isMemoryPanelOpen()) return;

        float floatDistance = dpToPx(6);  // 上浮 6dp
        float breatheScale = 0.025f;       // 缩放 2.5%

        breathingAnimator = new ValueAnimator();
        breathingAnimator.setFloatValues(0f, 1f);
        breathingAnimator.setDuration(3500);  // 3.5秒一个周期
        breathingAnimator.setRepeatCount(ValueAnimator.INFINITE);
        breathingAnimator.setRepeatMode(ValueAnimator.REVERSE);
        breathingAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        breathingAnimator.addUpdateListener(anim -> {
            float val = (float) anim.getAnimatedValue();
            characterImage.setTranslationY(-floatDistance * val);
            float scale = 1f + breatheScale * val;
            characterImage.setScaleX(scale);
            characterImage.setScaleY(scale);
        });
        breathingAnimator.start();
    }

    private void stopBreathingAnimation() {
        breathingHandler.removeCallbacks(breathingStarter);
        if (breathingAnimator != null) {
            breathingAnimator.cancel();
            breathingAnimator = null;
            // 恢复原始状态
            characterImage.setTranslationY(0f);
            characterImage.setScaleX(1f);
            characterImage.setScaleY(1f);
        }
    }

    // ═══════════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════════

    @Override
    protected void onResume() {
        super.onResume();
        updateDrawerStatus();
        updateGreeting();  // 刷新时段问候（时间可能变化）
        updateStarField();  // 星空背景
        scheduleBreathingAfterIdle();  // 重新开始闲置计时
        refreshChatHistoryList();
        if (memoryPanel != null && memoryPanel.isBuilt()) {
            memoryPanel.refreshData();
        }
    }

    @Override
    public void onBackPressed() {
        if (isChatMode) {
            exitChatMode();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cancel in-flight animations to prevent post-destroy view manipulation
        if (imageAnimator != null) {
            imageAnimator.cancel();
            imageAnimator = null;
        }
        if (breathingAnimator != null) {
            breathingAnimator.cancel();
            breathingAnimator = null;
        }
        breathingHandler.removeCallbacks(breathingStarter);
        if (starFieldView != null) {
            starFieldView.stop();
        }
        // Remove MusicListener to prevent Activity leak via MusicPlayer singleton
        if (musicListener != null) {
            MusicPlayer.getInstance().removeListener(musicListener);
            musicListener = null;
        }
        if (imageHandler != null) {
            imageHandler.release();
        }
        if (chatAiService != null) {
            chatAiService.cancel();
        }
    }

    // ═══════════════════════════════════════════════════════
    // 服务控制
    // ═══════════════════════════════════════════════════════

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

    private void startScheduleSecretaryService() {
        // 确保秘书会话立即创建（不等服务启动），让聊天记录列表能显示
        if (chatSessionManager != null) {
            chatSessionManager.getOrCreateSecretarySession();
        }

        // 检查是否在日程范围内
        ScheduleDataManager dataMgr = ScheduleDataManager.getInstance(this);
        if (!dataMgr.isInRange()) {
            Log.d(TAG, "不在日程范围内，跳过启动秘书服务");
            return;
        }

        if (ScheduleSecretaryService.isRunning()) {
            Log.d(TAG, "秘书服务已在运行");
            return;
        }

        Intent intent = new Intent(this, ScheduleSecretaryService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Log.d(TAG, "日程秘书服务已启动");
    }

    private boolean isSecretarySession(int sessionId) {
        List<ChatSessionManager.SessionInfo> sessions = chatSessionManager.getSessionList();
        for (ChatSessionManager.SessionInfo s : sessions) {
            if (s.id() == sessionId && "secretary".equals(s.type())) return true;
        }
        return false;
    }

    private void openSecretaryChat() {
        int secId = chatSessionManager.getOrCreateSecretarySession();
        if (secId != -1) {
            enterChatMode(secId);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("open_secretary_chat", false)) {
            openSecretaryChat();
        }
    }

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

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    // ═══════════════════════════════════════════════════════
    // 时段问候
    // ═══════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════
    // 星空背景
    // ═══════════════════════════════════════════════════════

    private void updateStarField() {
        if (starFieldView == null) return;
        boolean isDark = ThemeHelper.isDarkMode(this);
        if (isDark) {
            starFieldView.setVisibility(View.VISIBLE);
            starFieldView.start();
        } else {
            starFieldView.stop();
            starFieldView.setVisibility(View.GONE);
        }
    }

    private void updateGreeting() {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);

        String greeting;
        String[] messages;
        if (hour >= 6 && hour < 11) {
            greeting = "早安 ☀️";
            messages = MORNING_MESSAGES;
        } else if (hour >= 11 && hour < 14) {
            greeting = "中午好 🌤️";
            messages = NOON_MESSAGES;
        } else if (hour >= 14 && hour < 18) {
            greeting = "下午好 🌅";
            messages = AFTERNOON_MESSAGES;
        } else if (hour >= 18 && hour < 22) {
            greeting = "晚上好 🌙";
            messages = EVENING_MESSAGES;
        } else {
            greeting = "夜深了 ✨";
            messages = NIGHT_MESSAGES;
        }

        greetingText.setText(greeting);
        greetingMessage.setText(messages[greetingRandom.nextInt(messages.length)]);
    }

    private static final String[] MORNING_MESSAGES = {
            "新的一天开始啦，今天也要加油哦！",
            "早起的鸟儿有虫吃~ 可不可以为你唱首歌？",
            "今天天气怎么样呀？记得吃早饭哦~",
            "早安！可不已经准备好陪你刷抖音啦~",
            "元气满满的一天！可不在这里等你呢~",
            "早上好呀~ 昨晚睡得好吗？",
            "新的一天，新的快乐！让我们开始吧~",
    };

    private static final String[] NOON_MESSAGES = {
            "午安~ 记得吃饭，不要光顾着刷视频哦！",
            "中午啦！可不提醒你休息一下眼睛~",
            "吃过午饭了吗？可不有点想吃团子呢...",
            "午间小憩，刷会抖音放松一下吧~",
            "中午好！今天的午餐好吃吗？",
    };

    private static final String[] AFTERNOON_MESSAGES = {
            "下午茶时间~ 来杯奶茶怎么样？",
            "下午好！可不在这里陪你呢~",
            "下午有点困吧？可不给你讲个笑话？",
            "午后的阳光暖暖的，适合刷抖音~",
            "下午好呀~ 今天过得开心吗？",
            "下午时光，可不陪你一起度过~",
    };

    private static final String[] EVENING_MESSAGES = {
            "晚上好~ 辛苦一天了，来刷会抖音放松一下吧！",
            "晚安时光，可不陪你一起看视频~",
            "晚饭吃了吗？可不今天收集了好多有趣的评论呢！",
            "晚上好呀~ 今天有什么开心的事想分享吗？",
            "夜幕降临，可不在这里陪你~",
            "晚上好！要不要听听今天收集的热评？",
    };

    private static final String[] NIGHT_MESSAGES = {
            "夜深了，早点休息哦~ 可不会一直在这里等你的！",
            "这么晚了还在刷抖音呀？注意休息哦~",
            "熬夜对身体不好哦... 可不有点担心你呢",
            "夜深了~ 明天再来看可不吧！",
            "晚安~ 做个好梦，可不明天见！",
            "深夜了呢，记得盖好被子哦~",
    };

    private void updateMemoryToggle(MemoryCollector collector) {
        boolean enabled = collector.isEnabled();
        drawerMemoryToggle.setText(enabled ? "关闭" : "开启");
        drawerMemoryToggle.setBackgroundResource(enabled ? R.drawable.bg_button_close : R.drawable.bg_button_primary);
        drawerMemoryStatus.setText(enabled
                ? "已采集 " + collector.getDescriptionCount() + " 简介, " + collector.getHighLikeCommentCount() + " 评论"
                : "关闭");
    }

    private void updateDarkModeToggle() {
        boolean isDark = ThemeHelper.isDarkMode(this);
        drawerDarkModeToggle.setText(isDark ? "关闭" : "开启");
        drawerDarkModeToggle.setBackgroundResource(isDark ? R.drawable.bg_button_close : R.drawable.bg_button_secondary);
        drawerDarkModeToggle.setTextColor(isDark
                ? getResources().getColor(R.color.text_on_primary)
                : getResources().getColor(R.color.blue_primary));
        drawerDarkModeStatus.setText(isDark ? "已开启" : "关闭");
    }

    // ═══════════════════════════════════════════════════════
    // 聊天记录侧边栏
    // ═══════════════════════════════════════════════════════

    private void refreshChatHistoryList() {
        if (chatHistoryContainer == null) return;
        chatHistoryContainer.removeAllViews();

        ChatSessionManager sessionMgr = ChatSessionManager.getInstance(this);
        List<ChatSessionManager.SessionInfo> sessions = sessionMgr.getSessionList();

        if (sessions.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无聊天记录");
            empty.setTextSize(14);
            empty.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_hint));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dpToPx(32), 0, 0);
            chatHistoryContainer.addView(empty);
            return;
        }

        // 用 index 作为显示序号（连续编号）
        for (int i = 0; i < sessions.size(); i++) {
            LinearLayout item = createChatHistoryItem(sessions.get(i), i + 1);
            chatHistoryContainer.addView(item);
        }
    }

    private LinearLayout createChatHistoryItem(ChatSessionManager.SessionInfo session, int displayIndex) {
        int padPx = dpToPx(12);
        boolean isCurrentSession = isChatMode && chatSessionId == session.id();
        boolean isSecretary = "secretary".equals(session.type());

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setPadding(padPx, padPx, padPx, padPx);
        // 秘书会话：灰色背景
        if (isSecretary) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(androidx.core.content.ContextCompat.getColor(this, R.color.secretary_chat_bg));
            bg.setCornerRadius(dpToPx(8));
            item.setBackground(bg);
        } else {
            item.setBackgroundResource(R.drawable.selector_drawer_item);
        }
        item.setClickable(true);
        item.setFocusable(true);
        item.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dpToPx(4);
        item.setLayoutParams(lp);

        // 复选框（选择模式下可见，秘书会话隐藏）
        android.widget.CheckBox checkBox = new android.widget.CheckBox(this);
        checkBox.setVisibility((chatSelectMode && !isSecretary) ? View.VISIBLE : View.GONE);
        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(this, R.color.blue_primary)));

        // 当前聊天会话禁止选中
        if (isCurrentSession) {
            checkBox.setEnabled(false);
            checkBox.setAlpha(0.4f);
        }

        // CheckBox 独立监听器（处理选中/取消，不依赖父布局）
        int sessionId = session.id();
        checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isCurrentSession) return;  // 当前会话不允许操作
            if (isChecked) {
                selectedChatSessions.add(sessionId);
            } else {
                selectedChatSessions.remove(sessionId);
            }
            Log.d(TAG, "CheckBox变化: session=" + sessionId + " checked=" + isChecked + " selected=" + selectedChatSessions);
            updateDeleteButton();
        });

        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cbLp.rightMargin = dpToPx(4);
        item.addView(checkBox, cbLp);

        // 内容区域
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView idText = new TextView(this);
        idText.setText(isSecretary ? "📅 日程秘书" : "#" + displayIndex);  // 秘书标题
        idText.setTextSize(14);
        idText.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                isSecretary ? R.color.aurora_teal : R.color.blue_primary));
        idText.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(idText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView timeText = new TextView(this);
        timeText.setText(session.updatedAt());
        timeText.setTextSize(11);
        timeText.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_hint));
        header.addView(timeText);

        content.addView(header, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView preview = new TextView(this);
        preview.setText(session.preview());
        preview.setTextSize(13);
        preview.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary));
        preview.setMaxLines(1);
        preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewLp.topMargin = dpToPx(4);
        content.addView(preview, previewLp);

        // 当前会话标记
        String descText;
        if (isCurrentSession) {
            descText = "当前会话 · " + session.count() + " 条消息";
        } else {
            descText = session.count() + " 条消息";
        }
        TextView countText = new TextView(this);
        countText.setText(descText);
        countText.setTextSize(11);
        countText.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                isCurrentSession ? R.color.blue_primary : R.color.text_hint));
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.topMargin = dpToPx(2);
        content.addView(countText, countLp);

        item.addView(content);

        // 点击行为
        item.setOnClickListener(v -> {
            if (chatSelectMode) {
                // 选择模式：秘书会话不可选
                if (!isSecretary) {
                    checkBox.setChecked(!checkBox.isChecked());
                }
            } else {
                // 普通模式：打开会话
                drawerLayout.closeDrawer(chatDrawerView);
                enterChatMode(session.id());
            }
        });

        // 长按进入选择模式（秘书会话不可长按选中）
        item.setOnLongClickListener(v -> {
            if (!chatSelectMode && !isCurrentSession && !isSecretary) {
                toggleChatSelectMode();
                checkBox.setChecked(true);
            }
            return true;
        });

        return item;
    }

    private void toggleChatSelectMode() {
        chatSelectMode = !chatSelectMode;
        if (!chatSelectMode) {
            selectedChatSessions.clear();
        }
        chatEditButton.setText(chatSelectMode ? "完成" : "删除");
        chatDeleteButton.setVisibility(chatSelectMode ? View.VISIBLE : View.GONE);
        newChatButton.setVisibility(chatSelectMode ? View.GONE : View.VISIBLE);
        updateDeleteButton();
        refreshChatHistoryList();
    }

    private void updateDeleteButton() {
        int count = selectedChatSessions.size();
        chatDeleteButton.setText(count > 0 ? "删除 (" + count + ")" : "删除");
        chatDeleteButton.setAlpha(count > 0 ? 1f : 0.5f);
    }

    private void deleteSelectedChatSessions() {
        Log.d(TAG, "deleteSelectedChatSessions: selectedChatSessions=" + selectedChatSessions);
        if (selectedChatSessions.isEmpty()) {
            Toast.makeText(this, "请先选择要删除的记录", Toast.LENGTH_SHORT).show();
            return;
        }

        // Exit chat mode BEFORE the loop if the current session is being deleted.
        // Calling exitChatMode inside the loop causes async animation conflicts.
        if (isChatMode && selectedChatSessions.contains(chatSessionId)) {
            exitChatMode();
        }

        int count = selectedChatSessions.size();
        ChatSessionManager mgr = ChatSessionManager.getInstance(this);
        for (int sessionId : selectedChatSessions) {
            mgr.deleteSession(sessionId);
        }
        selectedChatSessions.clear();
        toggleChatSelectMode();  // 退出选择模式
        Toast.makeText(this, "已删除 " + count + " 条记录", Toast.LENGTH_SHORT).show();
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}
