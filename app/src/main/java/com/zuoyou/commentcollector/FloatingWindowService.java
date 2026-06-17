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

/**
 * Phase 4: 悬浮窗前台服务 — 系统级覆盖层显示 AI 吐槽。
 *
 * <p>两种状态：
 * <ul>
 *   <li><b>气泡态</b>：48dp 半透明橙色圆点，可拖拽到屏幕任意位置</li>
 *   <li><b>展开态</b>：收到 AI 吐槽时展开为白色卡片显示文字，5s 后自动收回</li>
 * </ul>
 *
 * 使用 {@link #showComment(String)} 静态方法（线程安全）从任意位置推送 AI 吐槽。
 */
public class FloatingWindowService extends Service {

    private static final String TAG = "ZuoYouFloat";
    private static final int NOTIFICATION_ID = 1002;
    private static final String CHANNEL_ID = "floating_window";

    // 尺寸常量（dp）
    private static final int BUBBLE_SIZE_DP = 48;
    private static final int CARD_MAX_WIDTH_DP = 240;
    private static final int AUTO_DISMISS_MS = 5000;
    private static final int EDGE_MARGIN_DP = 16;
    private static final int BUBBLE_ALPHA = 200; // 0-255

    /** 静态引用，用于外部线程安全调用。 */
    private static volatile FloatingWindowService sInstance = null;
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    /** 缓冲队列：悬浮窗未运行时暂存 AI 回复，启动后自动刷新 */
    private static final java.util.LinkedList<String> sPendingMessages = new java.util.LinkedList<>();
    private static final int MAX_PENDING = 3;

    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;

    // 视图层次
    private FrameLayout rootView;
    private ImageView bubbleView;
    private View expandedCard;
    private TextView commentText;

    // 状态
    private boolean isExpanded = false;
    private boolean wasExpandedOnDown = false; // 跟踪 ACTION_DOWN 时是否展开态
    private String currentText = "";
    private boolean hasEverShown = false;

    // 拖拽
    private float touchStartX, touchStartY;
    private float viewStartX, viewStartY;
    private boolean isDragging = false;

    // 自动收回
    private final Runnable dismissRunnable = () -> collapseToBubble();
    private int screenWidth;

    // ───── 静态入口 ─────

