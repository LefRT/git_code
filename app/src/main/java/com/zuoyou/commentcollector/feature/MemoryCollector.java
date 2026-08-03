package com.zuoyou.commentcollector.feature;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.zuoyou.commentcollector.Comment;
import com.zuoyou.commentcollector.Constants;
import com.zuoyou.commentcollector.ContextBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆收集器 — 定时从 ContextBuilder 采集视频简介和高赞评论，
 * 按视频分组存储，生成文本块供 ChatAiService 注入 AI 上下文。
 *
 * <p>记忆开关存储在 {@link Constants#KEY_MEMORY_ENABLED}（SharedPreferences）。
 * 采集数据持久化到 JSON 文件，Activity 重建不丢失。
 *
 * <p>采集策略（每 5 秒由 DouyinCommentService 定时器触发）：
 * <ul>
 *   <li>视频简介：最多 5 个视频（去重）</li>
 *   <li>每个视频的高赞评论：点赞 > 50，最多 20 条（去重）</li>
 * </ul>
 */
public class MemoryCollector {

    private static final String TAG = "MemoryCollector";

    private static final int MAX_VIDEOS = 40;
    private static final int MAX_COMMENTS_PER_VIDEO = 20;
    private static final int LIKE_THRESHOLD = 50;
    private static final String DATA_FILE = "memory_data.json";

    private final Context appContext;
    private volatile boolean dirty = false;

    /**
     * 视频记忆条目 — 包含视频简介和该视频下的高赞评论。
     */
    public static class VideoEntry {
        public final String description;
        public final LinkedHashMap<String, Comment> comments = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Comment> eldest) {
                return size() > MAX_COMMENTS_PER_VIDEO;
            }
        };

        public VideoEntry(String description) {
            this.description = description;
        }

        public int getCommentCount() {
            return comments.size();
        }

        public List<Comment> getComments() {
            return new ArrayList<>(comments.values());
        }
    }

    // 按视频分组的记忆数据（插入序，自动淘汰最旧）
    private final LinkedHashMap<String, VideoEntry> videoMemories = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, VideoEntry> eldest) {
            return size() > MAX_VIDEOS;
        }
    };

    // ─── 单例 ───

    private static volatile MemoryCollector sInstance;

    public static MemoryCollector getInstance() {
        return sInstance;
    }

    public MemoryCollector(Context context) {
        this.appContext = context.getApplicationContext();
        if (sInstance == null) {
            sInstance = this;
            loadData();
        }
        Log.d(TAG, "初始化完成，已有 " + videoMemories.size() + " 个视频记忆");
    }

    // ─── 开关 ───

    public boolean isEnabled() {
        SharedPreferences prefs = appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(Constants.KEY_MEMORY_ENABLED, false);
    }

    public void setEnabled(boolean enabled) {
        appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(Constants.KEY_MEMORY_ENABLED, enabled)
                .apply();
        Log.d(TAG, "记忆收集: " + (enabled ? "开启" : "关闭"));
    }

    // ─── 采集 ───

    /** 当前正在采集的视频 key，用于检测视频切换 */
    private String currentVideoKey = "";

    public static void tryCollect() {
        MemoryCollector instance = sInstance;
        if (instance == null || !instance.isEnabled()) return;

        ContextBuilder ctx = ContextBuilder.getInstance();
        if (ctx == null) return;

        instance.collectFrom(ctx);
    }

    /**
     * 通知视频已切换（由 DouyinCommentService 在 FULL_UPDATE 时调用）。
     * 下次 collectFrom 会创建新的视频分组。
     */
    public static void signalVideoChange() {
        MemoryCollector instance = sInstance;
        if (instance == null) return;
        instance.currentVideoKey = "";
        Log.d(TAG, "视频切换信号");
    }

    private synchronized void collectFrom(ContextBuilder ctx) {
        boolean newData = false;

        // 获取当前视频简介
        String desc = ctx.getVideoDescription();
        if (desc == null || desc.isEmpty()) return;

        // 获取或创建视频条目
        String descKey = desc.length() > 50 ? desc.substring(0, 50) : desc;
        VideoEntry entry = videoMemories.get(descKey);

        // 视频切换：key 变了，说明用户滑到了新视频
        if (!descKey.equals(currentVideoKey)) {
            currentVideoKey = descKey;
            if (entry == null) {
                entry = new VideoEntry(desc);
                videoMemories.put(descKey, entry);
                newData = true;
                Log.d(TAG, "新视频简介 (" + videoMemories.size() + "): " +
                        (desc.length() > 40 ? desc.substring(0, 40) + "..." : desc));
            }
        }

        // 如果还没有对应条目（首次采集），创建之
        if (entry == null) {
            entry = new VideoEntry(desc);
            videoMemories.put(descKey, entry);
            newData = true;
        }

        // 采集该视频的高赞评论
        List<Comment> visible = ctx.getCurrentVisibleComments();
        for (Comment c : visible) {
            if (c.likeCount() > LIKE_THRESHOLD) {
                String commentKey = c.user() + "|" + (c.text() != null ? c.text() : "");
                if (!entry.comments.containsKey(commentKey)) {
                    entry.comments.put(commentKey, c);
                    newData = true;
                    Log.d(TAG, "采集高赞评论 (❤" + c.likeCount() + "): " +
                            c.user() + " - " +
                            (c.text() != null && c.text().length() > 20
                                    ? c.text().substring(0, 20) + "..." : c.text()));
                }
            }
        }

        if (newData) {
            dirty = true;
            saveData();
        }
    }

    // ─── AI 注入文本 ───

    public synchronized String buildMemoryContext() {
        if (videoMemories.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("你已经刷过以下视频：\n");

        for (VideoEntry entry : videoMemories.values()) {
            sb.append("📹 ").append(entry.description).append("\n");
            if (!entry.comments.isEmpty()) {
                sb.append("  热门评论：\n");
                for (Comment c : entry.comments.values()) {
                    String text = c.text() != null ? c.text() : "(无文本)";
                    if (text.length() > 50) {
                        text = text.substring(0, 50) + "...";
                    }
                    sb.append("  💬 ").append(c.user()).append(": ").append(text)
                            .append(" (❤ ").append(c.likeCount()).append(")\n");
                }
            }
        }

        String result = sb.toString();
        Log.d(TAG, "记忆上下文 (" + result.length() + " 字):\n" +
                (result.length() > 200 ? result.substring(0, 200) + "..." : result));
        return result;
    }

    // ─── 统计 ───

    public synchronized int getDescriptionCount() {
        return videoMemories.size();
    }

    public synchronized int getHighLikeCommentCount() {
        int count = 0;
        for (VideoEntry entry : videoMemories.values()) {
            count += entry.comments.size();
        }
        return count;
    }

    public synchronized List<String> getDescriptions() {
        List<String> result = new ArrayList<>();
        for (VideoEntry entry : videoMemories.values()) {
            result.add(entry.description);
        }
        return result;
    }

    public synchronized List<Comment> getHighLikeComments() {
        List<Comment> result = new ArrayList<>();
        for (VideoEntry entry : videoMemories.values()) {
            result.addAll(entry.comments.values());
        }
        return result;
    }

    /**
     * 获取所有视频记忆条目（用于弹窗显示）。
     */
    public synchronized List<VideoEntry> getVideoEntries() {
        return new ArrayList<>(videoMemories.values());
    }

    public synchronized void clearMemory() {
        videoMemories.clear();
        dirty = true;
        saveData();
        Log.d(TAG, "记忆已清空");
    }

    /**
     * 删除指定视频记忆。
     *
     * @param descKey 视频简介的 key（前 50 字）
     * @return true 如果成功删除
     */
    public synchronized boolean removeVideo(String descKey) {
        VideoEntry removed = videoMemories.remove(descKey);
        if (removed != null) {
            dirty = true;
            saveData();
            Log.d(TAG, "已删除视频记忆: " +
                    (removed.description.length() > 30
                            ? removed.description.substring(0, 30) + "..." : removed.description));
            return true;
        }
        return false;
    }

    // ─── 持久化 ───

    private synchronized void saveData() {
        if (!dirty) return;
        try {
            JSONObject root = new JSONObject();
            root.put("version", 2);

            JSONArray videosArr = new JSONArray();
            for (VideoEntry entry : videoMemories.values()) {
                JSONObject videoObj = new JSONObject();
                videoObj.put("description", entry.description);

                JSONArray commentsArr = new JSONArray();
                for (Comment c : entry.comments.values()) {
                    JSONObject cObj = new JSONObject();
                    cObj.put("user", c.user());
                    cObj.put("text", c.text());
                    cObj.put("likes", c.likeCount());
                    cObj.put("time", c.time());
                    cObj.put("location", c.location());
                    commentsArr.put(cObj);
                }
                videoObj.put("comments", commentsArr);
                videosArr.put(videoObj);
            }
            root.put("videos", videosArr);

            File file = new File(appContext.getFilesDir(), DATA_FILE);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            dirty = false;
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

            // Check version for new format
            int version = root.optInt("version", 1);
            if (version >= 2) {
                // New format: grouped by video
                JSONArray videosArr = root.optJSONArray("videos");
                if (videosArr != null) {
                    for (int i = 0; i < videosArr.length(); i++) {
                        JSONObject videoObj = videosArr.getJSONObject(i);
                        String desc = videoObj.getString("description");
                        String descKey = desc.length() > 50 ? desc.substring(0, 50) : desc;

                        VideoEntry entry = new VideoEntry(desc);

                        JSONArray commentsArr = videoObj.optJSONArray("comments");
                        if (commentsArr != null) {
                            for (int j = 0; j < commentsArr.length(); j++) {
                                JSONObject cObj = commentsArr.getJSONObject(j);
                                Comment c = new Comment(
                                        cObj.optString("user", ""),
                                        cObj.optString("text", ""),
                                        cObj.optInt("likes", 0),
                                        cObj.optString("time", ""),
                                        cObj.optString("location", "")
                                );
                                String commentKey = c.user() + "|" + (c.text() != null ? c.text() : "");
                                entry.comments.put(commentKey, c);
                            }
                        }

                        videoMemories.put(descKey, entry);
                    }
                }
            } else {
                // Old format: flat lists - migrate to new format
                Log.d(TAG, "迁移旧格式记忆数据");

                JSONArray descArr = root.optJSONArray("descriptions");
                if (descArr != null) {
                    for (int i = 0; i < descArr.length(); i++) {
                        String desc = descArr.getString(i);
                        String descKey = desc.length() > 50 ? desc.substring(0, 50) : desc;
                        videoMemories.put(descKey, new VideoEntry(desc));
                    }
                }

                // Put old comments under a generic category
                JSONArray commentArr = root.optJSONArray("high_like_comments");
                if (commentArr != null && commentArr.length() > 0) {
                    VideoEntry targetEntry = videoMemories.isEmpty() ? null : videoMemories.values().iterator().next();
                    if (targetEntry == null) {
                        targetEntry = new VideoEntry("已收集评论");
                        videoMemories.put("已收集评论", targetEntry);
                    }

                    for (int i = 0; i < commentArr.length(); i++) {
                        JSONObject cObj = commentArr.getJSONObject(i);
                        Comment c = new Comment(
                                cObj.optString("user", ""),
                                cObj.optString("text", ""),
                                cObj.optInt("likes", 0),
                                cObj.optString("time", ""),
                                cObj.optString("location", "")
                        );
                        String commentKey = c.user() + "|" + (c.text() != null ? c.text() : "");
                        targetEntry.comments.put(commentKey, c);
                    }
                }

                // Save in new format
                dirty = true;
                saveData();
            }

            Log.d(TAG, "加载记忆数据: " + videoMemories.size() + " 个视频");
        } catch (Exception e) {
            Log.e(TAG, "加载记忆数据失败", e);
        }
    }
}
