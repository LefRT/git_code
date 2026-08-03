package com.zuoyou.commentcollector.feature;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * 主界面 ImageView 手势处理器 — 双击 + 水平拖拽（音乐面板）+ 垂直拖拽（记忆面板）。
 * <p>
 * 记忆面板模式：图片像盒子盖子，上拉时图片上移+缩小，面板从图片底部被"吐出来"。
 * 图片 z-order 高于面板，静止时图片盖住面板。面板通过 translationY 跟随图片移动，
 * 保持恒定间距，从屏幕底部逐渐显现。
 */
public class MainImageHandler implements View.OnTouchListener {

    private static final String TAG = "MainImageHandler";

    // ─── 水平拖拽（音乐面板）常量 ───
    private static final float OPEN_THRESHOLD = 0.4f;
    private static final int MAX_TRANSLATE_X_DP = 100;
    private static final float MIN_SCALE = 0.71f;
    private static final long ANIM_SNAP_DURATION = 250;
    private static final long ANIM_BACK_DURATION = 200;
    private static final int PANEL_LEFT_MARGIN_DP = 8;

    // ─── 垂直拖拽（记忆面板）常量 ───
    private static final float V_OPEN_THRESHOLD = 0.4f;
    public static final int V_MAX_TRANSLATE_Y_DP = 230;
    public static final float V_MIN_SCALE = 0.4f;

    private static final int EDGE_ZONE_DP = 24;

    public interface Callback {
        void onDoubleTap();
    }

    private final Activity activity;
    private final View imageView;
    private final MusicMenuPopup musicPanel;
    private final MemoryInfoPopup memoryPanel;
    private final GestureDetector gestureDetector;
    private final Callback callback;

    // ─── 水平拖拽状态 ───
    private float startX;
    private float startProgress;
    private boolean isDragging = false;
    private boolean isOpen = false;
    private int maxTranslateXPx;
    private int touchSlop;

    // ─── 垂直拖拽状态 ───
    private float startY;
    private float startVProgress;
    private boolean isVerticalDrag = false;
    private boolean isVerticalOpen = false;
    private float vProgress = 0f;
    private int maxTranslateYPx;
    private ValueAnimator vAnimator;

    // 方向锁定
    private boolean directionLocked = false;

    // 左边缘滑动检测
    private float touchStartX;
    private boolean isEdgeSwipeCandidate = false;
    private int edgeZonePx;

    // ─── 记忆面板定位 ───
    private int panelRestTopMargin = 0;  // 面板完全打开时的 topMargin
    private int panelParentHeight = 0;   // 父布局高度

    // ─── 聊天模式 ───
    private boolean chatMode = false;
    private float chatBaseScale = 1f;
    private int savedTopMargin = 0;

    public MainImageHandler(Activity activity, View imageView,
            MusicMenuPopup musicPanel, MemoryInfoPopup memoryPanel, Callback callback) {
        this.activity = activity;
        this.imageView = imageView;
        this.musicPanel = musicPanel;
        this.memoryPanel = memoryPanel;
        this.callback = callback;

        touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        maxTranslateXPx = dpToPx(MAX_TRANSLATE_X_DP);
        maxTranslateYPx = dpToPx(V_MAX_TRANSLATE_Y_DP);
        edgeZonePx = dpToPx(EDGE_ZONE_DP);

        gestureDetector = new GestureDetector(activity, new DoubleTapDetector());
        imageView.setOnTouchListener(this);
    }

    public void setChatMode(boolean enabled, float baseScale) {
        this.chatMode = enabled;
        this.chatBaseScale = baseScale;
        if (enabled) {
            imageView.setPivotX(0);
            imageView.setPivotY(0);
        } else {
            imageView.setPivotX(imageView.getWidth() / 2f);
            imageView.setPivotY(imageView.getHeight() / 2f);
            // Reset vertical drag state so image isn't stuck offset
            if (vAnimator != null) vAnimator.cancel();
            vProgress = 0f;
            isVerticalOpen = false;
            imageView.setTranslationY(0);
            if (memoryPanel != null && memoryPanel.isBuilt()) {
                memoryPanel.getView().setTranslationY(panelParentHeight - panelRestTopMargin);
            }
        }
    }

