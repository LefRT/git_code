package com.zuoyou.commentcollector;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
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
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    private static final int CARD_MAX_WIDTH_DP = 320;
    private static final int AUTO_DISMISS_MS = 5000;
    private static final int EDGE_MARGIN_DP = 16;

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
    private FrameLayout rootView;
    private ImageView bubbleView;
    private LinearLayout cardContainer;   // 卡片容器（评论列表 / 评价结果共用）
    private LinearLayout commentListView;  // 评论列表
    private TextView evaluationText;       // 评价结果文字
    private TextView titleText;            // 卡片标题

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
                sInstance.showEvaluation(text);
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

        // 根布局
        rootView = new FrameLayout(this);
        rootView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        // ── 气泡 ──
        bubbleView = new ImageView(this);
        FrameLayout.LayoutParams bubbleLp = new FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx);
        bubbleView.setLayoutParams(bubbleLp);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bubbleView.setClipToOutline(true);
            bubbleView.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
        } else {
            GradientDrawable bubbleBg = new GradientDrawable();
            bubbleBg.setShape(GradientDrawable.OVAL);
            bubbleBg.setColor(0xFFFFFFFF);
            bubbleView.setBackground(bubbleBg);
        }
        bubbleView.setElevation(dpToPx(4));
        bubbleView.setImageResource(R.drawable.ic_floating_bubble);
        bubbleView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bubbleView.setVisibility(View.VISIBLE);
        rootView.addView(bubbleView);

        // ── 卡片容器（评论列表 / 评价结果共用） ──
        cardContainer = createCardContainer();
        cardContainer.setVisibility(View.GONE);
        rootView.addView(cardContainer);

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
     * 创建卡片容器（包含标题 + 内容区）。
     */
    private LinearLayout createCardContainer() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(12));
        bg.setColor(0xFFFFFFFF);
        bg.setStroke(dpToPx(1), ContextCompat.getColor(this, R.color.floating_border));
        container.setBackground(bg);
        container.setElevation(dpToPx(8));

        // 标题
        titleText = new TextView(this);
        titleText.setTextSize(12);
        titleText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        titleText.setPadding(0, 0, 0, dpToPx(6));
        container.addView(titleText);

        // 评论列表（动态添加）
        commentListView = new LinearLayout(this);
        commentListView.setOrientation(LinearLayout.VERTICAL);
        container.addView(commentListView);

        // 评价结果文字
        evaluationText = new TextView(this);
        evaluationText.setTextSize(14);
        evaluationText.setTextColor(ContextCompat.getColor(this, R.color.floating_card_text));
        evaluationText.setMaxLines(6);
        evaluationText.setLineSpacing(dpToPx(4), 1);
        evaluationText.setLayoutParams(new LinearLayout.LayoutParams(
                dpToPx(CARD_MAX_WIDTH_DP),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        evaluationText.setVisibility(View.GONE);
        container.addView(evaluationText);

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
            showEvaluation(msg);
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
            // 没有评论，显示提示
            titleText.setText("暂无新评论");
            commentListView.removeAllViews();
            evaluationText.setVisibility(View.GONE);
        } else {
            titleText.setText("最新评论（点击评价）");
            commentListView.removeAllViews();
            evaluationText.setVisibility(View.GONE);

            for (int i = 0; i < comments.size(); i++) {
                Comment c = comments.get(i);
                TextView item = createCommentItem(c, i);
                commentListView.addView(item);
            }
        }

        switchToCard(FloatingState.COMMENT_LIST);
        Log.d(TAG, "显示评论列表，共 " + comments.size() + " 条");
    }

    /**
     * 创建单条评论项（昵称 + 赞数）。
     * 单击 = 固定评价，双击 = 自定义评价。
     */
    private TextView createCommentItem(Comment comment, int index) {
        TextView tv = new TextView(this);
        tv.setTextSize(13);
        tv.setTextColor(ContextCompat.getColor(this, R.color.floating_card_text));
        tv.setPadding(dpToPx(4), dpToPx(6), dpToPx(4), dpToPx(6));
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);

        String display = (index + 1) + ". " + comment.user()
                + " (" + comment.likeCount() + "赞)";
        tv.setText(display);

        tv.setOnClickListener(v -> {
            Log.d(TAG, "用户选择评价: " + comment.user());
            AiService.evaluateCommentDirect(comment);
            showEvaluation("正在评价 " + comment.user() + " 的评论…");
        });

        // 按压效果
        tv.setBackgroundResource(android.R.drawable.list_selector_background);

        return tv;
    }

    /**
     * 显示 AI 评价结果。
     */
    private void showEvaluation(String text) {
        currentAiText = text;
        titleText.setText("AI 评价");
        commentListView.removeAllViews();
        evaluationText.setText(text);
        evaluationText.setVisibility(View.VISIBLE);

        if (currentState != FloatingState.EVALUATION) {
            switchToCard(FloatingState.EVALUATION);
        }

        // 重置自动收回计时
        sMainHandler.removeCallbacks(dismissRunnable);
        sMainHandler.postDelayed(dismissRunnable, AUTO_DISMISS_MS);
        Log.d(TAG, "显示评价: " + text);
    }

    /**
     * 切换到卡片态（隐藏气泡，显示卡片容器）。
     * 状态由调用方设置（COMMENT_LIST 或 EVALUATION）。
     */
    private void switchToCard(FloatingState newState) {
        currentState = newState;
        bubbleView.setVisibility(View.GONE);
        cardContainer.setVisibility(View.VISIBLE);

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
     * 收回到气泡态。
     */
    private void collapseToBubble() {
        if (currentState == FloatingState.BUBBLE) return;
        currentState = FloatingState.BUBBLE;
        cardContainer.setVisibility(View.GONE);
        bubbleView.setVisibility(View.VISIBLE);
        sMainHandler.removeCallbacks(dismissRunnable);
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
            commentListView = null;
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
                .setContentTitle("左右")
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
