package com.zuoyou.commentcollector;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DouyinCommentService extends AccessibilityService {

    private static final String TAG = "DouyinComment";
    private static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;

        // 只处理抖音的事件
        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!packageName.equals(DOUYIN_PACKAGE)) return;

        Log.d(TAG, "收到抖音事件: " + event.getEventType());

        // 获取当前窗口的根节点
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        // 提取评论
        List<Comment> comments = extractComments(rootNode);

        if (!comments.isEmpty()) {
            // 输出 JSON
            String json = buildJson(comments);
            Log.d(TAG, "=== 评论 JSON ===");
            Log.d(TAG, json);
            Log.d(TAG, "================");
        }

        rootNode.recycle();
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "无障碍服务被中断");
    }

    /**
     * 从节点树中提取评论
     */
    private List<Comment> extractComments(AccessibilityNodeInfo rootNode) {
        List<Comment> comments = new ArrayList<>();

        // 查找评论列表（通常是 RecyclerView）
        List<AccessibilityNodeInfo> recyclerViews = findNodesByClassName(rootNode, "androidx.recyclerview.widget.RecyclerView");

        for (AccessibilityNodeInfo recyclerView : recyclerViews) {
            // 遍历列表项
            for (int i = 0; i < recyclerView.getChildCount(); i++) {
                AccessibilityNodeInfo item = recyclerView.getChild(i);
                if (item != null) {
                    Comment comment = parseCommentItem(item);
                    if (comment != null && comment.text != null && !comment.text.isEmpty()) {
                        comments.add(comment);
                    }
                    item.recycle();
                }
            }
        }

        return comments;
    }

    /**
     * 解析单条评论
     */
    private Comment parseCommentItem(AccessibilityNodeInfo item) {
        Comment comment = new Comment();

        // 获取所有 TextView
        List<AccessibilityNodeInfo> textViews = findNodesByClassName(item, "android.widget.TextView");

        for (int i = 0; i < textViews.size(); i++) {
            AccessibilityNodeInfo textView = textViews.get(i);
            String text = textView.getText() != null ? textView.getText().toString() : "";

            if (text.isEmpty()) continue;

            // 简单策略：第一个非空文本作为用户名，第二个作为评论内容
            if (comment.user == null) {
                comment.user = text;
            } else if (comment.text == null) {
                comment.text = text;
            }

            textView.recycle();
        }

        return comment;
    }

    /**
     * 查找指定类名的节点
     */
    private List<AccessibilityNodeInfo> findNodesByClassName(AccessibilityNodeInfo root, String className) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        findNodesRecursive(root, className, result);
        return result;
    }

    private void findNodesRecursive(AccessibilityNodeInfo node, String className, List<AccessibilityNodeInfo> result) {
        if (node == null) return;

        if (className.equals(node.getClassName())) {
            result.add(AccessibilityNodeInfo.obtain(node));
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                findNodesRecursive(child, className, result);
                child.recycle();
            }
        }
    }

    /**
     * 构建 JSON 输出
     */
    private String buildJson(List<Comment> comments) {
        try {
            JSONObject json = new JSONObject();
            json.put("app", "抖音");
            json.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).format(new Date()));
            json.put("comment_count", comments.size());

            JSONArray commentArray = new JSONArray();
            for (Comment comment : comments) {
                JSONObject commentJson = new JSONObject();
                commentJson.put("user", comment.user);
                commentJson.put("text", comment.text);
                commentArray.put(commentJson);
            }
            json.put("comments", commentArray);

            return json.toString(2); // 格式化输出
        } catch (JSONException e) {
            Log.e(TAG, "JSON 构建失败", e);
            return "{}";
        }
    }

    /**
     * 评论数据类
     */
    private static class Comment {
        String user;
        String text;
    }
}