    /**
     * 设置记忆面板的定位参数（由 MainActivity 在 post() 中调用）。
     *
     * @param panelTopMargin 面板完全打开时的 topMargin（像素）
     * @param parentHeight   父布局高度（像素）
     */
    public void setMemoryPanelPosition(int panelTopMargin, int parentHeight) {
        this.panelRestTopMargin = panelTopMargin;
        this.panelParentHeight = parentHeight;
    }

    // ═══════════════════════════════════════════════════════
    //  触摸分发
    // ═══════════════════════════════════════════════════════

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        gestureDetector.onTouchEvent(event);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getRawX();
                startX = event.getRawX();
                startY = event.getRawY();
                isDragging = false;
                isVerticalDrag = false;
                directionLocked = false;
                isEdgeSwipeCandidate = (event.getX() < edgeZonePx);

                // Cancel running animations to avoid position jumps
                if (vAnimator != null) vAnimator.cancel();
                imageView.animate().cancel();

                // Capture actual animated position (not binary state)
                startProgress = isOpen ? 1f : 0f;
                startVProgress = vProgress;

                if (chatMode) {
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) imageView.getLayoutParams();
                    savedTopMargin = lp.topMargin;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - startX;
                float dy = event.getRawY() - startY;

                // Left-edge right-swipe → open drawer (before direction lock)
                if (isEdgeSwipeCandidate && !isDragging) {
                    float totalDx = event.getRawX() - touchStartX;
                    if (totalDx > touchSlop) {
                        Log.d(TAG, "左边缘右滑 → 打开抽屉");
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        return false;
                    }
                }

                if (!directionLocked && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    directionLocked = true;
                    isVerticalDrag = Math.abs(dy) > Math.abs(dx);
                    isDragging = true;

                    if (isVerticalDrag) {
                        startVProgress = vProgress;
                        Log.d(TAG, "方向锁定: 垂直");
                    } else {
                        // Capture actual visual progress from current translationX
                        float curTx = imageView.getTranslationX();
                        startProgress = (maxTranslateXPx > 0)
                                ? Math.max(0f, Math.min(1f, curTx / maxTranslateXPx))
                                : (isOpen ? 1f : 0f);
                        Log.d(TAG, "方向锁定: 水平, startProgress=" + startProgress);
                    }
                }