    /**
     * 从任意线程安全地推送 AI 吐槽到悬浮窗。
     * 如果服务未运行，消息会被缓冲（最多 {@link #MAX_PENDING} 条），
     * 服务启动后自动刷新显示。
     */
    public static void showComment(String text) {
        if (text == null || text.isEmpty()) return;
        sMainHandler.post(() -> {
            if (sInstance != null) {
                sInstance.showCommentInternal(text);
            } else {
                // 缓冲消息，等服务启动后刷新
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

    /**
     * 检查服务是否正在运行。
     */
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

        // 刷新缓冲的消息
        flushPendingMessages();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        // 系统杀死后不再自动重启（需要用户手动开启）
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

        // ── 气泡（橙色圆点） ──
        bubbleView = new ImageView(this);
        FrameLayout.LayoutParams bubbleLp = new FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx);
        bubbleView.setLayoutParams(bubbleLp);

        GradientDrawable bubbleBg = new GradientDrawable();
        bubbleBg.setShape(GradientDrawable.OVAL);
        bubbleBg.setColor(ContextCompat.getColor(this, R.color.orange_primary));
        bubbleBg.setAlpha(BUBBLE_ALPHA);
        bubbleView.setBackground(bubbleBg);
        bubbleView.setElevation(dpToPx(4));
        bubbleView.setImageResource(android.R.drawable.ic_menu_compass);
        bubbleView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        bubbleView.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        bubbleView.setColorFilter(0xFFFFFFFF);
        bubbleView.setVisibility(View.VISIBLE);

        rootView.addView(bubbleView);

        // ── 展开态卡片 ──
        expandedCard = createExpandedCard();
        expandedCard.setVisibility(View.GONE);
        rootView.addView(expandedCard);

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

        // 初始位置：屏幕底部居中
        int statusBarHeight = getStatusBarHeight();
        int bottomMargin = dpToPx(80);
        layoutParams.y = screenHeightPx - bottomMargin - bubbleSizePx - statusBarHeight;
        layoutParams.x = (screenWidth - bubbleSizePx) / 2;

        windowManager.addView(rootView, layoutParams);
        Log.d(TAG, "悬浮窗已创建，初始位置: (" + layoutParams.x + ", " + layoutParams.y + ")");
    }

    private View createExpandedCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(12));
        bg.setColor(0xFFFFFFFF);
        bg.setStroke(dpToPx(1), ContextCompat.getColor(this, R.color.floating_border));
        card.setBackground(bg);
        card.setElevation(dpToPx(8));

        // 文字
        commentText = new TextView(this);
        commentText.setTextSize(14);
        commentText.setTextColor(ContextCompat.getColor(this, R.color.floating_card_text));
        commentText.setMaxLines(3);
        commentText.setLineSpacing(dpToPx(4), 1);
        commentText.setLayoutParams(new LinearLayout.LayoutParams(
                dpToPx(CARD_MAX_WIDTH_DP),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        card.addView(commentText);

        return card;
    }

    // ───── 缓冲消息刷新 ─────

    /**
     * 服务启动后，将缓冲的消息逐条显示。
     */
    private void flushPendingMessages() {
        java.util.List<String> pending;
        synchronized (sPendingMessages) {
            if (sPendingMessages.isEmpty()) return;
            pending = new java.util.ArrayList<>(sPendingMessages);
            sPendingMessages.clear();
        }
        for (String msg : pending) {
            showCommentInternal(msg);
        }
        Log.d(TAG, "已刷新 " + pending.size() + " 条缓冲消息");
    }

    // ───── 显示逻辑 ─────

    private void showCommentInternal(String text) {
        currentText = text;

        // 如果当前是展开态，先取消自动收回，更新文字
        if (isExpanded) {
            sMainHandler.removeCallbacks(dismissRunnable);
            commentText.setText(text);
            sMainHandler.postDelayed(dismissRunnable, AUTO_DISMISS_MS);
            return;
        }

        // 气泡态 → 展开显示文字
        expandToCard(text);
    }

    private void expandToCard(String text) {
        isExpanded = true;
        bubbleView.setVisibility(View.GONE);
        commentText.setText(text);
        expandedCard.setVisibility(View.VISIBLE);

        // 确保卡片不超出屏幕右边界
        expandedCard.measure(
                View.MeasureSpec.makeMeasureSpec(dpToPx(CARD_MAX_WIDTH_DP), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int cardWidth = expandedCard.getMeasuredWidth();
        int overflow = layoutParams.x + cardWidth + dpToPx(EDGE_MARGIN_DP) - screenWidth;
        if (overflow > 0) {
            layoutParams.x = Math.max(dpToPx(EDGE_MARGIN_DP), layoutParams.x - overflow);
            try {
                windowManager.updateViewLayout(rootView, layoutParams);
            } catch (Exception e) {
                Log.w(TAG, "展开卡片更新布局失败", e);
            }
        }

        sMainHandler.postDelayed(dismissRunnable, AUTO_DISMISS_MS);
        Log.d(TAG, "展开显示: " + text);
    }

    private void collapseToBubble() {
        if (!isExpanded) return;
        isExpanded = false;
        expandedCard.setVisibility(View.GONE);
        bubbleView.setVisibility(View.VISIBLE);
        sMainHandler.removeCallbacks(dismissRunnable);
        Log.d(TAG, "收回气泡");
    }

    private void removeOverlay() {
        if (rootView != null && windowManager != null) {
            try {
                windowManager.removeView(rootView);
            } catch (Exception e) {
                Log.w(TAG, "移除悬浮窗时异常（可能已移除）", e);
            }
            rootView = null;
            bubbleView = null;
            expandedCard = null;
            commentText = null;
        }
    }

    // ───── 触摸处理（拖拽 + 点击展开/收起） ─────

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
                wasExpandedOnDown = isExpanded;
                if (isExpanded) {
                    collapseToBubble();
                }
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
                } else if (!isExpanded) {
                    if (currentText != null && !currentText.isEmpty()) {
                        expandToCard(currentText);
                    }
                }
                isDragging = false;
                return true;
            }
        }
        return false;
    }

    /**
     * 拖拽结束后将浮窗吸附到最近的屏幕边缘。
     * 使用 updateViewLayout 直接跳转，避免 View 动画与 WindowManager 坐标冲突。
     */
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
        int leftDist = centerX;
        int rightDist = widthPx - centerX;

        if (leftDist < rightDist) {
            layoutParams.x = dpToPx(EDGE_MARGIN_DP);
        } else {
            layoutParams.x = widthPx - bubbleSizePx - dpToPx(EDGE_MARGIN_DP);
        }

        // 确保不超出顶部/底部边界
        int statusBarH = getStatusBarHeight();
        layoutParams.y = Math.max(statusBarH,
                Math.min(layoutParams.y, heightPx - bubbleSizePx - dpToPx(16)));

        try {
            windowManager.updateViewLayout(rootView, layoutParams);
        } catch (Exception e) {
            Log.w(TAG, "边缘吸附更新布局失败", e);
        }
    }

    // ───── 通知 ─────

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "悬浮窗",
                NotificationManager.IMPORTANCE_LOW);
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
