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

        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";
        if (!packageName.equals(DOUYIN_PACKAGE)) return;

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        CharSequence rootPkg = rootNode.getPackageName();
        if (rootPkg == null || !rootPkg.toString().equals(DOUYIN_PACKAGE)) {
            rootNode.recycle();
            return;
        }

        List<Comment> comments = new ArrayList<>();
        collectCommentsRecursive(rootNode, comments);
        rootNode.recycle();

        if (!comments.isEmpty()) {
            String json = buildJson(comments);
            Log.d(TAG, "=== 评论 JSON ===");
            Log.d(TAG, json);
            Log.d(TAG, "================");
        } else {
            Log.d(TAG, "未提取到评论，可能需要调整解析逻辑");
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "无障碍服务被中断");
    }

    /**
     * 递归遍历节点树，找到每条评论的 FrameLayout，
     * 从 contentDescription 提取用户/内容，从子节点提取点赞数。
     */
    private void collectCommentsRecursive(AccessibilityNodeInfo node, List<Comment> comments) {
        if (node == null) return;

        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.toString().contains("回复 按钮")) {
            Comment partial = CommentParser.parseFromDescription(desc.toString());
            if (partial != null) {
                // 在同个节点内找点赞数
                // 注意：只搜索，不回收子节点（统一由 collectCommentsRecursive 的循环回收）
                partial.likeCount = findLikeCount(node);
                comments.add(partial);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectCommentsRecursive(child, comments);
                child.recycle();
            }
        }
    }

    /**
     * 在评论节点内查找点赞数。
     * 只读不回收——子节点的回收由 collectCommentsRecursive 统一处理。
     */
    private int findLikeCount(AccessibilityNodeInfo node) {
        if (node == null) return 0;

        String text = node.getText() != null ? node.getText().toString().trim() : "";
        if (text.matches("\\d{1,6}")) {
            AccessibilityNodeInfo parent = node.getParent();
            if (parent != null) {
                CharSequence parentDesc = parent.getContentDescription();
                if (parentDesc != null && parentDesc.toString().contains("赞")) {
                    try {
                        return Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        return 0;
                    }
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                int found = findLikeCount(child);
                // 注意：不回收 child，统一由 collectCommentsRecursive 处理
                if (found > 0) return found;
            }
        }

        return 0;
    }

    private String buildJson(List<Comment> comments) {
        try {
            JSONObject json = new JSONObject();
            json.put("app", "抖音");
            json.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.CHINA).format(new Date()));
            json.put("comment_count", comments.size());

            JSONArray commentArray = new JSONArray();
            for (Comment comment : comments) {
                JSONObject commentJson = new JSONObject();
                commentJson.put("user", comment.user != null ? comment.user : "");
                commentJson.put("text", comment.text != null ? comment.text : "");
                commentJson.put("likes", comment.likeCount);
                commentArray.put(commentJson);
            }
            json.put("comments", commentArray);

            return json.toString(2);
        } catch (JSONException e) {
            Log.e(TAG, "JSON 构建失败", e);
            return "{}";
        }
    }
}
