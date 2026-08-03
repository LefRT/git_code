package com.zuoyou.commentcollector;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * 悬浮窗前台服务 — 三种状态：
 *
 * <ul>
 *   <li><b>气泡态</b>：48dp 圆形图标，可拖拽</li>
 *   <li><b>评论列表态</b>：点击气泡展开，显示最近新增评论者昵称+赞数</li>
 *   <li><b>评价结果态</b>：点击某条评论后显示 AI 评价，5s 自动收回</li>
 * </ul>
 */
public class FloatingWindowService extends Service {

    private static final String TAG = "ZuoYouFloat";
    private static final int NOTIFICATION_ID = 1002;
    private static final String CHANNEL_ID = "floating_window";

    // 尺寸常量（dp）
    private static final int BUBBLE_SIZE_DP = 48;
    /** 卡片最大宽度（dp），长文本自适应上限 */
    private static final int CARD_MAX_WIDTH_DP = 360;
    /** 卡片最小宽度（dp），短文本自适应下限 */
    private static final int CARD_MIN_WIDTH_DP = 200;
    private static final int AUTO_DISMISS_MS = 5000;
    private static final int EDGE_MARGIN_DP = 16;

    /** 阅读速度 ≈ 45ms/字，最短 2.5s，最长 12s */
    private static final int MS_PER_CHAR = 45;
    private static final int MIN_DISPLAY_MS = 2500;
    private static final int MAX_DISPLAY_MS = 12000;

    // 悬浮窗状态
    private enum FloatingState {
        BUBBLE,         // 气泡态
        COMMENT_LIST,   // 评论列表
        EVALUATION      // AI 评价结果
    }

    /** 静态引用 */
    private static volatile FloatingWindowService sInstance = null;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    /** 缓冲队列 */
    private static final java.util.LinkedList<String> sPendingMessages = new java.util.LinkedList<>();
    private static final int MAX_PENDING = 3;

    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;

    // 视图
    private LinearLayout rootView;
    private ImageView bubbleView;
    private LinearLayout cardContainer;   // 卡片容器（评论列表 / 评价结果共用）
    private ScrollView commentScrollView; // 评论列表的 ScrollView
    private LinearLayout commentListView;  // 评论列表
    private ScrollView evaluationScrollView; // 评价结果可滚动容器
    private TextView evaluationText;       // 评价结果文字
    private TextView titleText;            // 卡片标题
    private LinearLayout typingIndicator;  // 打字指示器

    // 状态
    private FloatingState currentState = FloatingState.BUBBLE;
    private String currentAiText = "";
    private int screenWidth;

    // 拖拽
    private float touchStartX, touchStartY;
    private float viewStartX, viewStartY;
    private boolean isDragging = false;
    private boolean wasExpandedOnDown = false;

    // 自动收回
    private final Runnable dismissRunnable = () -> collapseToBubble();

    // ───── 静态入口 ─────

    public static void showComment(String text) {
        if (text == null || text.isEmpty()) return;
        sMainHandler.post(() -> {
            if (sInstance != null) {
                sInstance.showEvaluationResult(text);
            } else {
                synchronized (sPendingMessages) {
                    sPendingMessages.addLast(text);
                    if (sPendingMessages.size() > MAX_PENDING) {
                        sPendingMessages.removeFirst();
                    }
                }
                Log.d(TAG, "悬浮窗未运行，消息已缓冲: " + text);
            }
        });
    }

    public static boolean isRunning() {
        return sInstance != null;
    }

    // ───── 服务生命周期 ─────

