package com.zuoyou.commentcollector.feature;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * 音乐菜单弹窗 — 竖向歌曲列表 + 播放/暂停控制。
 * <p>
 * 纯代码构建（不额外 XML），白色圆角卡片风格。
 * 宽度 250dp，每行：封面小图 + 歌名 + 播放指示。
 */
public class MusicMenuPopup {

    private static final int WIDTH_DP = 250;
    private static final int COVER_SIZE_DP = 36;
    private static final int ITEM_HEIGHT_DP = 48;
    private static final int PADDING_DP = 12;

    private final Activity activity;
    private final MusicPlayer musicPlayer;
    private PopupWindow popupWindow;
    private LinearLayout listContainer;
    private MusicPlayer.MusicListener popupListener;

    public MusicMenuPopup(Activity activity) {
        this.activity = activity;
        this.musicPlayer = MusicPlayer.getInstance();
    }

    /**
     * 在指定锚点 View 旁边显示弹窗。
     */
    public void show(View anchor) {
        if (popupWindow != null && popupWindow.isShowing()) {
            dismiss();
            return;
        }

        int widthPx = dpToPx(WIDTH_DP);
        int paddingPx = dpToPx(PADDING_DP);

        // 根布局
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);

        // 圆角白色背景
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dpToPx(12));
        bg.setStroke(dpToPx(1), 0xFFE0E8EE);
        root.setBackground(bg);

        // 标题
        TextView title = new TextView(activity);
        title.setText("音乐");
        title.setTextSize(16);
        title.setTextColor(0xFF1A2A3A);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dpToPx(8));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 歌曲列表
        listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 播放/暂停按钮
        TextView playPauseBtn = new TextView(activity);
        playPauseBtn.setTextSize(14);
        playPauseBtn.setTextColor(0xFFFFFFFF);
        playPauseBtn.setGravity(Gravity.CENTER);
        playPauseBtn.setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFF7EB6D9);
        btnBg.setCornerRadius(dpToPx(20));
        playPauseBtn.setBackground(btnBg);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dpToPx(8);
        btnLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(playPauseBtn, btnLp);

        // 构建歌曲列表
        refreshSongList();

        // 播放/暂停按钮逻辑
        playPauseBtn.setOnClickListener(v -> {
            musicPlayer.togglePlayPause();
            updatePlayPauseText(playPauseBtn);
            refreshSongList();
        });
        updatePlayPauseText(playPauseBtn);

        // 监听播放状态变化（使用 addListener，dismiss 时移除）
        popupListener = new MusicPlayer.MusicListener() {
            @Override
            public void onSongChanged(MusicPlayer.SongInfo song) {
                activity.runOnUiThread(() -> refreshSongList());
            }

            @Override
            public void onPlayStateChanged(boolean isPlaying) {
                activity.runOnUiThread(() -> {
                    updatePlayPauseText(playPauseBtn);
                    refreshSongList();
                });
            }
        };
        musicPlayer.addListener(popupListener);

        // 创建 PopupWindow
        popupWindow = new PopupWindow(root, widthPx, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setOutsideTouchable(true);
        popupWindow.setElevation(dpToPx(8));

        // 显示在锚点下方
        popupWindow.showAsDropDown(anchor, 0, dpToPx(8));
    }

    /**
     * 关闭弹窗。
     */
    public void dismiss() {
        if (popupListener != null) {
            musicPlayer.removeListener(popupListener);
            popupListener = null;
        }
        if (popupWindow != null && popupWindow.isShowing()) {
            popupWindow.dismiss();
        }
        popupWindow = null;
    }

    // ─── 内部 ───

    private void refreshSongList() {
        listContainer.removeAllViews();
        MusicPlayer.SongInfo[] songs = musicPlayer.getAllSongs();
        int currentIdx = musicPlayer.getCurrentIndex();

        for (int i = 0; i < songs.length; i++) {
            MusicPlayer.SongInfo song = songs[i];
            boolean isCurrent = (i == currentIdx);

            LinearLayout item = createSongItem(song, isCurrent);
            listContainer.addView(item, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private LinearLayout createSongItem(MusicPlayer.SongInfo song, boolean isCurrent) {
        int coverSizePx = dpToPx(COVER_SIZE_DP);
        int itemHeightPx = dpToPx(ITEM_HEIGHT_DP);

        LinearLayout item = new LinearLayout(activity);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4));
        item.setMinimumHeight(itemHeightPx);

        // 点击背景
        GradientDrawable pressBg = new GradientDrawable();
        pressBg.setColor(0x1A7EB6D9);
        pressBg.setCornerRadius(dpToPx(8));
        item.setBackground(pressBg);
        item.setClickable(true);
        item.setFocusable(true);

        // 封面小图
        ImageView cover = new ImageView(activity);
        cover.setImageResource(song.coverResId());
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(coverSizePx, coverSizePx);
        coverLp.rightMargin = dpToPx(10);
        item.addView(cover, coverLp);

        // 歌名 + 歌手
        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleView = new TextView(activity);
        titleView.setText(song.title());
        titleView.setTextSize(14);
        titleView.setTextColor(isCurrent ? 0xFF7EB6D9 : 0xFF1A2A3A);
        titleView.setTypeface(null, isCurrent ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        titleView.setMaxLines(1);
        textCol.addView(titleView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView artistView = new TextView(activity);
        artistView.setText(song.artist());
        artistView.setTextSize(11);
        artistView.setTextColor(0xFF6B8A9E);
        artistView.setMaxLines(1);
        textCol.addView(artistView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        item.addView(textCol);

        // 播放指示
        if (isCurrent) {
            TextView indicator = new TextView(activity);
            indicator.setText(musicPlayer.isPlaying() ? "▶" : "❚❚");
            indicator.setTextSize(14);
            indicator.setTextColor(0xFF7EB6D9);
            indicator.setPadding(dpToPx(8), 0, 0, 0);
            item.addView(indicator);
        }

        // 点击播放
        item.setOnClickListener(v -> {
            musicPlayer.play(song.index());
            refreshSongList();
        });

        return item;
    }

    private void updatePlayPauseText(TextView btn) {
        btn.setText(musicPlayer.isPlaying() ? "⏸ 暂停" : "▶ 播放");
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, activity.getResources().getDisplayMetrics());
    }
}