                if (isDragging) {
                    if (isVerticalDrag) handleVerticalMove(dy);
                    else handleHorizontalMove(dx);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDragging) {
                    if (isVerticalDrag) handleVerticalRelease(event.getRawY() - startY);
                    else handleHorizontalRelease(event.getRawX() - startX);
                    isDragging = false;
                }
                isEdgeSwipeCandidate = false;
                directionLocked = false;
                return true;
        }
        return false;
    }

    /**
     * 记忆面板是否处于打开或正在打开的状态。
     * 供 MainActivity 呼吸动画判断是否应启动。
     */
    public boolean isMemoryPanelOpen() {
        return isVerticalOpen || vProgress > 0.01f;
    }

    public void release() {
        Log.d(TAG, "release()");
        if (vAnimator != null) vAnimator.cancel();
        imageView.animate().cancel();
        musicPanel.release();
        memoryPanel.release();
    }

    // ═══════════════════════════════════════════════════════
    //  垂直拖拽（记忆面板）— 盒子盖子模式
    //
    //  静止时：图片居中（z-order 高，盖住面板），面板在屏幕底部附近（不可见）
    //  上拉时：图片 translationY 上移 + scale 缩小，面板 translationY 跟随
    //  面板速度为图片的 80%，间距逐渐增大，面板从屏幕底部逐渐显现
    // ═══════════════════════════════════════════════════════

    private void handleVerticalMove(float dy) {
        // Cancel snap animation if user starts new drag mid-animation
        if (vAnimator != null && vAnimator.isRunning()) vAnimator.cancel();
        // 向上拖时 dy < 0，progress 应该增加 → 用 -dy
        float progress = startVProgress + (-dy) / maxTranslateYPx;
        progress = Math.max(0f, Math.min(1f, progress));
        applyVerticalProgress(progress, chatBaseScale);
    }

    private void handleVerticalRelease(float totalDy) {
        float progress = startVProgress + (-totalDy) / maxTranslateYPx;
        progress = Math.max(0f, Math.min(1f, progress));

        if (!isVerticalOpen && progress >= V_OPEN_THRESHOLD) {
            animateToVProgress(1f, ANIM_SNAP_DURATION);
            isVerticalOpen = true;
        } else if (isVerticalOpen && progress <= (1f - V_OPEN_THRESHOLD)) {
            animateToVProgress(0f, ANIM_SNAP_DURATION);
            isVerticalOpen = false;
        } else {
            animateToVProgress(startVProgress, ANIM_BACK_DURATION);
        }
    }

    /**
     * 实时拖拽驱动。
     * 图片上移+缩小，面板 translationY 跟随上移（速度为图片的 80%）。
     */
    private void applyVerticalProgress(float progress, float fromScale) {
        vProgress = progress;

        float translateY = -maxTranslateYPx * progress;
        float scale = fromScale - (fromScale - fromScale * V_MIN_SCALE) * progress;

        imageView.setTranslationY(translateY);
        imageView.setScaleX(scale);
        imageView.setScaleY(scale);

        if (chatMode) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) imageView.getLayoutParams();
            if (lp.topMargin != savedTopMargin) {
                lp.topMargin = savedTopMargin;
                imageView.setLayoutParams(lp);
            }
        }

        // 面板从底部滑上来（progress=0 隐藏在屏幕下方，progress=1 在图片下方可见）
        if (memoryPanel != null && memoryPanel.isBuilt() && panelParentHeight > 0) {
            float hiddenOffset = panelParentHeight - panelRestTopMargin;
            memoryPanel.getView().setTranslationY(hiddenOffset * (1 - progress));
        }
    }

    /**
     * 松手吸附动画。用 ValueAnimator 统一驱动图片和面板，避免不同步。
     */
    private void animateToVProgress(float targetProgress, long duration) {
        if (vAnimator != null) vAnimator.cancel();

        float startProgress = vProgress;
        float baseScale = chatBaseScale;

        vAnimator = ValueAnimator.ofFloat(startProgress, targetProgress);
        vAnimator.setDuration(duration);
        // 打开用 OvershootInterpolator，关闭用 DecelerateInterpolator（无过冲）
        if (targetProgress > startProgress) {
            vAnimator.setInterpolator(new OvershootInterpolator(2.5f));
        } else {
            vAnimator.setInterpolator(new DecelerateInterpolator(2f));
        }
        vAnimator.addUpdateListener(a -> {
            float p = (float) a.getAnimatedValue();
            applyVerticalProgress(p, baseScale);
        });
        vAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                vProgress = targetProgress;
                applyVerticalProgress(targetProgress, baseScale);
            }
        });

        if (chatMode) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) imageView.getLayoutParams();
            lp.topMargin = savedTopMargin;
            imageView.setLayoutParams(lp);
        }

        vAnimator.start();
    }

    // ═══════════════════════════════════════════════════════
    //  水平拖拽（音乐面板）
    // ═══════════════════════════════════════════════════════

    private void handleHorizontalMove(float dx) {
        float effectiveDx = !isOpen ? Math.max(0, dx) : Math.min(0, dx);
        float progress = startProgress + effectiveDx / maxTranslateXPx;
        progress = Math.max(0f, Math.min(1f, progress));
        applyDragProgress(progress);
    }

    private void handleHorizontalRelease(float totalDx) {
        float effectiveDx = !isOpen ? Math.max(0, totalDx) : Math.min(0, totalDx);
        float progress = startProgress + effectiveDx / maxTranslateXPx;
        progress = Math.max(0f, Math.min(1f, progress));

        if (!isOpen && progress >= OPEN_THRESHOLD) {
            animateToProgress(1f, ANIM_SNAP_DURATION);
            isOpen = true;
        } else if (isOpen && progress <= (1f - OPEN_THRESHOLD)) {
            animateToProgress(0f, ANIM_SNAP_DURATION);
            isOpen = false;
        } else {
            animateToProgress(startProgress, ANIM_BACK_DURATION);
        }
    }

    private float computePanelTranslationX(float progress) {
        View panel = musicPanel.getView();
        int panelWidth = panel.getWidth();
        if (panelWidth == 0) panelWidth = dpToPx(180);
        int leftMargin = dpToPx(PANEL_LEFT_MARGIN_DP);

        float panelStart, panelEnd;
        if (chatMode) {
            int imageWidth = (int) (imageView.getWidth() * chatBaseScale);
            panelStart = -(panelWidth + dpToPx(8));
            panelEnd = imageWidth + leftMargin;
        } else {
            panelStart = -(panelWidth + dpToPx(8));
            panelEnd = leftMargin;
        }
        return panelStart + (panelEnd - panelStart) * progress;
    }

    private void applyDragProgress(float progress) {
        float translateX = maxTranslateXPx * progress;
        float scale = chatBaseScale - (chatBaseScale - chatBaseScale * MIN_SCALE) * progress;

        imageView.setTranslationX(translateX);
        imageView.setScaleX(scale);
        imageView.setScaleY(scale);

        if (chatMode) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) imageView.getLayoutParams();
            if (lp.topMargin != savedTopMargin) {
                lp.topMargin = savedTopMargin;
                imageView.setLayoutParams(lp);
            }
        }

        musicPanel.getView().setTranslationX(computePanelTranslationX(progress));
    }

    private void animateToProgress(float targetProgress, long duration) {
        float targetTranslateX = maxTranslateXPx * targetProgress;
        float targetScale = chatBaseScale - (chatBaseScale - chatBaseScale * MIN_SCALE) * targetProgress;
        float panelTargetTx = computePanelTranslationX(targetProgress);

        if (chatMode) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) imageView.getLayoutParams();
            lp.topMargin = savedTopMargin;
            imageView.setLayoutParams(lp);
        }

        imageView.animate()
                .translationX(targetTranslateX)
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(duration)
                .withEndAction(() -> {
                    if (targetProgress == 0f) imageView.setTranslationX(0);
                })
                .start();

        musicPanel.getView().animate()
                .translationX(panelTargetTx)
                .setDuration(duration)
                .start();
    }

    // ═══════════════════════════════════════════════════════
    //  双击检测
    // ═══════════════════════════════════════════════════════

    private class DoubleTapDetector extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) { return true; }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (isDragging) return false;
            if (AnimationPlayer.isPlaying()) return true;

            // Cancel running animations and reset state directly
            // (don't start close animations — they would fight with enterChat animator)
            if (isOpen) {
                imageView.animate().cancel();
                imageView.setTranslationX(0);
                imageView.setScaleX(chatBaseScale);
                imageView.setScaleY(chatBaseScale);
                isOpen = false;
            }
            if (isVerticalOpen) {
                if (vAnimator != null) vAnimator.cancel();
                vProgress = 0f;
                isVerticalOpen = false;
                imageView.setTranslationY(0);
                if (memoryPanel != null && memoryPanel.isBuilt()) {
                    memoryPanel.getView().setTranslationY(panelParentHeight - panelRestTopMargin);
                }
            }

            if (callback != null) callback.onDoubleTap();
            return true;
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
