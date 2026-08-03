package com.zuoyou.commentcollector.feature;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.zuoyou.commentcollector.R;

/**
 * 音乐面板 — 抽屉式侧滑面板，显示在角色图片正左边。
 * <p>
 * 纯代码构建（不额外 XML），白色圆角卡片风格。
 * 宽度 180dp，每行：封面小图 + 歌名（最多 4 字，超出跑马灯）+ 播放指示。
 * <p>
 * 不再使用 PopupWindow，而是构建一个 View 供外部添加到布局中，
 * 通过 translationX 控制显隐（由 MainImageHandler 拖拽驱动）。
 */
public class MusicMenuPopup {

    private static final int WIDTH_DP = 180;
    private static final int COVER_SIZE_DP = 32;
    private static final int ITEM_HEIGHT_DP = 40;
    private static final int PADDING_DP = 10;
    private static final int MAX_TITLE_CHARS = 4;

    private final Activity activity;
    private final MusicPlayer musicPlayer;

    private LinearLayout panelView;
    private LinearLayout listContainer;
    private TextView modeBtn;
    private MusicPlayer.MusicListener musicListener;

    public MusicMenuPopup(Activity activity) {
        this.activity = activity;
        this.musicPlayer = MusicPlayer.getInstance();
    }

    /**
     * 构建面板 View 并返回（仅构建一次）。
     */
    public View getView() {
        if (panelView != null) return panelView;
        buildPanel();
        return panelView;
    }

    /**
     * 面板是否已构建。
     */
    public boolean isBuilt() {
        return panelView != null;
    }

    /**
     * 将面板添加到指定父布局。
     */
    public void attachTo(ViewGroup parent) {
        if (panelView == null) buildPanel();
        if (panelView.getParent() == null) {
            parent.addView(panelView);
        }
    }

    /**
     * 释放资源（Activity.onDestroy 时调用）。
     */
    public void release() {
        if (musicListener != null) {
            musicPlayer.removeListener(musicListener);
            musicListener = null;
            android.util.Log.d("MusicMenuPopup", "release() — listener removed");
        }
    }

    // ─── 构建面板 ───

    private void buildPanel() {
        int widthPx = dpToPx(WIDTH_DP);
        int paddingPx = dpToPx(PADDING_DP);

        // 根布局
        panelView = new LinearLayout(activity);
        panelView.setOrientation(LinearLayout.VERTICAL);
        panelView.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
        panelView.setElevation(dpToPx(8));

        // 圆角主题自适应背景
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(activity, R.color.glass_white));
        bg.setCornerRadius(dpToPx(12));
        bg.setStroke(dpToPx(1), ContextCompat.getColor(activity, R.color.glass_border));
        panelView.setBackground(bg);

        // 固定宽度
        panelView.setLayoutParams(new LinearLayout.LayoutParams(
                widthPx, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题行：标题（左）+ 模式按钮（右）
        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(0, 0, 0, dpToPx(6));
        panelView.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题
        TextView title = new TextView(activity);
        title.setText("音乐");
        title.setTextSize(14);
        title.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 模式切换按钮
        modeBtn = new TextView(activity);
        modeBtn.setTextSize(11);
        modeBtn.setTextColor(ContextCompat.getColor(activity, R.color.blue_primary));
        modeBtn.setGravity(Gravity.CENTER);
        modeBtn.setPadding(dpToPx(6), dpToPx(3), dpToPx(6), dpToPx(3));
        GradientDrawable modeBtnBg = new GradientDrawable();
        modeBtnBg.setColor((ContextCompat.getColor(activity, R.color.blue_primary) & 0x00FFFFFF) | 0x1A000000);
        modeBtnBg.setCornerRadius(dpToPx(10));
        modeBtn.setBackground(modeBtnBg);
        titleRow.addView(modeBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        updateModeText();

        // 歌曲列表
        listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        panelView.addView(listContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 模式按钮逻辑
        modeBtn.setOnClickListener(v -> {
            musicPlayer.cyclePlayMode();
            updateModeText();
        });

        // 监听播放状态变化
        musicListener = new MusicPlayer.MusicListener() {
            @Override
            public void onSongChanged(MusicPlayer.SongInfo song) {
                activity.runOnUiThread(() -> refreshSongList());
            }

            @Override
            public void onPlayStateChanged(boolean isPlaying) {
                activity.runOnUiThread(() -> refreshSongList());
            }

            @Override
            public void onPlayModeChanged(MusicPlayer.PlayMode mode) {
                activity.runOnUiThread(() -> updateModeText());
            }
        };
        musicPlayer.addListener(musicListener);

        // 构建歌曲列表
        refreshSongList();
    }

    // ─── 歌曲列表 ───

    private void refreshSongList() {
        if (listContainer == null) return;
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
        item.setPadding(dpToPx(4), dpToPx(3), dpToPx(4), dpToPx(3));
        item.setMinimumHeight(itemHeightPx);

        // 点击背景
        GradientDrawable pressBg = new GradientDrawable();
        pressBg.setColor((ContextCompat.getColor(activity, R.color.blue_primary) & 0x00FFFFFF) | 0x1A000000);
        pressBg.setCornerRadius(dpToPx(8));
        item.setBackground(pressBg);
        item.setClickable(true);
        item.setFocusable(true);

        // 封面小图
        ImageView cover = new ImageView(activity);
        cover.setImageResource(song.coverResId());
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(coverSizePx, coverSizePx);
        coverLp.rightMargin = dpToPx(8);
        item.addView(cover, coverLp);

        // 歌名（最多 4 字，超出跑马灯）
        TextView titleView = new TextView(activity);
        titleView.setTextSize(13);
        titleView.setTextColor(ContextCompat.getColor(activity, isCurrent ? R.color.blue_primary : R.color.text_primary));
        titleView.setTypeface(null, isCurrent ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        titleView.setMarqueeRepeatLimit(-1);  // 无限循环
        titleView.setHorizontallyScrolling(true);
        titleView.setSelected(true);  // 触发 marquee
        titleView.setFocusable(true);
        titleView.setFocusableInTouchMode(true);

        // 截取前 4 个字显示，超出部分由 marquee 滚动
        String fullTitle = song.title();
        titleView.setText(fullTitle);
        titleView.setMaxWidth(dpToPx(MAX_TITLE_CHARS * 14));  // 约 4 个字的宽度

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        item.addView(titleView, titleLp);

        // 播放指示
        if (isCurrent) {
            TextView indicator = new TextView(activity);
            indicator.setText(musicPlayer.isPlaying() ? "❚❚" : "▶");
            indicator.setTextSize(12);
            indicator.setTextColor(ContextCompat.getColor(activity, R.color.blue_primary));
            indicator.setPadding(dpToPx(4), 0, 0, 0);
            item.addView(indicator);
        }

        // 点击：当前歌曲→暂停/恢复，其他歌曲→切歌
        item.setOnClickListener(v -> {
            if (musicPlayer.getCurrentIndex() == song.index() && musicPlayer.isPlaying()) {
                musicPlayer.pause();
            } else {
                musicPlayer.play(song.index());
            }
            refreshSongList();
        });

        return item;
    }

    // ─── 更新 UI ───

    private void updateModeText() {
        if (modeBtn == null) return;
        switch (musicPlayer.getPlayMode()) {
            case SEQUENTIAL -> modeBtn.setText("🔁顺序");
            case RANDOM -> modeBtn.setText("🔀随机");
            case SINGLE_LOOP -> modeBtn.setText("🔂单曲");
        }
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, activity.getResources().getDisplayMetrics());
    }
}
