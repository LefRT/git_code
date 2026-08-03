package com.zuoyou.commentcollector.feature;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zuoyou.commentcollector.Comment;
import com.zuoyou.commentcollector.R;
import com.zuoyou.commentcollector.ThemeHelper;

import java.util.List;

import androidx.core.content.ContextCompat;

/**
 * 记忆收集面板 — 纯代码构建，白色圆角卡片，280dp 宽。
 * 显示按视频分组的记忆，每个视频可展开查看高赞评论。
 * 由 MainImageHandler 的垂直拖拽控制显隐。
 */
public class MemoryInfoPopup {

    private static final int INITIAL_COMMENTS_SHOW = 5;

    private final Activity activity;
    private LinearLayout contentView;
    private LinearLayout listContainer;
    private boolean built = false;
    private MemoryCollector memoryCollector;

    public MemoryInfoPopup(Activity activity) {
        this.activity = activity;
    }

    /**
     * 构建面板 View（未添加到父布局）。
     */
    public void build() {
        if (built) return;

        contentView = new LinearLayout(activity);
        contentView.setOrientation(LinearLayout.VERTICAL);

        // 主题自适应圆角背景
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(activity, R.color.glass_white));
        bg.setCornerRadius(dpToPx(16));
        bg.setStroke(dpToPx(1), ContextCompat.getColor(activity, R.color.glass_border));
        contentView.setBackground(bg);
        contentView.setElevation(dpToPx(4));

        int padH = dpToPx(16);
        int padV = dpToPx(12);
        contentView.setPadding(padH, padV, padH, padV);

