package com.zuoyou.commentcollector;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 抖音评论提取服务 — 5 秒定时循环 + 三态评论对比 + 最佳评论筛选。
 *
 * <p>每 5 秒执行一轮：
 * <ol>
 *   <li>遍历节点树提取评论列表</li>
 *   <li>与上轮对比 → 判断有无新评论（三态）</li>
 *   <li>如有新评论 → 评分筛选最佳 1 条 → 推送 AI</li>
 *   <li>截屏一次</li>
 * </ol>
 *
 * <p>视频切换检测由 {@link AccessibilityEvent#TYPE_WINDOW_STATE_CHANGED} 直接判定。
 */
public class DouyinCommentService extends AccessibilityService {

    private static final String TAG = "DouyinComment";

    /** 定时间隔（毫秒） */
    private static final long TIMER_INTERVAL_MS = 5000;

    /** 上一轮的评论列表（用于对比） */
    private List<Comment> previousComments = new ArrayList<>();

    /** 是否在前台运行（控制定时器） */
    private volatile boolean isForeground = false;

    /** 最近一次 STATE_CHANGED 事件时间戳（防抖） */
    private long lastStateChangedTime = 0;

    private CommentCollector collector;
    private ContextBuilder contextBuilder;
    private AiService aiService;

    private Handler timerHandler;
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isForeground) return;
            doTimerTick();
            timerHandler.postDelayed(this, TIMER_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        timerHandler = new Handler(Looper.getMainLooper());

        // 创建 Context Builder
        contextBuilder = new ContextBuilder();

        // Phase 4: AI 服务
        aiService = new AiService(this);
        aiService.setListener(new AiService.Listener() {
            @Override
            public void onAiResponse(String text) {
                FloatingWindowService.showComment(text);
                Log.d(TAG, "AI 回复: " + text);
            }

            @Override
            public void onError(String message) {
                Log.w(TAG, "AI 服务错误: " + message);
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(DouyinCommentService.this,
                                "AI 错误: " + message, Toast.LENGTH_SHORT).show());
            }
        });
        // 注入到 ScreenCaptureService
        ScreenCaptureService.setContextBuilder(contextBuilder);

        // 评论收集器（定时器驱动，不再通过 listener 自动推送）
        collector = new CommentCollector(null);
    }

    // ───── 无障碍事件 ─────

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!packageName.equals(Constants.DOUYIN_PACKAGE)) return;

        int eventType = event.getEventType();

        // ── 窗口切换（翻视频）→ 立即重置 + 触发一轮 ──
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            long now = System.currentTimeMillis();
            if (now - lastStateChangedTime < 300) return;
            lastStateChangedTime = now;

            // 标记进入前台，启动定时器
            if (!isForeground) {
                isForeground = true;
                timerHandler.post(timerRunnable);
                ScreenCaptureService.setDouyinForeground(true);
                Log.d(TAG, "抖音进入前台，启动定时器");
            }

            // 重置状态
            previousComments.clear();
            if (aiService != null) {
                aiService.resetVideoContext();
            }

            // 立即执行一轮
            doTimerTick();
            return;
        }

        // ── 内容变化 → 仅用于检测前台状态 ──
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (!isForeground) {
                isForeground = true;
                timerHandler.post(timerRunnable);
                ScreenCaptureService.setDouyinForeground(true);
                Log.d(TAG, "抖音进入前台（CONTENT_CHANGED），启动定时器");
            }
        }
    }

    /**
     * 定时器每 5 秒执行一轮：提取评论 → 对比 → 筛选 → 截屏。
     */
    private void doTimerTick() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        CharSequence rootPkg = rootNode.getPackageName();
        if (rootPkg == null || !rootPkg.toString().equals(Constants.DOUYIN_PACKAGE)) {
            rootNode.recycle();
            // 抖音不在前台，停止定时器
            isForeground = false;
            ScreenCaptureService.setDouyinForeground(false);
            Log.d(TAG, "抖音不在前台，停止定时器");
            return;
        }

        try {
            // 1. 提取当前评论列表
            List<Comment> currentComments = extractComments(rootNode);

            // 2. 与上轮对比
            CommentDiffer.DiffResult diff = CommentDiffer.diff(previousComments, currentComments);

            // 3. 根据变化状态处理
            switch (diff.status()) {
                case NO_UPDATE:
                    Log.d(TAG, "评论无更新");
                    break;

                case PARTIAL_UPDATE:
                case FULL_UPDATE:
                    List<Comment> newComments = diff.newComments();
                    Log.d(TAG, diff.status() + "，新增 " + newComments.size() + " 条评论");

                    // 静默推送新增评论到 ContextBuilder（供悬浮窗列表显示，不触发 AI）
                    contextBuilder.pushCommentsSilent(newComments);

                    // 选出最佳评论推送 AI
                    Comment best = diff.bestComment();
                    if (best != null) {
                        String text = best.text() != null ? best.text() : "";
                        Log.d(TAG, "最佳评论: " + best.user() + " (赞 " + best.likeCount() + ")");
                        aiService.sendComment(text);
                    }
                    break;
            }

            // 4. 更新上轮记录
            previousComments = currentComments;

        } finally {
            rootNode.recycle();
        }

        // 5. 截屏
        if (ScreenCaptureService.isRunning) {
            ScreenCaptureService.captureOnce();
        }
    }

    /**
     * 从节点树中提取评论列表（只读遍历，不通过 listener 回调）。
     */
    private List<Comment> extractComments(AccessibilityNodeInfo rootNode) {
        List<Comment> comments = new ArrayList<>();
        extractRecursive(rootNode, comments);
        return comments;
    }

    /**
     * 递归遍历节点树提取评论（与 CommentCollector 逻辑一致，但直接返回列表）。
     */
    private void extractRecursive(AccessibilityNodeInfo node, List<Comment> comments) {
        if (node == null) return;

        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.toString().contains("回复 按钮")) {
            Comment partial = CommentParser.parseFromDescription(desc.toString());
            if (partial != null) {
                partial = partial.withLikeCount(findLikeCount(node));
                comments.add(partial);
            }
        }

        int childCount = safeGetChildCount(node);
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(i);
                if (child != null) {
                    extractRecursive(child, comments);
                }
            } catch (Exception e) {
                Log.w(TAG, "遍历子节点 #" + i + " 时异常", e);
            } finally {
                if (child != null) child.recycle();
            }
        }
    }

    /**
     * 在评论节点内查找点赞数。
     */
    private int findLikeCount(AccessibilityNodeInfo node) {
        if (node == null) return 0;

        try {
            String text = node.getText() != null ? node.getText().toString().trim() : "";
            if (text.matches("\\d{1,6}")) {
                AccessibilityNodeInfo parent = node.getParent();
                if (parent != null) {
                    try {
                        CharSequence parentDesc = parent.getContentDescription();
                        if (parentDesc != null && parentDesc.toString().contains("赞")) {
                            return Integer.parseInt(text);
                        }
                    } finally {
                        parent.recycle();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "findLikeCount 异常", e);
        }

        int childCount = safeGetChildCount(node);
        for (int i = 0; i < childCount; i++) {
            try {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    try {
                        int found = findLikeCount(child);
                        if (found > 0) return found;
                    } finally {
                        child.recycle();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "findLikeCount 递归异常", e);
            }
        }

        return 0;
    }

    private int safeGetChildCount(AccessibilityNodeInfo node) {
        try {
            return node.getChildCount();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "无障碍服务被中断");
    }

    @Override
    public void onDestroy() {
        isForeground = false;
        timerHandler.removeCallbacksAndMessages(null);
        ScreenCaptureService.setDouyinForeground(false);
        if (aiService != null) {
            aiService.shutdown();
        }
        super.onDestroy();
    }
}
