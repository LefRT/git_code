package com.zuoyou.commentcollector.feature;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.zuoyou.commentcollector.Comment;
import com.zuoyou.commentcollector.Constants;
import com.zuoyou.commentcollector.ContextBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆收集器 — 定时从 ContextBuilder 采集视频简介和高赞评论，
 * 生成文本块供 ChatAiService 注入 AI 上下文。
 *
 * <p>记忆开关存储在 {@link Constants#KEY_MEMORY_ENABLED}（SharedPreferences）。
 * 采集数据持久化到 JSON 文件，Activity 重建不丢失。
 *
 * <p>采集策略（每 5 秒由 DouyinCommentService 定时器触发）：
 * <ul>
 *   <li>视频简介：最多 5 条（去重）</li>
 *   <li>高赞评论：点赞 > 50，最多 20 条（去重）</li>
 * </ul>
 */
public class MemoryCollector {

    private static final String TAG = "MemoryCollector";

    private static final int MAX_DESCRIPTIONS = 5;
    private static final int MAX_HIGH_LIKE_COMMENTS = 20;
    private static final int LIKE_THRESHOLD = 50;
    private static final String DATA_FILE = "memory_data.json";

    private final Context appContext;

    // 去重用 LinkedHashMap（插入序，自动淘汰最旧）
    private final LinkedHashMap<String, String> descriptions = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_DESCRIPTIONS;
        }
    };
    private final LinkedHashMap<String, Comment> highLikeComments = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Comment> eldest) {
            return size() > MAX_HIGH_LIKE_COMMENTS;
        }
    };

    // ─── 单例 ───

    private static volatile MemoryCollector sInstance;

    public static MemoryCollector getInstance() {
        return sInstance;
    }

    public MemoryCollector(Context context) {
        this.appContext = context.getApplicationContext();
        // 不覆盖已有实例的数据（Activity 重建时保留采集结果）
        if (sInstance == null) {
            sInstance = this;
            loadData();
        }
        Log.d(TAG, "初始化完成，已有 " + descriptions.size() + " 简介, " +
                highLikeComments.size() + " 评论");
    }

    // ─── 开关 ───

    /**
     * 记忆收集是否开启。
     */
    public boolean isEnabled() {
        SharedPreferences prefs = appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(Constants.KEY_MEMORY_ENABLED, false);
    }

    /**
     * 设置记忆收集开关。
     */
    public void setEnabled(boolean enabled) {
        appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(Constants.KEY_MEMORY_ENABLED, enabled)
                .apply();
        Log.d(TAG, "记忆收集: " + (enabled ? "开启" : "关闭"));
    }

    // ─── 采集 ───

    /**
     * 尝试采集一次（由 DouyinCommentService 5 秒定时器调用）。
     * 如果开关关闭或 ContextBuilder 不可用，直接返回。
     */
    public static void tryCollect() {
        MemoryCollector instance = sInstance;
        if (instance == null || !instance.isEnabled()) return;

        ContextBuilder ctx = ContextBuilder.getInstance();
        if (ctx == null) return;

        instance.collectFrom(ctx);
    }

    private synchronized void collectFrom(ContextBuilder ctx) {
        // 采集视频简介
        String desc = ctx.getVideoDescription();
        if (desc != null && !desc.isEmpty()) {
            // 用前 50 字做去重 key
            String key = desc.length() > 50 ? desc.substring(0, 50) : desc;
            if (!descriptions.containsKey(key)) {
                descriptions.put(key, desc);
                Log.d(TAG, "采集新视频简介 (" + descriptions.size() + "): " +
                        (desc.length() > 40 ? desc.substring(0, 40) + "..." : desc));
            }
        }

        // 采集高赞评论
        List<Comment> visible = ctx.getCurrentVisibleComments();
        for (Comment c : visible) {
            if (c.likeCount() > LIKE_THRESHOLD) {
                String key = c.user() + "|" + (c.text() != null ? c.text() : "");
                if (!highLikeComments.containsKey(key)) {
                    highLikeComments.put(key, c);
                    Log.d(TAG, "采集高赞评论 (❤" + c.likeCount() + "): " +
                            c.user() + " - " +
                            (c.text() != null && c.text().length() > 20
                                    ? c.text().substring(0, 20) + "..." : c.text()));
                }
            }
        }

        saveData();
    }

    // ─── AI 注入文本 ───

    /**
     * 构建记忆上下文文本块，用于注入 AI 消息数组。
     *
     * <p>如果没有采集到任何记忆，返回空字符串。
     */
    public synchronized String buildMemoryContext() {
        if (descriptions.isEmpty() && highLikeComments.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("你已经刷过以下视频：\n");

        // 视频简介
        if (!descriptions.isEmpty()) {
            for (String desc : descriptions.values()) {
                sb.append("📹 ").append(desc).append("\n");
            }
        }

        // 高赞评论
        if (!highLikeComments.isEmpty()) {
            sb.append("\n热门评论：\n");
            for (Comment c : highLikeComments.values()) {
                String text = c.text() != null ? c.text() : "(无文本)";
                if (text.length() > 50) {
                    text = text.substring(0, 50) + "...";
                }
                sb.append("💬 ").append(c.user()).append(": ").append(text)
                        .append(" (❤ ").append(c.likeCount()).append(")\n");
            }
        }

        String result = sb.toString();
        Log.d(TAG, "记忆上下文 (" + result.length() + " 字):\n" +
                (result.length() > 200 ? result.substring(0, 200) + "..." : result));
        return result;
    }

    // ─── 统计 ───

    /**
     * 已采集的视频简介数量。
     */
    public synchronized int getDescriptionCount() {
        return descriptions.size();
    }

    /**
     * 已采集的高赞评论数量。
     */
    public synchronized int getHighLikeCommentCount() {
        return highLikeComments.size();
    }

    /**
     * 清空所有已采集的记忆。
     */
    public synchronized void clearMemory() {
        descriptions.clear();
        highLikeComments.clear();
        saveData();
        Log.d(TAG, "记忆已清空");
    }

    // ─── 持久化 ───

    private synchronized void saveData() {
        try {
            JSONObject root = new JSONObject();

            JSONArray descArr = new JSONArray();
            for (String desc : descriptions.values()) {
                descArr.put(desc);
            }
            root.put("descriptions", descArr);

            JSONArray commentArr = new JSONArray();
            for (Comment c : highLikeComments.values()) {
                JSONObject cObj = new JSONObject();
                cObj.put("user", c.user());
                cObj.put("text", c.text());
                cObj.put("likes", c.likeCount());
                cObj.put("time", c.time());
                cObj.put("location", c.location());
                commentArr.put(cObj);
            }
            root.put("high_like_comments", commentArr);

            File file = new File(appContext.getFilesDir(), DATA_FILE);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.e(TAG, "保存记忆数据失败", e);
        }
    }

    private void loadData() {
        File file = new File(appContext.getFilesDir(), DATA_FILE);
        if (!file.exists()) return;

        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }

            JSONObject root = new JSONObject(sb.toString());

            JSONArray descArr = root.optJSONArray("descriptions");
            if (descArr != null) {
                for (int i = 0; i < descArr.length(); i++) {
                    String desc = descArr.getString(i);
                    String key = desc.length() > 50 ? desc.substring(0, 50) : desc;
                    descriptions.put(key, desc);
                }
            }

            JSONArray commentArr = root.optJSONArray("high_like_comments");
            if (commentArr != null) {
                for (int i = 0; i < commentArr.length(); i++) {
                    JSONObject cObj = commentArr.getJSONObject(i);
                    Comment c = new Comment(
                            cObj.optString("user", ""),
                            cObj.optString("text", ""),
                            cObj.optInt("likes", 0),
                            cObj.optString("time", ""),
                            cObj.optString("location", "")
                    );
                    String key = c.user() + "|" + (c.text() != null ? c.text() : "");
                    highLikeComments.put(key, c);
                }
            }

            Log.d(TAG, "加载记忆数据: " + descriptions.size() + " 简介, " +
                    highLikeComments.size() + " 评论");
        } catch (Exception e) {
            Log.e(TAG, "加载记忆数据失败", e);
        }
    }
}
