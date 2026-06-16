package com.zuoyou.commentcollector;

import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 评论数据收集器 — 将 Phase 1 的"遍历节点树→解析→构建 JSON"抽离为独立类。
 *
 * 职责：
 * 1. 递归遍历 AccessibilityNodeInfo 树，找到所有评论 FrameLayout
 * 2. 委托 CommentParser 解析 contentDescription
 * 3. 在节点树内查找点赞数
 * 4. 构建结构化 JSON 输出
 *
 * 设计为纯逻辑类，不依赖 AccessibilityService 生命周期，方便 Phase 3 复用。
 */
public class CommentCollector {

    private static final String TAG = "DouyinComment";
    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";

    private final Listener listener;

    /**
     * @param listener 收集到评论时的回调，可为 null（仅输出 Logcat）
     */
    public CommentCollector(Listener listener) {
        this.listener = listener;
    }

    /**
     * 从根节点开始递归收集评论。
     * 结果通过 {@link Listener} 回调传递，避免多余的返回值分配。
     *
     * @param rootNode 当前 Activity 的根 AccessibilityNodeInfo
     */
    public void collect(AccessibilityNodeInfo rootNode) {
        List<Comment> comments = new ArrayList<>();
        collectRecursive(rootNode, comments);

        if (!comments.isEmpty()) {
            String json = buildJson(comments);
            android.util.Log.d(TAG, json);

            if (listener != null) {
                listener.onCommentsCollected(comments);
            }
        } else {
            android.util.Log.d(TAG, "未提取到评论");
        }
    }

    // ──────────────────────────────────────────────
    //  节点树遍历
    // ──────────────────────────────────────────────

    /**
     * 递归遍历节点树，找到每条评论的 FrameLayout，
     * 从 contentDescription 提取用户/内容，从子节点提取点赞数。
     */
    private void collectRecursive(AccessibilityNodeInfo node, List<Comment> comments) {
        if (node == null) return;

        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.toString().contains("回复 按钮")) {
            Comment partial = CommentParser.parseFromDescription(desc.toString());
            if (partial != null) {
                // 在同个节点内找点赞数，record 不可变，返回新实例
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
                    collectRecursive(child, comments);
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "遍历子节点 #" + i + " 时异常（已跳过）", e);
            } finally {
                if (child != null) child.recycle();
            }
        }
    }

    // ──────────────────────────────────────────────
    //  点赞数查找
    // ──────────────────────────────────────────────

    /**
     * 在评论节点内查找点赞数。
     *
     * 注意：每次 getChild() / getParent() 都返回新的 Java 包装对象，
     * 必须调用 recycle() 回收，否则泄漏原生资源会导致服务被系统杀死。
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
            android.util.Log.w(TAG, "findLikeCount 直接检查异常", e);
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
                android.util.Log.w(TAG, "findLikeCount 递归子节点 #" + i + " 异常（已跳过）", e);
            }
        }

        return 0;
    }

    // ──────────────────────────────────────────────
    //  工具方法
    // ──────────────────────────────────────────────

    /**
     * 安全获取节点子节点数，避免已回收节点抛异常。
     */
    private int safeGetChildCount(AccessibilityNodeInfo node) {
        try {
            return node.getChildCount();
        } catch (Exception e) {
            android.util.Log.w(TAG, "getChildCount 异常，返回 0", e);
            return 0;
        }
    }

    // ──────────────────────────────────────────────
    //  JSON 构建
    // ──────────────────────────────────────────────

    private static String buildJson(List<Comment> comments) {
        try {
            JSONObject json = new JSONObject();
            json.put("app", "抖音");
            json.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).format(new Date()));
            json.put("comment_count", comments.size());

            JSONArray commentArray = new JSONArray();
            for (Comment comment : comments) {
                JSONObject commentJson = new JSONObject();
                commentJson.put("user", nullToEmpty(comment.user()));
                commentJson.put("text", nullToEmpty(comment.text()));
                commentJson.put("likes", comment.likeCount());
                commentJson.put("time", nullToEmpty(comment.time()));
                commentJson.put("location", nullToEmpty(comment.location()));
                commentArray.put(commentJson);
            }
            json.put("comments", commentArray);

            return json.toString(2);
        } catch (JSONException e) {
            android.util.Log.e(TAG, "JSON 构建失败", e);
            return "{}";
        }
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    // ──────────────────────────────────────────────
    //  回调接口
    // ──────────────────────────────────────────────

    /**
     * 收集到评论时的回调。
     * Phase 3 Context Builder 可以通过实现此接口接入数据处理管线。
     */
    public interface Listener {
        void onCommentsCollected(List<Comment> comments);
    }
}
