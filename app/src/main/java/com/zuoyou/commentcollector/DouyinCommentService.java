package com.zuoyou.commentcollector;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

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
 *
 * ## 自适应采样
 *
 * 当连续多次树遍历都未发现新评论时，自动拉长事件间隔，
 * 减少无意义的节点树遍历以节省 CPU 和电量。
 * 一旦发现新评论，立即恢复到最短间隔。
 */
public class DouyinCommentService extends AccessibilityService {

    private static final String TAG = "DouyinComment";

    // ── 自适应采样参数 ──
    /** 最短事件间隔（有新评论时） */
    private static final long MIN_EVENT_INTERVAL_MS = 1000;
    /** 最长事件间隔（长时间无新评论时） */
    private static final long MAX_EVENT_INTERVAL_MS = 5000;
    /** 连续多少次无新评论后，间隔翻倍 */
    private static final int EMPTY_BACKOFF_THRESHOLD = 3;

    /** 当前有效事件间隔（动态调整） */
    private long currentIntervalMs = MIN_EVENT_INTERVAL_MS;
    /** 最近一次处理事件的时间戳 */
    private long lastProcessedTime = 0;
    /** 最近一次 STATE_CHANGED 事件的时间戳（防抖） */
    private long lastStateChangedTime = 0;
    /** 连续无新评论的次数 */
    private int emptyWalkCount = 0;

    /**
     * 已提取评论的去重缓存（自动淘汰最旧条目）。
     */
    private final LinkedHashMap<Integer, Boolean> recentComments = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Boolean> eldest) {
            return size() > 100;
        }
    };

    private CommentCollector collector;
    private ContextBuilder contextBuilder;
    private AiService aiService;

    @Override
    public void onCreate() {
        super.onCreate();

        // 创建 Context Builder（Phase 3 数据管道）
        contextBuilder = new ContextBuilder();

        // Phase 4: AI 服务
        aiService = new AiService(this);
        aiService.setListener(new AiService.Listener() {
            @Override
            public void onAiResponse(String text, String emotion) {
                FloatingWindowService.showComment(text);
                Log.d(TAG, "AI 吐槽 [" + emotion + "]: " + text);
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "AI 服务错误: " + message);
                // 在主线程显示 Toast，让用户知道 AI 出错了
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(DouyinCommentService.this,
                                "AI 错误: " + message, Toast.LENGTH_SHORT).show());
            }
        });
        contextBuilder.setListener(aiService);

        // 注入到 ScreenCaptureService
        ScreenCaptureService.setContextBuilder(contextBuilder);

        // 创建评论收集器
        collector = new CommentCollector(comments -> {
            List<Comment> freshComments = new ArrayList<>();
            for (Comment c : comments) {
                Integer key = Objects.hash(c.user(), c.text());
                if (!recentComments.containsKey(key)) {
                    recentComments.put(key, Boolean.TRUE);
                    freshComments.add(c);
                }
            }

            if (!freshComments.isEmpty()) {
                // 发现新评论 → 缩短采样间隔
                if (currentIntervalMs > MIN_EVENT_INTERVAL_MS) {
                    currentIntervalMs = Math.max(MIN_EVENT_INTERVAL_MS, currentIntervalMs / 2);
                    Log.d(TAG, "发现新评论，缩短采样间隔至 " + currentIntervalMs + "ms");
                }
                emptyWalkCount = 0;
                contextBuilder.pushComments(freshComments);
            } else {
                // 无新评论 → 累计空跑次数
                emptyWalkCount++;
                if (emptyWalkCount >= EMPTY_BACKOFF_THRESHOLD) {
                    long newInterval = Math.min(currentIntervalMs * 2, MAX_EVENT_INTERVAL_MS);
                    if (newInterval != currentIntervalMs) {
                        currentIntervalMs = newInterval;
                        Log.d(TAG, "连续 " + emptyWalkCount + " 次无新评论，延长采样间隔至 " + currentIntervalMs + "ms");
                    }
                }
            }
        });
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!packageName.equals(Constants.DOUYIN_PACKAGE)) return;

        // 通知 ScreenCaptureService：抖音在前台
        ScreenCaptureService.setDouyinForeground(true);

        int eventType = event.getEventType();

        // ── 窗口切换（翻视频）→ 立即重置采样 + 去重缓存 ──
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            long now = System.currentTimeMillis();
            // 防抖：300ms 内的连续 STATE_CHANGED 事件只处理一次
            if (now - lastStateChangedTime < MIN_EVENT_INTERVAL_MS) return;
            lastStateChangedTime = now;
            // 重置自适应采样
            currentIntervalMs = MIN_EVENT_INTERVAL_MS;
            emptyWalkCount = 0;
            // 清除去重缓存，让新视频的评论能通过
            recentComments.clear();
            // 重置 AI 内容哈希
            if (aiService != null) {
                aiService.resetVideoContext();
            }
            lastProcessedTime = now;
            Log.d(TAG, "窗口切换，重置采样间隔至 " + MIN_EVENT_INTERVAL_MS + "ms，清空去重缓存");
            // 立即执行一次树遍历，不要被防抖挡住
            AccessibilityNodeInfo rootNode = getRootInActiveWindow();
            if (rootNode != null) {
                CharSequence rootPkg = rootNode.getPackageName();
                if (rootPkg != null && rootPkg.toString().equals(Constants.DOUYIN_PACKAGE)) {
                    collector.collect(rootNode);
                }
                rootNode.recycle();
            }
            return;
        }

        // ── 普通内容变化 → 自适应防抖 ──
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            long now = System.currentTimeMillis();
            if (now - lastProcessedTime < currentIntervalMs) return;
            lastProcessedTime = now;
        }

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        CharSequence rootPkg = rootNode.getPackageName();
        if (rootPkg == null || !rootPkg.toString().equals(Constants.DOUYIN_PACKAGE)) {
            rootNode.recycle();
            return;
        }

        collector.collect(rootNode);
        rootNode.recycle();
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "无障碍服务被中断");
    }

    @Override
    public void onDestroy() {
        // 抖音前台标志重置
        ScreenCaptureService.setDouyinForeground(false);
        if (aiService != null) {
            aiService.shutdown();
        }
        super.onDestroy();
    }
}
