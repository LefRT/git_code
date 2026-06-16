package com.zuoyou.commentcollector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * 统一上下文数据契约 — 融合评论 + 截图的时间线快照。
 * <p>
 * 由 {@link ContextBuilder} 构建，供 Phase 4 AI 处理管线消费。
 *
 * @param app              当前应用（如 "抖音"）
 * @param timestamp        上下文生成时间（ISO 8601）
 * @param commentCount     累计评论总数
 * @param recentComments   最近 N 条评论（最多 20）
 * @param latestScreenshot 最新截图文件路径（可能为 null）
 * @param timeline         最近事件时间线（最多 30）
 */
public record AppContext(
    String app,
    String timestamp,
    int commentCount,
    List<Comment> recentComments,
    String latestScreenshot,
    List<TimelineEvent> timeline
) {
    /**
     * 输出结构化 JSON。
     */
    public String toJson() {
        try {
            JSONObject json = new JSONObject();
            json.put("app", app);
            json.put("timestamp", timestamp);
            json.put("comment_count", commentCount);

            JSONArray commentArray = new JSONArray();
            for (Comment c : recentComments) {
                JSONObject cj = new JSONObject();
                cj.put("user", c.user());
                cj.put("text", c.text() != null ? c.text() : "");
                cj.put("likes", c.likeCount());
                cj.put("time", c.time() != null ? c.time() : "");
                cj.put("location", c.location() != null ? c.location() : "");
                commentArray.put(cj);
            }
            json.put("recent_comments", commentArray);

            json.put("latest_screenshot", latestScreenshot != null ? latestScreenshot : "");

            JSONArray timelineArray = new JSONArray();
            for (TimelineEvent e : timeline) {
                JSONObject ej = new JSONObject();
                ej.put("type", e.type());
                ej.put("time", e.time());
                ej.put("detail", e.detail());
                timelineArray.put(ej);
            }
            json.put("timeline", timelineArray);

            return json.toString(2);
        } catch (JSONException e) {
            android.util.Log.e("ZuoYouContext", "AppContext JSON 序列化失败", e);
            return "{}";
        }
    }
}

/**
 * 时间线事件 — 记录评论提取或截图捕获事件。
 *
 * @param type   "comment" | "screenshot"
 * @param time   ISO 8601 时间戳
 * @param detail 描述文本
 */
record TimelineEvent(
    String type,
    String time,
    String detail
) {}
