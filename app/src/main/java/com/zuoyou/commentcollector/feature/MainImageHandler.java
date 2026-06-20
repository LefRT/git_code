package com.zuoyou.commentcollector.feature;

import android.app.Activity;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

/**
 * 主界面 ImageView 手势处理器 — 双击 + 拖拽拉出音乐面板 + 左滑开抽屉。
 * <p>
 * 支持聊天模式：图片缩小时，拖拽和音乐面板跟随图片当前位置。
 */
public class MainImageHandler implements View.OnTouchListener {

    private static final String TAG = "MainImageHandler";

    private static final float OPEN_THRESHOLD = 0.4f;
    private static final int MAX_TRANSLATE_X_DP = 100;
    private static final float MIN_SCALE = 0.71f;
    private static final long ANIM_SNAP_DURATION = 250;
    private static final long ANIM_BACK_DURATION = 200;
    private static final int PANEL_LEFT_MARGIN_DP = 8;
    private static final int EDGE_ZONE_DP = 24;

    public interface Callback {
        void onDoubleTap();
    }

    private final Activity activity;
    private final View imageView;
    private final MusicMenuPopup musicPanel;
    private final GestureDetector gestureDetector;
    private final Callback callback;

    // 拖拽状态
    private float startX;
    private float startProgress;
    private boolean isDragging = false;
    private boolean isOpen = false;
    private int maxTranslateXPx;
    private int touchSlop;
    private int edgeZonePx;

    // 左边缘滑动检测
    private float touchStartX;
    private boolean isEdgeSwipeCandidate = false;

    // 聊天模式
    private boolean chatMode = false;
    private float chatBaseScale = 1f;
    private int savedTopMargin = 0;

    public MainImageHandler(Activity activity, View imageView, MusicMenuPopup musicPanel, Callback callback) {
        this.activity = activity;
        this.imageView = imageView;
        this.musicPanel = musicPanel;
        this.callback = callback;

        touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        maxTranslateXPx = dpToPx(MAX_TRANSLATE_X_DP);
        edgeZonePx = dpToPx(EDGE_ZONE_DP);

        gestureDetector = new GestureDetector(activity, new DoubleTapDetector());
        imageView.setOnTouchListener(this);
    }

    /**
     * 设置聊天模式。
     *
     * @param enabled    是否进入聊天模式
     * @param baseScale  聊天模式下图片的基准缩放比例
     */
    public void setChatMode(boolean enabled, float baseScale) {
        this.chatMode = enabled;
        this.chatBaseScale = baseScale;
        if (enabled) {
            // 聊天模式：缩放从左上角开始
            imageView.setPivotX(0);
            imageView.setPivotY(0);
        } else {
            // 默认模式：缩放从中心开始
            imageView.setPivotX(imageView.getWidth() / 2f);
            imageView.setPivotY(imageView.getHeight() / 2f);
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        gestureDetector.onTouchEvent(event);

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getRawX();
                startX = event.getRawX();
                startProgress = isOpen ? 1f : 0f;
                isDragging = false;
                isEdgeSwipeCandidate = (event.getX() < edgeZonePx);

                // 聊天模式：保存当前 topMargin，拖拽中保持不变
                if (chatMode) {
                    ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) imageView.getLayoutParams();
                    savedTopMargin = lp.topMargin;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - startX;

                if (isEdgeSwipeCandidate && !isDragging) {
                    float totalDx = event.getRawX() - touchStartX;
                    if (totalDx > touchSlop) {
                        Log.d(TAG, "左边缘右滑 → 打开抽屉");
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        return false;
                    }
                }

                if (!isDragging && Math.abs(dx) > touchSlop) {
                    isDragging = true;
                }

                if (isDragging) {
                    float effectiveDx;
                    if (!isOpen) {
                        effectiveDx = Math.max(0, dx);
                    } else {
                        effectiveDx = Math.min(0, dx);
                    }

                    float progress = startProgress + effectiveDx / maxTranslateXPx;
                    progress = Math.max(0f, Math.min(1f, progress));
                    applyDragProgress(progress);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDragging) {
                    float totalDx = event.getRawX() - startX;
                    float effectiveDx;
                    if (!isOpen) {
                        effectiveDx = Math.max(0, totalDx);
                    } else {
                        effectiveDx = Math.min(0, totalDx);
                    }
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
                    isDragging = false;
                }
                isEdgeSwipeCandidate = false;
                return true;
        }
        return false;
    }

    public void release() {
        musicPanel.release();
    }

    // ─── 拖拽驱动 ───

    /**
     * 计算音乐面板的 translationX（共享逻辑，避免 applyDragProgress / animateToProgress 分叉）。
     */
    private float computePanelTranslationX(float progress) {
        View panel = musicPanel.getView();
        int panelWidth = panel.getWidth();
        if (panelWidth == 0) {
            panelWidth = dpToPx(180);
        }
        int leftMargin = dpToPx(PANEL_LEFT_MARGIN_DP);

        float panelStart;
        float panelEnd;
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
        // 图片位移和缩放（从当前基准值开始）
        float translateX = maxTranslateXPx * progress;
        float scale = chatBaseScale - (chatBaseScale - chatBaseScale * MIN_SCALE) * progress;

        imageView.setTranslationX(translateX);
        imageView.setScaleX(scale);
        imageView.setScaleY(scale);

        // 聊天模式：保持 topMargin 不变（防止布局抖动）
        if (chatMode) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) imageView.getLayoutParams();
            if (lp.topMargin != savedTopMargin) {
                lp.topMargin = savedTopMargin;
                imageView.setLayoutParams(lp);
            }
        }

        // 音乐面板位置（跟随图片当前位置）
        musicPanel.getView().setTranslationX(computePanelTranslationX(progress));
    }

    private void animateToProgress(float targetProgress, long duration) {
        float targetTranslateX = maxTranslateXPx * targetProgress;
        float targetScale = chatBaseScale - (chatBaseScale - chatBaseScale * MIN_SCALE) * targetProgress;
        float panelTargetTx = computePanelTranslationX(targetProgress);

        // 聊天模式：动画中保持 topMargin
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
                    // 动画结束：如果回到关闭状态，重置 translationX
                    if (targetProgress == 0f) {
                        imageView.setTranslationX(0);
                    }
                })
                .start();

        musicPanel.getView().animate()
                .translationX(panelTargetTx)
                .setDuration(duration)
                .start();
    }

    // ─── 双击检测 ───

    private class DoubleTapDetector extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (isDragging) return false;
            if (AnimationPlayer.isPlaying()) {
                Log.d(TAG, "动画播放中，忽略双击");
                return true;
            }

            Log.d(TAG, "双击 → 回调 MainActivity");

            if (isOpen) {
                animateToProgress(0f, ANIM_BACK_DURATION);
                isOpen = false;
            }

            if (callback != null) {
                callback.onDoubleTap();
            }

            return true;
        }
    }

    // ─── 工具 ───

    private int dpToPx(int dp) {
        return (int) (dp * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
