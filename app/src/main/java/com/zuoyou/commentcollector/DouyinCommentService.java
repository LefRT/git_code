package com.zuoyou.commentcollector;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 1 核心：抖音评论提取服务。
 *
 * 通过 Android AccessibilityService 监听抖音窗口变化，
 * 遍历节点树提取评论区的用户、内容、点赞数、时间和位置，
 * 输出结构化 JSON 到 Logcat。
 *
 * 节点遍历和 JSON 构建委托给 {@link CommentCollector}。
 */
public class DouyinCommentService extends AccessibilityService {

    private static final String TAG = "DouyinComment";
    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";

    /** 同类型事件的最小处理间隔（毫秒），防抖用 */
    private static final long MIN_EVENT_INTERVAL_MS = 500;

    /** 最近一次处理事件的时间戳 */
    private long lastProcessedTime = 0;

    /**
     * 已提取评论的去重缓存（自动淘汰最旧条目）。
     * key = hash(user, text)，Integer 哈希键避免字符串拼接的分隔符冲突。
     */
    private final LinkedHashMap<Integer, Boolean> recentComments = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
            return size() > 100;
        }
    };

    private CommentCollector collector;
    private ContextBuilder contextBuilder;

    @Override
    public void onCreate() {
        super.onCreate();

        // 创建 Context Builder（Phase 3 数据管道）
        contextBuilder = new ContextBuilder();
        contextBuilder.setListener(context -> {
            // Phase 4 入口：在此处将 context 传递给 AI 处理管线
            // 当前仅 Logcat 输出，由 ContextBuilder 内部完成
        });

        // 注入到 ScreenCaptureService，使其能推送截图事件
        ScreenCaptureService.setContextBuilder(contextBuilder);

        // 创建评论收集器，通过 Listener 接收最新评论
        collector = new CommentCollector(comments -> {
            // 去重过滤：跳过已提取过的评论
            List<Comment> freshComments = new ArrayList<>();
            for (Comment c : comments) {
                // 使用复合哈希作为去重键，避免字符串拼接的分隔符冲突
                Integer key = Objects.hash(c.user(), c.text());
                if (!recentComments.containsKey(key)) {
                    recentComments.put(key, Boolean.TRUE);
                    freshComments.add(c);
                }
            }
            // 将新评论推入 Context Builder
            if (!freshComments.isEmpty()) {
                contextBuilder.pushComments(freshComments);
            }
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!packageName.equals(DOUYIN_PACKAGE)) return;

        // 事件防抖：500ms 内跳过重复处理，适用于 CONTENT_CHANGED 和 STATE_CHANGED
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            long now = System.currentTimeMillis();
            if (now - lastProcessedTime < MIN_EVENT_INTERVAL_MS) return;
            lastProcessedTime = now;
        }

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        CharSequence rootPkg = rootNode.getPackageName();
        if (rootPkg == null || !rootPkg.toString().equals(DOUYIN_PACKAGE)) {
            rootNode.recycle();
            return;
        }

        // 委托 CommentCollector 完成节点遍历、解析、JSON 输出
        collector.collect(rootNode);
        rootNode.recycle();
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "无障碍服务被中断");
    }
}