    /** 返回带有当前主题配置的 Context，确保颜色资源正确加载 */
    private Context getThemedContext() {
        boolean dark = ThemeHelper.isDarkMode(this);
        Configuration config = new Configuration(getResources().getConfiguration());
        int nightMode = dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightMode;
        return createConfigurationContext(config);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "=== FloatingWindowService 创建 ===");
        sInstance = this;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("AI 陪看已开启"));

        initOverlay();
        updateNotification("AI 陪看已开启");
        flushPendingMessages();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "=== FloatingWindowService 销毁 ===");
        sInstance = null;
        sMainHandler.removeCallbacksAndMessages(null);
        removeOverlay();
        if (Build.VERSION.SDK_INT >= 34) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ───── 悬浮窗构建 ─────

    private void initOverlay() {
        int screenHeightPx;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowMetrics wmMetrics = windowManager.getCurrentWindowMetrics();
            android.graphics.Rect bounds = wmMetrics.getBounds();
            screenWidth = bounds.width();
            screenHeightPx = bounds.height();
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeightPx = metrics.heightPixels;
        }

        int bubbleSizePx = dpToPx(BUBBLE_SIZE_DP);
        int bubbleAreaPx = bubbleSizePx + dpToPx(8);

        // 根布局：垂直排列，气泡在上卡片在下
        rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 气泡区（始终显示） ──
        FrameLayout bubbleArea = new FrameLayout(this);
        LinearLayout.LayoutParams areaLp = new LinearLayout.LayoutParams(bubbleAreaPx, bubbleAreaPx);
        bubbleArea.setLayoutParams(areaLp);

        // 外层发光环
        Context themedCtx = getThemedContext();
        View glowRing = new View(this);
        FrameLayout.LayoutParams glowLp = new FrameLayout.LayoutParams(bubbleSizePx + dpToPx(8), bubbleSizePx + dpToPx(8));
        glowLp.gravity = Gravity.CENTER;
        glowRing.setLayoutParams(glowLp);
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setShape(GradientDrawable.OVAL);
        int primaryColor = ContextCompat.getColor(themedCtx, R.color.blue_primary);
        ringBg.setColor((primaryColor & 0x00FFFFFF) | 0x18000000); // 10% 透明度
        ringBg.setStroke(dpToPx(2), (primaryColor & 0x00FFFFFF) | 0x40000000); // 25% 透明度
        glowRing.setBackground(ringBg);
        glowRing.setVisibility(View.VISIBLE);
        bubbleArea.addView(glowRing);

        // 气泡本身
        bubbleView = new ImageView(this);
        FrameLayout.LayoutParams bubbleLp = new FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx);
        bubbleLp.gravity = Gravity.CENTER;
        bubbleView.setLayoutParams(bubbleLp);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bubbleView.setClipToOutline(true);
            bubbleView.setElevation(dpToPx(6));
            bubbleView.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            // 气泡阴影（冰蓝色阴影更柔和）
            bubbleView.setTranslationZ(dpToPx(4));
        }
        // 白色半透明背景（让角色图更柔和）
        GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setShape(GradientDrawable.OVAL);
        bubbleBg.setColor(0xFFFFFFFF);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            bubbleView.setBackground(bubbleBg);
        } else {
            // API 21+ 用渐变边缘更自然
            android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                    new android.graphics.drawable.Drawable[]{
                            bubbleBg,
                            new GradientDrawable(GradientDrawable.Orientation.BR_TL,
                                    new int[]{0x185BA8C8, 0x00000000})
                    });
            bubbleView.setBackground(layers);
        }
        bubbleView.setImageResource(R.drawable.ic_floating_bubble);
        bubbleView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bubbleView.setVisibility(View.VISIBLE);
        bubbleArea.addView(bubbleView);
        rootView.addView(bubbleArea);

        // 呼吸动画 — 让发光环轻微脉动
        ObjectAnimator pulseAnim = ObjectAnimator.ofPropertyValuesHolder(
                glowRing,
                PropertyValuesHolder.ofFloat("scaleX", 1f, 1.08f),
                PropertyValuesHolder.ofFloat("scaleY", 1f, 1.08f),
                PropertyValuesHolder.ofFloat("alpha", 0.6f, 1.0f)
        );
        pulseAnim.setDuration(2000);
        pulseAnim.setRepeatCount(ObjectAnimator.INFINITE);
        pulseAnim.setRepeatMode(ObjectAnimator.REVERSE);
        pulseAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnim.start();

        // ── 卡片容器（评论列表 / 评价结果共用） ──
        cardContainer = createCardContainer();
        cardContainer.setVisibility(View.GONE);
        // 必须给宽度，否则 MATCH_PARENT 的孩子找不到参考宽度，卡片缩成气泡大小
        rootView.addView(cardContainer, new LinearLayout.LayoutParams(
                dpToPx(CARD_MAX_WIDTH_DP),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 触摸控制 ──
        rootView.setOnTouchListener(this::onRootTouch);

        // ── 窗口参数 ──
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);

        layoutParams.gravity = Gravity.TOP | Gravity.START;

        int statusBarHeight = getStatusBarHeight();
        layoutParams.x = dpToPx(EDGE_MARGIN_DP);
        layoutParams.y = statusBarHeight + (int) ((screenHeightPx - statusBarHeight) * 0.3);

        windowManager.addView(rootView, layoutParams);
        Log.d(TAG, "悬浮窗已创建");
    }

    /**
     * 创建卡片容器（冰蓝主题 + 圆润卡片）。
     */
    private LinearLayout createCardContainer() {
        Context themedCtx = getThemedContext();
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));

        // 卡片背景 — 玻璃态 + 主题自适应边框
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(16));
        bg.setColor(ContextCompat.getColor(themedCtx, R.color.floating_card_bg));
        bg.setStroke(dpToPx(1), ContextCompat.getColor(themedCtx, R.color.floating_border));
        container.setBackground(bg);
        container.setElevation(dpToPx(12));

        // 标题行（KAFU 名字标签 + 状态）
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, 0, 0, dpToPx(8));

        // 可不头像小圆标
        View avatarDot = new View(this);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dpToPx(14), dpToPx(14));
        avatarLp.rightMargin = dpToPx(6);
        avatarDot.setLayoutParams(avatarLp);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColor(ContextCompat.getColor(themedCtx, R.color.blue_primary));
        avatarDot.setBackground(avatarBg);
        titleRow.addView(avatarDot);

        // 标题文字
        titleText = new TextView(this);
        titleText.setTextSize(12);
        titleText.setTextColor(ContextCompat.getColor(themedCtx, R.color.blue_primary));
        titleText.setTypeface(null, Typeface.BOLD);
        titleText.setPadding(0, 0, 0, 0);
        titleRow.addView(titleText);
        container.addView(titleRow);

        // 评论列表（动态添加，可滚动）
        commentScrollView = new ScrollView(this);
        commentScrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx((int) (CARD_MAX_WIDTH_DP / 1.618)))); // 黄金比例 φ ≈ 1.618，超限可滚动
        commentScrollView.setHorizontalScrollBarEnabled(false);
        commentScrollView.setVerticalScrollBarEnabled(false);
        commentListView = new LinearLayout(this);
        commentListView.setOrientation(LinearLayout.VERTICAL);
        commentScrollView.addView(commentListView);
        container.addView(commentScrollView);

        // 评价结果文字（可滚动长文本）
        ScrollView evalScrollView = new ScrollView(this);
        evaluationScrollView = evalScrollView;
        evalScrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx((int) (CARD_MAX_WIDTH_DP / 1.618)))); // 黄金比例 φ ≈ 1.618，超限可滚动
        evalScrollView.setHorizontalScrollBarEnabled(false);
        evalScrollView.setVerticalScrollBarEnabled(true);
        evalScrollView.setVisibility(View.GONE);

        evaluationText = new TextView(this);
        evaluationText.setTextSize(14);
        evaluationText.setTextColor(ContextCompat.getColor(themedCtx, R.color.floating_card_text));
        evaluationText.setLineSpacing(dpToPx(5), 1);
        evaluationText.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        evalScrollView.addView(evaluationText);
        container.addView(evalScrollView);

        // 打字指示器（"正在输入…" 三个跳动点）
        typingIndicator = new LinearLayout(this);
        typingIndicator.setOrientation(LinearLayout.HORIZONTAL);
        typingIndicator.setPadding(0, dpToPx(4), 0, dpToPx(2));
        typingIndicator.setVisibility(View.GONE);
        for (int i = 0; i < 3; i++) {
            TextView dot = new TextView(this);
            dot.setTextSize(18);
            dot.setTextColor(ContextCompat.getColor(themedCtx, R.color.blue_primary));
            dot.setText("·");
            dot.setPadding(dpToPx(2), 0, dpToPx(2), 0);
            if (i > 0) dot.setAlpha(0.4f);
            typingIndicator.addView(dot);
        }
        container.addView(typingIndicator);

        return container;
    }

    // ───── 缓冲消息刷新 ─────

    private void flushPendingMessages() {
        java.util.List<String> pending;
        synchronized (sPendingMessages) {
            if (sPendingMessages.isEmpty()) return;
            pending = new java.util.ArrayList<>(sPendingMessages);
            sPendingMessages.clear();
        }
        for (String msg : pending) {
            showEvaluationResult(msg);
        }
        Log.d(TAG, "已刷新 " + pending.size() + " 条缓冲消息");
    }

    // ───── 状态切换 ─────

    /**
     * 气泡态 → 评论列表态。
     * 从 ContextBuilder 获取最新评论，只显示昵称 + 赞数。
     */
    private void showCommentList() {
        List<Comment> comments = ContextBuilder.getLatestCommentsStatic();
        if (comments.isEmpty()) {
            titleText.setText("暂无新评论");
            commentListView.removeAllViews();
            evaluationScrollView.setVisibility(View.GONE);
            commentScrollView.setVisibility(View.VISIBLE);
        } else {
            titleText.setText("评论列表");
            commentListView.removeAllViews();
            evaluationScrollView.setVisibility(View.GONE);
            commentScrollView.setVisibility(View.VISIBLE);

            for (int i = 0; i < comments.size(); i++) {
                Comment c = comments.get(i);
                View item = createCommentItem(c, i);
                commentListView.addView(item);
            }
        }

        switchToCard(FloatingState.COMMENT_LIST);
        Log.d(TAG, "显示评论列表，共 " + comments.size() + " 条");
    }

    /**
     * 创建单条评论项（头像 + 昵称 + 赞数）。
     * 单击 = 发送 AI 评价。
     */
    private View createCommentItem(Comment comment, int index) {
        Context themedCtx = getThemedContext();
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dpToPx(4), dpToPx(7), dpToPx(4), dpToPx(7));

        // 分割线（非最后一条加底部线）
        if (index > 0) {
            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
            divider.setBackgroundColor(0x085BA8C8); // 极光蓝绿 3% 透明度极淡分割线
            // 没法在 LinearLayout 中加 item 间的分隔线，用背景色暗示
        }

        // 头像圆圈（取用户名的第一个字符或 #）
        TextView avatarView = new TextView(this);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dpToPx(24), dpToPx(24));
        avatarLp.rightMargin = dpToPx(8);
        avatarView.setLayoutParams(avatarLp);
        avatarView.setGravity(Gravity.CENTER);
        avatarView.setTextSize(11);
        avatarView.setTypeface(null, Typeface.BOLD);
        avatarView.setTextColor(0xFFFFFFFF);

        // 从用户名取第一个字符作为头像
        String user = comment.user();
        String initial = (user != null && !user.isEmpty()) ? user.substring(0, 1) : "#";
        avatarView.setText(initial);

        // 随机但稳定的颜色（根据用户名 hash）
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        int[] avatarColors = {
                0xFF5BA8C8, 0xFF3D7A9A, 0xFF8ECDE6,
                0xFF8EADD0, 0xFF7CBAD0, 0xFF5BAABE
        };
        int colorIdx = Math.abs(user.hashCode()) % avatarColors.length;
        avatarBg.setColor(avatarColors[colorIdx]);
        avatarView.setBackground(avatarBg);
        item.addView(avatarView);

        // 右侧文字区
        LinearLayout textArea = new LinearLayout(this);
        textArea.setOrientation(LinearLayout.VERTICAL);
        textArea.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 昵称行
        TextView nameText = new TextView(this);
        nameText.setTextSize(13);
        nameText.setTextColor(ContextCompat.getColor(themedCtx, R.color.floating_card_text));
        nameText.setTypeface(null, Typeface.BOLD);
        nameText.setSingleLine(true);
        nameText.setEllipsize(TextUtils.TruncateAt.END);
        nameText.setText(user);
        textArea.addView(nameText);

        // 点赞行（❤ 赞数）
        TextView likeText = new TextView(this);
        likeText.setTextSize(11);
        likeText.setTextColor(ContextCompat.getColor(themedCtx, R.color.text_hint));
        likeText.setText("❤ " + comment.likeCount());
        textArea.addView(likeText);

        item.addView(textArea);

        // 点击整行 → 评价
        item.setOnClickListener(v -> {
            Log.d(TAG, "用户选择评价: " + comment.user());
            AiService.evaluateCommentDirect(comment);
            showEvaluation("正在评价 " + comment.user() + " 的评论…");
        });

        // 按压反馈
        item.setBackgroundResource(android.R.drawable.list_selector_background);

        return item;
    }

    /**
     * 显示 AI 评价结果（真实 AI 回复，启动自动收回计时）。
     * 根据文字长度动态调整卡片宽度和显示时长。
     */
    private void showEvaluationResult(String text) {
        // 隐藏打字指示器
        typingIndicator.setVisibility(View.GONE);
        typingIndicator.animate().cancel();

        showEvaluation(text);

        // 动态计算显示时间：阅读速度 ≈ 45ms/字 + 2s 基准
        int displayMs = Math.min(
                Math.max(text.length() * MS_PER_CHAR + 2000, MIN_DISPLAY_MS),
                MAX_DISPLAY_MS);
        Log.d(TAG, "显示时长: " + displayMs + "ms (" + text.length() + "字)");

        sMainHandler.removeCallbacks(dismissRunnable);
        sMainHandler.postDelayed(dismissRunnable, displayMs);
    }

    /**
     * 显示评价文本（占位或真实回复）。
     * 不启动自动收回计时 — 由调用方（showEvaluationResult / placeholder 调用）决定。
     */
    private void showEvaluation(String text) {
        currentAiText = text;
        titleText.setText("可不 说");
        commentListView.removeAllViews();

        commentScrollView.setVisibility(View.GONE);

        boolean isPlaceholder = text.startsWith("正在评价");
        if (isPlaceholder) {
            evaluationScrollView.setVisibility(View.GONE);
            typingIndicator.setVisibility(View.VISIBLE);
            typingIndicator.setAlpha(0f);
            typingIndicator.animate().alpha(1f).setDuration(300).start();
            animateTypingDots();
            resizeCardToWidth(dpToPx(CARD_MIN_WIDTH_DP));
        } else {
            typingIndicator.setVisibility(View.GONE);
            evaluationText.setText(text);
            evaluationScrollView.setVisibility(View.VISIBLE);
            evaluationScrollView.scrollTo(0, 0);

            // 根据文本长度自适应卡片宽度
            resizeCardToText(text);

            if (currentState == FloatingState.EVALUATION) {
                evaluationScrollView.setAlpha(0f);
                evaluationScrollView.animate().alpha(1f).setDuration(300).start();
            }
        }

        if (currentState != FloatingState.EVALUATION) {
            switchToCard(FloatingState.EVALUATION);
        }

        Log.d(TAG, "显示评价: " + text);
    }

    // ───── 自适应宽度 ─────

    /**
     * 根据文本长度自动调整卡片宽度和评价区高度。
     * 测量最长行的像素宽度，加上 padding 后 clamp 到 [200dp, 360dp]。
     */
    private void resizeCardToText(String text) {
        String[] lines = text.split("\n");
        android.graphics.Paint paint = evaluationText.getPaint();
        float maxLineWidth = 0;
        for (String line : lines) {
            float w = paint.measureText(line);
            if (w > maxLineWidth) maxLineWidth = w;
        }

        // 卡片宽度 = 最长行宽度 + 左右 padding(28dp) + 滚动条余量(8dp)
        int cardWidthPx = Math.min(
                Math.max((int) maxLineWidth + dpToPx(36), dpToPx(CARD_MIN_WIDTH_DP)),
                dpToPx(CARD_MAX_WIDTH_DP));

        resizeCardToWidth(cardWidthPx);
    }

    /**
     * 将卡片宽度设为指定值（px），高度按黄金比例调整。
     */
    private void resizeCardToWidth(int widthPx) {
        // 更新卡片容器宽度
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cardContainer.getLayoutParams();
        lp.width = widthPx;
        cardContainer.setLayoutParams(lp);

        // 更新评价区高度（黄金比例）
        int scrollHeight = (int) (widthPx / 1.618);
        evaluationScrollView.getLayoutParams().height = scrollHeight;
        evaluationScrollView.requestLayout();
    }

    /**
     * 切换到卡片态（气泡始终显示，卡片从下方展开）。
     */
    private void switchToCard(FloatingState newState) {
        currentState = newState;

        // 卡片从下方展开（淡入 + 轻微位移）
        cardContainer.setTranslationY(dpToPx(8));
        cardContainer.setAlpha(0f);
        cardContainer.setVisibility(View.VISIBLE);
        cardContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();

        // 确保卡片不超出屏幕右边界
        cardContainer.measure(
                View.MeasureSpec.makeMeasureSpec(dpToPx(CARD_MAX_WIDTH_DP), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int cardWidth = cardContainer.getMeasuredWidth();
        int overflow = layoutParams.x + cardWidth + dpToPx(EDGE_MARGIN_DP) - screenWidth;
        if (overflow > 0) {
            layoutParams.x = Math.max(dpToPx(EDGE_MARGIN_DP), layoutParams.x - overflow);
            try {
                windowManager.updateViewLayout(rootView, layoutParams);
            } catch (Exception e) {
                Log.w(TAG, "更新布局失败", e);
            }
        }
    }

    /**
     * 打字点跳动动画（三个点依次淡入淡出）。
     */
    private void animateTypingDots() {
        for (int i = 0; i < typingIndicator.getChildCount(); i++) {
            View dot = typingIndicator.getChildAt(i);
            dot.setAlpha(0.3f);
            dot.animate()
                    .alpha(1f)
                    .setDuration(400)
                    .setStartDelay(i * 200)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> {
                        // 循环：恢复后重新开始
                        if (typingIndicator.getVisibility() == View.VISIBLE) {
                            animateTypingDots();
                        }
                    })
                    .start();
        }
    }

    /**
     * 收回到气泡态（卡片缩放淡出，气泡始终可见）。
     */
    private void collapseToBubble() {
        if (currentState == FloatingState.BUBBLE) return;
        currentState = FloatingState.BUBBLE;
        sMainHandler.removeCallbacks(dismissRunnable);

        // 卡片缩放淡出（气泡始终在顶部可见）
        cardContainer.animate()
                .alpha(0f)
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(200)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    cardContainer.setVisibility(View.GONE);
                    cardContainer.setScaleX(1f);
                    cardContainer.setScaleY(1f);
                })
                .start();

        Log.d(TAG, "收回气泡");
    }

    private void removeOverlay() {
        if (rootView != null && windowManager != null) {
            try {
                windowManager.removeView(rootView);
            } catch (Exception e) {
                Log.w(TAG, "移除悬浮窗异常", e);
            }
            rootView = null;
            bubbleView = null;
            cardContainer = null;
            commentScrollView = null;
            commentListView = null;
            evaluationScrollView = null;
            evaluationText = null;
            titleText = null;
        }
    }

    // ───── 触摸处理 ─────

    private boolean onRootTouch(View v, MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                isDragging = false;
                v.setTranslationX(0);
                v.setTranslationY(0);
                touchStartX = event.getRawX();
                touchStartY = event.getRawY();
                viewStartX = layoutParams.x;
                viewStartY = layoutParams.y;

                // 卡片态：检查触摸是否落在评论项（可交互子 View）上
                if (currentState != FloatingState.BUBBLE) {
                    View clickedChild = findClickableChild(v, (int) event.getRawX(), (int) event.getRawY());
                    if (clickedChild != null) {
                        // 触摸在评论项上 → 不收回，让子 View 的点击事件正常触发
                        wasExpandedOnDown = false;
                        return false;
                    }
                    // 触摸在标题/背景等非交互区域 → 收回到气泡
                    wasExpandedOnDown = true;
                    collapseToBubble();
                    return true;
                }
                wasExpandedOnDown = false;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (wasExpandedOnDown) return true;
                float dx = event.getRawX() - touchStartX;
                float dy = event.getRawY() - touchStartY;
                if (Math.abs(dx) > dpToPx(5) || Math.abs(dy) > dpToPx(5)) {
                    isDragging = true;
                }
                if (isDragging) {
                    layoutParams.x = (int) (viewStartX + dx);
                    layoutParams.y = (int) (viewStartY + dy);
                    try {
                        windowManager.updateViewLayout(rootView, layoutParams);
                    } catch (Exception e) {
                        Log.w(TAG, "拖拽更新布局失败", e);
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (wasExpandedOnDown) {
                    wasExpandedOnDown = false;
                    return true;
                }
                if (isDragging) {
                    snapToEdge();
                } else if (currentState == FloatingState.BUBBLE) {
                    // 点击气泡 → 展开评论列表
                    showCommentList();
                }
                isDragging = false;
                return true;
            }
        }
        return false;
    }

    /**
     * 检查触摸坐标（屏幕坐标）是否落在评论项上。
     * 返回评论项 View，或 null 表示触摸在背景/标题等非交互区域。
     */
    private View findClickableChild(View parent, int rawX, int rawY) {
        if (commentListView == null || commentListView.getChildCount() == 0) return null;
        for (int j = 0; j < commentListView.getChildCount(); j++) {
            View item = commentListView.getChildAt(j);
            if (item.getVisibility() != View.VISIBLE) continue;
            int[] loc = new int[2];
            item.getLocationOnScreen(loc);
            if (rawX >= loc[0] && rawX <= loc[0] + item.getWidth()
                    && rawY >= loc[1] && rawY <= loc[1] + item.getHeight()) {
                return item;
            }
        }
        return null;
    }

    private void snapToEdge() {
        int bubbleSizePx = dpToPx(BUBBLE_SIZE_DP);
        int widthPx, heightPx;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowMetrics wmMetrics = windowManager.getCurrentWindowMetrics();
            android.graphics.Rect bounds = wmMetrics.getBounds();
            widthPx = bounds.width();
            heightPx = bounds.height();
        } else {
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(metrics);
            widthPx = metrics.widthPixels;
            heightPx = metrics.heightPixels;
        }

        int halfBubble = bubbleSizePx / 2;
        int centerX = layoutParams.x + halfBubble;
        if (centerX < widthPx / 2) {
            layoutParams.x = dpToPx(EDGE_MARGIN_DP);
        } else {
            layoutParams.x = widthPx - bubbleSizePx - dpToPx(EDGE_MARGIN_DP);
        }

        int statusBarH = getStatusBarHeight();
        layoutParams.y = Math.max(statusBarH,
                Math.min(layoutParams.y, heightPx - bubbleSizePx - dpToPx(16)));

        try {
            windowManager.updateViewLayout(rootView, layoutParams);
        } catch (Exception e) {
            Log.w(TAG, "边缘吸附失败", e);
        }
    }

    // ───── 通知 ─────

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "悬浮窗", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("FloatingWindowService 的前台通知");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("可不")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    // ───── 工具方法 ─────

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int getStatusBarHeight() {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return resId > 0 ? getResources().getDimensionPixelSize(resId) : 0;
    }
}
