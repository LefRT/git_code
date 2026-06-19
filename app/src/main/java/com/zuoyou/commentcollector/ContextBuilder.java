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
    private static final int MAX_COMMENTS = 5;
    private static final int MAX_SCREENSHOTS = 5;
    private static final int MAX_TIMELINE = 30;

    /** 静态引用，供 FloatingWindowService 读取最新评论 */
    private static volatile ContextBuilder sInstance = null;

    /**
     * 获取 ContextBuilder 单例（供外部复用，服务重启时保留数据）。
     */
    public static ContextBuilder getInstance() {
        return sInstance;
    }

    private final LinkedList<Comment> recentComments = new LinkedList<>();
    private final LinkedList<String> recentScreenshots = new LinkedList<>();
    private final LinkedList<TimelineEvent> timeline = new LinkedList<>();

    /** 当前屏幕上的完整评论列表（每轮 doTimerTick 更新） */
    private volatile List<Comment> currentVisibleComments = List.of();

    /** 当前视频简介 */
    private volatile String currentVideoDescription = "";

    /** 悬浮窗显示的评论数量上限（避免无滚动列表溢出屏幕） */
    private static final int FLOATING_WINDOW_MAX_COMMENTS = 10;

    private int totalCommentCount = 0;
    private volatile Listener listener;

    public ContextBuilder() {
        // 服务重启时复用已有实例，避免 FloatingWindow 读取到空数据
        if (sInstance != null) {
            Log.w(TAG, "ContextBuilder 已存在，复用旧实例（保留环形缓冲区数据）");
        } else {
            sInstance = this;
        }
    }

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

        String now;
        synchronized (timestampFormat) {
            now = timestampFormat.format(new Date());
        }
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

        String now;
        synchronized (timestampFormat) {
            now = timestampFormat.format(new Date());
        }
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

    /**
     * 静默推送评论（不触发 listener.onContextUpdated）。
     * 仅更新缓冲区，供悬浮窗列表显示。
     */
    public void pushCommentsSilent(List<Comment> comments) {
        if (comments == null || comments.isEmpty()) return;

        String now;
        synchronized (timestampFormat) {
            now = timestampFormat.format(new Date());
        }
        synchronized (recentComments) {
            for (Comment c : comments) {
                recentComments.addLast(c);
                if (recentComments.size() > MAX_COMMENTS) {
                    recentComments.removeFirst();
                }
                String detail = c.user() + ": " + (c.text() != null ? c.text() : "(无文本)");
                pushTimelineEvent(new TimelineEvent("comment", now, detail));
            }
            totalCommentCount += comments.size();
        }

        Log.d(TAG, "静默推送 " + comments.size() + " 条评论，累计 " + totalCommentCount);
        // 不调用 notifyUpdate()，由调用方直接触发 AI
    }

    /**
     * 更新当前屏幕上的完整评论列表（每轮 doTimerTick 调用）。
     */
    public void setCurrentVisibleComments(List<Comment> comments) {
        currentVisibleComments = comments != null ? List.copyOf(comments) : List.of();
    }

    /**
     * 设置当前视频简介。
     */
    public void setVideoDescription(String description) {
        currentVideoDescription = description != null ? description : "";
    }

    /**
     * 获取当前视频简介。
     */
    public String getVideoDescription() {
        return currentVideoDescription;
    }

    /**
     * 静态入口：获取当前视频简介。
     */
    public static String getVideoDescriptionStatic() {
        ContextBuilder instance = sInstance;
        if (instance == null) return "";
        return instance.getVideoDescription();
    }

    /**
     * 获取当前屏幕上的完整评论列表（供悬浮窗显示）。
     * 超出 {@value #FLOATING_WINDOW_MAX_COMMENTS} 条时截断，避免无滚动列表溢出。
     */
    public List<Comment> getCurrentVisibleComments() {
        List<Comment> list = currentVisibleComments;
        int size = list.size();
        if (size > FLOATING_WINDOW_MAX_COMMENTS) {
            return list.subList(0, FLOATING_WINDOW_MAX_COMMENTS);
        }
        return list;
    }

    /**
     * 静态入口：获取当前屏幕上的完整评论列表（供 FloatingWindowService 使用）。
     */
    public static List<Comment> getLatestCommentsStatic() {
        ContextBuilder instance = sInstance;
        if (instance == null) return List.of();
        return instance.getCurrentVisibleComments();
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
        int snapshotCount;

        synchronized (recentComments) {
            commentsSnapshot = List.copyOf(recentComments);
            snapshotCount = totalCommentCount;
        }
        synchronized (recentScreenshots) {
            latestScreenshot = recentScreenshots.isEmpty() ? null : recentScreenshots.getLast();
        }
        synchronized (timeline) {
            timelineSnapshot = List.copyOf(timeline);
        }

        String timestamp;
        synchronized (timestampFormat) {
            timestamp = timestampFormat.format(new Date());
        }

        return new AppContext(
                "抖音",
                timestamp,
                snapshotCount,
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
