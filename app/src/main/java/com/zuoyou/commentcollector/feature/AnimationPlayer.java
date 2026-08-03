package com.zuoyou.commentcollector.feature;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 动画播放器 — 在 ImageView 位置覆盖播放 MP4 动画。
 * <p>
 * 播放流程：
 * <ol>
 *   <li>在 {@code android.R.id.content} 上覆盖一个 SurfaceView，位置 = ImageView 的屏幕坐标</li>
 *   <li>MediaPlayer + setDisplay() 播放 MP4</li>
 *   <li>播放完成/失败 → 移除 SurfaceView → 触发回调</li>
 *   <li>播放期间拦截触摸事件</li>
 * </ol>
 * <p>
 * 资源约定：动画文件 {@code R.raw.animation_intro}（raw），由用户提供。
 */
public class AnimationPlayer {

    private static final String TAG = "AnimationPlayer";

    private static volatile boolean sPlaying = false;  // 全局防重入

    private final Activity activity;
    private SurfaceView surfaceView;
    private MediaPlayer mediaPlayer;
    private Runnable onComplete;
    private boolean released = false;

    public AnimationPlayer(Activity activity) {
        this.activity = activity;
    }

    /**
     * 是否正在播放动画（全局）。
     */
    public static boolean isPlaying() {
        return sPlaying;
    }

    /**
     * 在指定 View 位置播放 MP4 动画。
     *
     * @param anchor     锚点 View（ImageView），SurfaceView 覆盖在其上方
     * @param onComplete 动画播放完成后的回调（在主线程）
     */
    public void play(View anchor, Runnable onComplete) {
        if (sPlaying) {
            Log.w(TAG, "动画已在播放中，忽略");
            return;
        }

        this.onComplete = onComplete;

        // 获取锚点在屏幕上的位置
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int x = loc[0];
        int y = loc[1];
        int w = anchor.getWidth();
        int h = anchor.getHeight();

        Log.d(TAG, "播放动画，锚点位置: (" + x + ", " + y + ") " + w + "x" + h);

        // 创建 SurfaceView
        surfaceView = new SurfaceView(activity);
        surfaceView.setZOrderOnTop(true);
        surfaceView.getHolder().setFormat(PixelFormat.TRANSLUCENT);

        // 拦截触摸事件
        surfaceView.setOnTouchListener((v, event) -> true);

        // 添加到 content 根布局
        FrameLayout content = activity.findViewById(android.R.id.content);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        // 计算相对位置（content 的坐标 = 屏幕坐标 - content 的屏幕坐标）
        int[] contentLoc = new int[2];
        content.getLocationOnScreen(contentLoc);
        lp.leftMargin = x - contentLoc[0];
        lp.topMargin = y - contentLoc[1];
        lp.gravity = Gravity.TOP | Gravity.START;
        content.addView(surfaceView, lp);
        sPlaying = true;

        // 初始化 MediaPlayer
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(activity.getResources().openRawResourceFd(com.zuoyou.commentcollector.R.raw.animation_intro));
            mediaPlayer.setDisplay(surfaceView.getHolder());
            mediaPlayer.setOnPreparedListener(mp -> {
                Log.d(TAG, "MediaPlayer prepared，开始播放");
                mp.start();
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                Log.d(TAG, "动画播放完成");
                cleanup();
                dispatchComplete();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer 错误: what=" + what + ", extra=" + extra);
                cleanup();
                dispatchComplete();
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "初始化 MediaPlayer 失败", e);
            cleanup();
            dispatchComplete();
        }
    }

    /**
     * 释放资源（Activity.onDestroy 时调用）。
     */
    public void release() {
        Log.d(TAG, "release()");
        released = true;
        cleanup();
    }

    private void cleanup() {
        sPlaying = false;
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception e) {
                Log.w(TAG, "释放 MediaPlayer 异常", e);
            }
            mediaPlayer = null;
        }
        if (surfaceView != null) {
            ViewGroup parent = (ViewGroup) surfaceView.getParent();
            if (parent != null) {
                parent.removeView(surfaceView);
            }
            surfaceView = null;
        }
    }

    private void dispatchComplete() {
        if (released) return;
        Runnable cb = onComplete;
        onComplete = null;
        if (cb != null) {
            activity.runOnUiThread(cb);
        }
    }
}
