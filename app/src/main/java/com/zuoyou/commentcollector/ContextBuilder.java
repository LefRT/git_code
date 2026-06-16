package com.zuoyou.commentcollector;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * Phase 3: 上下文构建器 — 融合评论 + 截图的时间线管道。
 * <p>
 * 接收来自两个数据源的事件推送，维护内存环形缓冲区，
 * 构建统一的 {@link AppContext} 供外部消费。
 * <p>
 * 实例通过以下方式共享：
 * <ol>
 *   <li>{@link DouyinCommentService#onCreate()} 中创建实例</li>
 *   <li>通过 {@link ScreenCaptureService#setContextBuilder(ContextBuilder)} 注入到截帧服务</li>
 * </ol>
 */
public class ContextBuilder {

    private static final String TAG = "ZuoYouContext";
    private static final int MAX_COMMENTS = 20;
    private static final int MAX_SCREENSHOTS = 5;
    private static final int MAX_TIMELINE = 30;

    private final LinkedList<Comment> recentComments = new LinkedList<>();
    private final LinkedList<String> recentScreenshots = new LinkedList<>();
    private final LinkedList<TimelineEvent> timeline = new LinkedList<>();

    private int totalCommentCount = 0;
    private Listener listener;

    // 缓存时间格式化器（非线程安全，但在单线程推送中没问题）
    private final SimpleDateFormat timestampFormat =
            new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA);

    // ──────────────────────────────────────────────
    //  数据入口
    // ──────────────────────────────────────────────

    /**
     * 推送新提取的评论列表。
     */
    public void pushComments(List<Comment> comments) {
        if (comments == null || comments.isEmpty()) return;

        String now = timestampFormat.format(new Date());
        synchronized (recentComments) {
            for (Comment c : comments) {
                // 追加到环形缓冲区，超限淘汰最旧
                recentComments.addLast(c);
                if (recentComments.size() > MAX_COMMENTS) {
                    recentComments.removeFirst();
                }

                // 生成时间线事件
                String detail = c.user() + ": " + (c.text() != null ? c.text() : "(无文本)");
                pushTimelineEvent(new TimelineEvent("comment", now, detail));
            }
            totalCommentCount += comments.size();
        }

        Log.d(TAG, "推送 " + comments.size() + " 条评论，累计 " + totalCommentCount);
        notifyUpdate();
    }

    /**
     * 推送新截图文件路径。
     */
    public void pushScreenshot(String filePath) {
        if (filePath == null || filePath.isEmpty()) return;

        String now = timestampFormat.format(new Date());
        synchronized (recentScreenshots) {
            recentScreenshots.addLast(filePath);
            if (recentScreenshots.size() > MAX_SCREENSHOTS) {
                recentScreenshots.removeFirst();
            }

            // 生成时间线事件
            String fileName = filePath.substring(filePath.lastIndexOf('/') + 1);
            pushTimelineEvent(new TimelineEvent("screenshot", now, fileName));
        }

        Log.d(TAG, "推送截图: " + filePath);
        notifyUpdate();
    }

    // ──────────────────────────────────────────────
    //  上下文构建
    // ──────────────────────────────────────────────

    /**
     * 构建当前上下文的快照。
     */
    public AppContext buildContext() {
        List<Comment> commentsSnapshot;
        String latestScreenshot;
        List<TimelineEvent> timelineSnapshot;

        synchronized (recentComments) {
            commentsSnapshot = List.copyOf(recentComments);
        }
        synchronized (recentScreenshots) {
            latestScreenshot = recentScreenshots.isEmpty() ? null : recentScreenshots.getLast();
        }
        synchronized (timeline) {
            timelineSnapshot = List.copyOf(timeline);
        }

        return new AppContext(
                "抖音",
                timestampFormat.format(new Date()),
                totalCommentCount,
                commentsSnapshot,
                latestScreenshot,
                timelineSnapshot
        );
    }

    // ──────────────────────────────────────────────
    //  监听器
    // ──────────────────────────────────────────────

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public interface Listener {
        /** 上下文有更新时回调（在推送者线程中调用，需轻量处理） */
        void onContextUpdated(AppContext context);
    }

    private void notifyUpdate() {
        if (listener != null) {
            AppContext context = buildContext();
            Log.d(TAG, context.toJson());
            listener.onContextUpdated(context);
        }
    }

    // ──────────────────────────────────────────────
    //  内部
    // ──────────────────────────────────────────────

    private void pushTimelineEvent(TimelineEvent event) {
        synchronized (timeline) {
            timeline.addLast(event);
            if (timeline.size() > MAX_TIMELINE) {
                timeline.removeFirst();
            }
        }
    }
}
