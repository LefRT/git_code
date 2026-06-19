package com.zuoyou.commentcollector.feature;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 聊天会话管理器 — JSON 文件读写聊天记录。
 * <p>
 * 文件结构：
 * <pre>
 *   getFilesDir()/chats/
 *     chat_index.json          ← 会话索引（避免全部加载）
 *     chat_1.json              ← 会话 1
 *     chat_2.json              ← 会话 2
 *     ...
 * </pre>
 *
 * <p>线程安全：所有公开方法通过 {@code synchronized(this)} 保护。
 */
public class ChatSessionManager {

    private static final String TAG = "ChatSession";
    private static final String DIR_NAME = "chats";
    private static final String INDEX_FILE = "chat_index.json";

    private final File chatDir;
    private final SimpleDateFormat timeFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    // ─── 内部数据结构 ───

    /** 单条聊天消息 */
    public static final class ChatMessage {
        private final String role;      // "user" 或 "assistant"
        private final String content;
        private final String timestamp;

        public ChatMessage(String role, String content, String timestamp) {
            this.role = role;
            this.content = content;
            this.timestamp = timestamp;
        }

        public String role()    { return role; }
        public String content() { return content; }
        public String timestamp() { return timestamp; }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("role", role);
            obj.put("content", content);
            obj.put("timestamp", timestamp);
            return obj;
        }

