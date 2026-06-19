package com.zuoyou.commentcollector.feature;

import android.app.Activity;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

/**
 * 主界面 ImageView 手势处理器 — 双击 + 左滑识别。
 * <p>
 * <ul>
 *   <li>双击 → 播放动画 → 进入聊天</li>
 *   <li>左滑（velocityX < -1000）→ 弹出音乐菜单</li>
 * </ul>
 * <p>
 * 防重入：动画播放时忽略双击。
 */
public class MainImageHandler implements View.OnTouchListener {

    private static final String TAG = "MainImageHandler";
    private static final int FLING_THRESHOLD = 1000;

    private final Activity activity;
    private final View imageView;
    private final GestureDetector gestureDetector;
    private final AnimationPlayer animationPlayer;
    private final MusicMenuPopup musicMenuPopup;

    public MainImageHandler(Activity activity, View imageView) {
        this.activity = activity;
        this.imageView = imageView;
        this.animationPlayer = new AnimationPlayer(activity);
        this.musicMenuPopup = new MusicMenuPopup(activity);

        gestureDetector = new GestureDetector(activity, new GestureListener());
        imageView.setOnTouchListener(this);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        return gestureDetector.onTouchEvent(event);
    }

    /**
     * 释放资源（Activity.onDestroy 时调用）。
     */
    public void release() {
        animationPlayer.release();
        musicMenuPopup.dismiss();
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            if (AnimationPlayer.isPlaying()) {
                Log.d(TAG, "动画播放中，忽略双击");
                return true;
            }

            Log.d(TAG, "双击 → 播放动画 → 聊天");

            animationPlayer.play(imageView, () -> {
                // 动画播放完成 → 跳转聊天
                Log.d(TAG, "动画完成，跳转聊天");
                android.content.Intent intent = new android.content.Intent(activity, ChatActivity.class);
                intent.putExtra(ChatActivity.EXTRA_SESSION_ID, -1);
                activity.startActivity(intent);
            });

            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (e1 == null || e2 == null) return false;

            float dx = e2.getX() - e1.getX();
            float dy = e2.getY() - e1.getY();

            // 左滑：velocityX < -1000，且水平位移 > 垂直位移
            if (velocityX < -FLING_THRESHOLD && Math.abs(dx) > Math.abs(dy)) {
                Log.d(TAG, "左滑 → 音乐菜单 (velocityX=" + velocityX + ")");
                musicMenuPopup.show(imageView);
                return true;
            }

            // 右滑：velocityX > 1000，未来可扩展其他功能
            if (velocityX > FLING_THRESHOLD && Math.abs(dx) > Math.abs(dy)) {
                Log.d(TAG, "右滑 (velocityX=" + velocityX + ")");
            }

            return false;
        }
    }
}