        // 标题行
        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(activity);
        title.setText("记忆收集");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        contentView.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 分割线
        View divider = new View(activity);
        divider.setBackgroundColor(ContextCompat.getColor(activity, R.color.drawer_divider));
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1));
        divLp.topMargin = dpToPx(8);
        divLp.bottomMargin = dpToPx(8);
        contentView.addView(divider, divLp);

        // 可滚动列表
        ScrollView scrollView = new ScrollView(activity);
        listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(listContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 限制最大高度：固定显示约 5 个视频条目（每个约 44dp + 标题区域约 50dp）
        int maxVisibleItems = 5;
        int itemHeightDp = 44;  // 8+8 padding + ~20 text + 8 margin
        int headerAreaDp = 50;  // 标题 + 分割线
        int maxContentDp = headerAreaDp + maxVisibleItems * itemHeightDp;
        int maxContentPx = dpToPx(maxContentDp);
        FrameLayout wrapper = new FrameLayout(activity) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                if (getMeasuredHeight() > maxContentPx) {
                    super.onMeasure(widthMeasureSpec,
                            MeasureSpec.makeMeasureSpec(maxContentPx, MeasureSpec.AT_MOST));
                }
            }
        };
        wrapper.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentView.addView(wrapper, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        refreshList();
        built = true;
    }

    /**
     * 将面板添加到父布局。父布局必须是 FrameLayout。
     */
    public void attachTo(ViewGroup parent) {
        if (contentView == null) build();
        if (contentView.getParent() != null) return;

        int widthPx = dpToPx(280);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        parent.addView(contentView, lp);
    }

    /**
     * 刷新列表数据。
     */
    public void refreshData() {
        memoryCollector = MemoryCollector.getInstance();
        if (built) refreshList();
    }

    private void refreshList() {
        if (listContainer == null) return;
        listContainer.removeAllViews();

        if (memoryCollector == null || memoryCollector.getDescriptionCount() == 0) {
            TextView empty = new TextView(activity);
            empty.setText("暂无记忆");
            empty.setTextSize(12);
            empty.setTextColor(ContextCompat.getColor(activity, R.color.text_hint));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dpToPx(16), 0, dpToPx(16));
            listContainer.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }

        List<MemoryCollector.VideoEntry> entries = memoryCollector.getVideoEntries();
        for (MemoryCollector.VideoEntry entry : entries) {
            addVideoSection(entry);
        }
    }

    private void addVideoSection(MemoryCollector.VideoEntry entry) {
        // Section container
        LinearLayout section = new LinearLayout(activity);
        section.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.bottomMargin = dpToPx(8);
        listContainer.addView(section, sectionLp);

        // Header background
        GradientDrawable headerBg = new GradientDrawable();
        headerBg.setColor(ContextCompat.getColor(activity, R.color.glass_frost));
        headerBg.setCornerRadius(dpToPx(8));

        // Header (clickable)
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        header.setClickable(true);
        header.setFocusable(true);
        header.setBackground(headerBg);

        // Expand indicator
        TextView indicator = new TextView(activity);
        indicator.setText("▶");
        indicator.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        indicator.setTextColor(ContextCompat.getColor(activity, R.color.text_hint));
        indicator.setPadding(0, 0, dpToPx(6), 0);
        header.addView(indicator, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Description text
        TextView descText = new TextView(activity);
        String desc = entry.description;
        descText.setText(desc.length() > 35 ? desc.substring(0, 35) + "…" : desc);
        descText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        descText.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        descText.setSingleLine(true);
        descText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(descText, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Comment count badge
        int commentCount = entry.getCommentCount();
        if (commentCount > 0) {
            TextView badge = new TextView(activity);
            badge.setText(commentCount + "条");
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            badge.setTextColor(ContextCompat.getColor(activity, R.color.blue_primary));
            badge.setPadding(dpToPx(4), 0, dpToPx(4), 0);
            header.addView(badge, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // Delete button
        TextView deleteBtn = new TextView(activity);
        deleteBtn.setText("✕");
        deleteBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        deleteBtn.setTextColor(ContextCompat.getColor(activity, R.color.status_inactive));
        deleteBtn.setPadding(dpToPx(6), dpToPx(2), dpToPx(2), dpToPx(2));
        deleteBtn.setClickable(true);
        deleteBtn.setFocusable(true);
        header.addView(deleteBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Compute descKey for deletion
        String descKey = entry.description.length() > 50
                ? entry.description.substring(0, 50) : entry.description;

        section.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Content container (initially GONE)
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setVisibility(View.GONE);
        content.setPadding(dpToPx(12), dpToPx(4), 0, 0);
        section.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Add comments to content
        List<Comment> comments = entry.getComments();
        for (int i = 0; i < comments.size(); i++) {
            Comment c = comments.get(i);
            TextView commentView = new TextView(activity);
            String text = "💬 " + c.user() + ": " + (c.text() != null ? c.text() : "");
            commentView.setText(text.length() > 60 ? text.substring(0, 60) + "…" : text);
            commentView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            commentView.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
            commentView.setSingleLine(true);
            commentView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams commentLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            commentLp.topMargin = dpToPx(2);
            commentLp.bottomMargin = dpToPx(2);
            content.addView(commentView, commentLp);

            // Hide comments beyond initial show count
            if (i >= INITIAL_COMMENTS_SHOW) {
                commentView.setVisibility(View.GONE);
                commentView.setTag("extra_comment");
            }
        }

        // "查看更多" button (if more than 5 comments)
        if (comments.size() > INITIAL_COMMENTS_SHOW) {
            TextView moreBtn = new TextView(activity);
            moreBtn.setText("查看更多 (" + (comments.size() - INITIAL_COMMENTS_SHOW) + ")");
            moreBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            moreBtn.setTextColor(ContextCompat.getColor(activity, R.color.blue_primary));
            moreBtn.setPadding(0, dpToPx(4), 0, dpToPx(4));
            moreBtn.setClickable(true);
            moreBtn.setFocusable(true);
            content.addView(moreBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            moreBtn.setOnClickListener(v -> {
                // Show all hidden comments
                for (int i = 0; i < content.getChildCount(); i++) {
                    View child = content.getChildAt(i);
                    if ("extra_comment".equals(child.getTag())) {
                        child.setVisibility(View.VISIBLE);
                    }
                }
                moreBtn.setVisibility(View.GONE);
            });
        }

        // Delete button click — remove this video and refresh list
        deleteBtn.setOnClickListener(v -> {
            if (memoryCollector != null) {
                memoryCollector.removeVideo(descKey);
                refreshList();
            }
        });

        // Click handler for header — toggle expand/collapse
        header.setOnClickListener(v -> {
            boolean expanded = content.getVisibility() == View.VISIBLE;
            content.setVisibility(expanded ? View.GONE : View.VISIBLE);
            indicator.setText(expanded ? "▶" : "▼");
        });
    }

    public View getView() {
        return contentView;
    }

    public boolean isBuilt() {
        return built;
    }

    public void release() {
        android.util.Log.d("MemoryInfoPopup", "release()");
        if (contentView != null && contentView.getParent() != null) {
            ((ViewGroup) contentView.getParent()).removeView(contentView);
        }
        contentView = null;
        listContainer = null;
        built = false;
    }

    private int dpToPx(int dp) {
        return (int) (dp * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