        public static ChatMessage fromJson(JSONObject obj) {
            return new ChatMessage(
                    obj.optString("role", "user"),
                    obj.optString("content", ""),
                    obj.optString("timestamp", "")
            );
        }
    }

    /** 会话摘要（索引用） */
    public static final class SessionInfo {
        private final int id;
        private String preview;       // 最新一条消息的前 30 字
        private int count;            // 消息总数
        private String updatedAt;

        public SessionInfo(int id, String preview, int count, String updatedAt) {
            this.id = id;
            this.preview = preview;
            this.count = count;
            this.updatedAt = updatedAt;
        }

        public int id()         { return id; }
        public String preview() { return preview; }
        public int count()      { return count; }
        public String updatedAt() { return updatedAt; }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("preview", preview);
            obj.put("count", count);
            obj.put("updated_at", updatedAt);
            return obj;
        }

        public static SessionInfo fromJson(JSONObject obj) {
            return new SessionInfo(
                    obj.optInt("id", 0),
                    obj.optString("preview", ""),
                    obj.optInt("count", 0),
                    obj.optString("updated_at", "")
            );
        }
    }

    // 索引缓存（内存）
    private final List<SessionInfo> indexCache = new ArrayList<>();
    private int nextId = 1;

    // ─── 构造 ───

    public ChatSessionManager(Context context) {
        chatDir = new File(context.getFilesDir(), DIR_NAME);
        if (!chatDir.exists()) {
            chatDir.mkdirs();
        }
        loadIndex();
        Log.d(TAG, "初始化完成，已有 " + indexCache.size() + " 个会话，nextId=" + nextId);
    }

    // ─── 公开方法 ───

    /**
     * 创建新会话，返回会话 ID。
     */
    public synchronized int createNewSession() {
        int id = nextId++;
        String now = timeFormat.format(new Date());
        SessionInfo info = new SessionInfo(id, "(新会话)", 0, now);
        indexCache.add(info);
        saveIndex();

        // 创建空的会话文件
        saveSessionFile(id, new ArrayList<>(), info);

        Log.d(TAG, "新建会话 #" + id);
        return id;
    }

    /**
     * 加载指定会话的全部消息。
     *
     * @param sessionId 会话 ID
     * @return 消息列表，会话不存在时返回空列表
     */
    public synchronized List<ChatMessage> loadSession(int sessionId) {
        File file = getSessionFile(sessionId);
        if (!file.exists()) {
            Log.w(TAG, "会话文件不存在: " + sessionId);
            return List.of();
        }

        try {
            String json = readFile(file);
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("messages");
            if (arr == null) return List.of();

            List<ChatMessage> messages = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                messages.add(ChatMessage.fromJson(arr.getJSONObject(i)));
            }
            return messages;
        } catch (JSONException e) {
            Log.e(TAG, "解析会话文件失败: " + sessionId, e);
            return List.of();
        }
    }

    /**
     * 向指定会话追加一条消息并持久化。
     *
     * @param sessionId 会话 ID
     * @param role      "user" 或 "assistant"
     * @param content   消息内容
     */
    public synchronized void saveMessage(int sessionId, String role, String content) {
        // 加载现有消息
        List<ChatMessage> messages = new ArrayList<>(loadSession(sessionId));
        String now = timeFormat.format(new Date());
        messages.add(new ChatMessage(role, content, now));

        // 更新索引
        SessionInfo info = findSession(sessionId);
        if (info == null) {
            Log.w(TAG, "会话不在索引中，自动创建: " + sessionId);
            info = new SessionInfo(sessionId, "", 0, now);
            indexCache.add(info);
        }
        info.count = messages.size();
        info.updatedAt = now;
        // 预览取最新消息前 30 字
        String lastContent = content;
        info.preview = lastContent.length() > 30
                ? lastContent.substring(0, 30) + "..."
                : lastContent;

        saveSessionFile(sessionId, messages, info);
        saveIndex();

        Log.d(TAG, "保存消息到会话 #" + sessionId + " (role=" + role + ", 总计 " + messages.size() + " 条)");
    }

    /**
     * 获取所有会话的摘要列表（按 ID 降序，最新在前）。
     */
    public synchronized List<SessionInfo> getSessionList() {
        List<SessionInfo> sorted = new ArrayList<>(indexCache);
        sorted.sort((a, b) -> Integer.compare(b.id, a.id));
        return Collections.unmodifiableList(sorted);
    }

    /**
     * 删除指定会话。
     *
     * @param sessionId 会话 ID
     * @return true 如果删除成功
     */
    public synchronized boolean deleteSession(int sessionId) {
        File file = getSessionFile(sessionId);
        boolean deleted = file.exists() && file.delete();

        indexCache.removeIf(s -> s.id == sessionId);
        saveIndex();

        Log.d(TAG, "删除会话 #" + sessionId + ": " + (deleted ? "成功" : "文件不存在"));
        return deleted;
    }

    // ─── 内部 ───

    private File getSessionFile(int sessionId) {
        return new File(chatDir, "chat_" + sessionId + ".json");
    }

    private SessionInfo findSession(int sessionId) {
        for (SessionInfo s : indexCache) {
            if (s.id == sessionId) return s;
        }
        return null;
    }

    private void saveSessionFile(int sessionId, List<ChatMessage> messages, SessionInfo info) {
        try {
            JSONObject root = new JSONObject();
            root.put("id", sessionId);
            root.put("message_count", messages.size());
            root.put("updated_at", info.updatedAt);

            JSONArray arr = new JSONArray();
            for (ChatMessage msg : messages) {
                arr.put(msg.toJson());
            }
            root.put("messages", arr);

            writeFile(getSessionFile(sessionId), root.toString(2));
        } catch (JSONException e) {
            Log.e(TAG, "序列化会话失败: " + sessionId, e);
        }
    }

    private void loadIndex() {
        File indexFile = new File(chatDir, INDEX_FILE);
        if (!indexFile.exists()) {
            Log.d(TAG, "索引文件不存在，从空开始");
            return;
        }

        try {
            String json = readFile(indexFile);
            JSONObject root = new JSONObject(json);
            nextId = root.optInt("next_id", 1);

            JSONArray arr = root.optJSONArray("sessions");
            if (arr != null) {
                indexCache.clear();
                for (int i = 0; i < arr.length(); i++) {
                    indexCache.add(SessionInfo.fromJson(arr.getJSONObject(i)));
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "解析索引文件失败", e);
        }
    }

    private void saveIndex() {
        try {
            JSONObject root = new JSONObject();
            root.put("next_id", nextId);

            JSONArray arr = new JSONArray();
            for (SessionInfo s : indexCache) {
                arr.put(s.toJson());
            }
            root.put("sessions", arr);

            writeFile(new File(chatDir, INDEX_FILE), root.toString(2));
        } catch (JSONException e) {
            Log.e(TAG, "序列化索引失败", e);
        }
    }

    private String readFile(File file) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "读取文件失败: " + file.getName(), e);
            return "";
        }
    }

    private void writeFile(File file, String content) {
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "写入文件失败: " + file.getName(), e);
        }
    }
}
