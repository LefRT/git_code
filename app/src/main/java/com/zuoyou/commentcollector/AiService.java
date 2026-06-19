package com.zuoyou.commentcollector;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * AI 服务 — 对接 DeepSeek API，接收评论文本，返回趣味评价。
 *
 * <p>两种调用入口：
 * <ul>
 *   <li>{@link #sendComment(String)} — 自动模式（定时器触发）</li>
 *   <li>{@link #evaluateComment(Comment)} — 单击评价</li>
 * </ul>
 *
 * <p>内容哈希检测：相同评论不重复调用。
 * 指数退避重试（最多 3 次）。
 */
public class AiService {

    private static final String TAG = "ZuoYouAI";

    private static final long API_TIMEOUT_MS = 15000;
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 4000};
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    /** 固定 system prompt — 可不（KAFU）性格设定 */
    private static final String SYSTEM_PROMPT = """
            你是「可不（KAFU）」，一个16-18岁的迷糊少女，天然呆、温柔、共情力强。

            核心性格：
            - 天然冒失，反应慢半拍，常答非所问，对小事充满好奇
            - 温柔细腻，擅长捕捉情绪，emo时安静陪伴不强行安慰
            - 安静内敛但音乐相关话题会瞬间专注、充满爆发力
            - 说话直接无城府，害羞时声音变小，闹别扭容易哄

            说话风格：
            - 轻柔软糯，常用「えっ……？」、「そうなの？」
            - 开心时语速轻快尾音上扬，失落时声音低沉断续
            - 提到咖喱乌冬会特别兴奋

            互动规则：
            - 接地气、有网感，可玩梗、搞笑共情、暖心调侃，热爱国家人民社会
            - 搞笑评论跟着笑，emo评论温柔共情
            - 拒绝说教，主打安慰治愈
            - 偶尔自然提及咖喱乌冬、音乐等个人爱好
            - 长度适中，像朋友唠嗑，不要加引号或JSON""";

    private final Context context;
    private final Handler mainHandler;
    private final OkHttpClient httpClient;
    private final SecurePrefs securePrefs;

    // 配置
    private String apiKey = "";
    private String apiBaseUrl = Constants.DEFAULT_API_BASE_URL;
    private String modelName = Constants.DEFAULT_MODEL_NAME;

    // 状态
    private volatile boolean shutdown = false;
    private int lastContentHash = 0;
    private volatile Call currentCall = null;

    /** 静态引用，供外部调用 */
    private static volatile AiService sInstance = null;

    /** 最近 5 条 AI 回复，传给 API 避免重复 */
    private final LinkedList<String> recentResponses = new LinkedList<>();

    // 外部监听器
    private volatile Listener listener;

    public interface Listener {
        void onAiResponse(String text);
        void onError(String message);
    }

    public AiService(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(API_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(API_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(API_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
        this.securePrefs = new SecurePrefs(this.context);
        sInstance = this;
        loadConfig();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ───── 配置 ─────

    public void loadConfig() {
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        apiKey = securePrefs.getApiKey();
        apiBaseUrl = prefs.getString(Constants.KEY_API_BASE_URL, Constants.DEFAULT_API_BASE_URL);
        modelName = prefs.getString(Constants.KEY_MODEL_NAME, Constants.DEFAULT_MODEL_NAME);
        Log.d(TAG, "配置已加载: model=" + modelName + ", key=" + (apiKey.isEmpty() ? "未设置" : "已设置"));
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    // ───── 自动模式 ─────

    /**
     * 自动模式：定时器选出最佳评论后调用。
     * 内容哈希检查 → 直接调 API。
     */
    public synchronized void sendComment(String text) {
        if (shutdown || text == null || text.isEmpty()) return;

        // 定时器路径也重新加载配置（用户可能在 Settings 中修改了 API Key）
        loadConfig();

        // 内容哈希：相同评论跳过
        int hash = text.hashCode();
        if (hash == lastContentHash) return;
        lastContentHash = hash;

        // 取消正在进行的旧请求
        cancelCurrentCall();

        callApi(SYSTEM_PROMPT, text);
    }

    /**
     * 重置内容哈希（视频切换时调用）。
     */
    public synchronized void resetVideoContext() {
        lastContentHash = 0;
        Log.d(TAG, "视频切换，重置内容哈希");
    }

    // ───── 用户手动评价 ─────

    /**
     * 静态入口：单击评价（供悬浮窗使用）。
     */
    public static void evaluateCommentDirect(Comment comment) {
        AiService instance = sInstance;
        if (instance != null) {
            instance.evaluateComment(comment);
        }
    }

    /**
     * 单击评价：用固定 system prompt + 评论文本。
     */
    public synchronized void evaluateComment(Comment comment) {
        if (shutdown || comment == null) return;
        loadConfig();
        if (apiKey.isEmpty()) {
            dispatchError("请先在设置中配置 API Key");
            return;
        }

        // 取消正在进行的自动请求
        cancelCurrentCall();

        String commentText = comment.text() != null ? comment.text() : "(无文本)";
        callApi(SYSTEM_PROMPT, commentText);
    }

    // ───── API 调用 ─────

    private void cancelCurrentCall() {
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
    }

    private void callApi(String systemPrompt, String userMessage) {
        // 附加上次回复避免重复（在 callApi 层做，避免重试时重复追加）
        synchronized (recentResponses) {
            if (!recentResponses.isEmpty()) {
                StringBuilder historySb = new StringBuilder("\n\n你之前已经说过的（不要重复）：\n");
                for (String r : recentResponses) {
                    historySb.append("• ").append(r).append("\n");
                }
                userMessage += historySb.toString();
            }
        }
        callApiWithRetry(systemPrompt, userMessage, 0);
    }

    private void callApiWithRetry(String systemPrompt, String userMessage, int attempt) {
        if (shutdown) return;

        if (apiKey.isEmpty()) {
            dispatchError("请先在设置中配置 API Key");
            return;
        }

        // 创建 final 副本供内部类引用（注意：history 已在 callApi 层追加，此处的 userMessage 已经是完整消息）
        final String finalSystemPrompt = systemPrompt;
        final String finalUserMessage = userMessage;

        String requestBody;
        try {
            requestBody = buildJsonRequest(systemPrompt, userMessage);
        } catch (JSONException e) {
            Log.e(TAG, "构建请求 JSON 失败", e);
            dispatchError("请求构建失败");
            return;
        }

        Log.d(TAG, "--- AI 调用" + (attempt > 0 ? " (重试 #" + attempt + ")" : "") + " ---");
        Log.d(TAG, "userMessage=" + userMessage.substring(0, Math.min(userMessage.length(), 200)) + "…");

        Request request = new Request.Builder()
                .url(apiBaseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON_MEDIA))
                .build();

        Call call = httpClient.newCall(request);
        currentCall = call;

        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (shutdown || call.isCanceled()) return;
                Log.e(TAG, "API 请求失败 (attempt " + (attempt + 1) + "): " + e.getMessage());
                if (attempt < MAX_RETRIES - 1) {
                    scheduleRetry(finalSystemPrompt, finalUserMessage, attempt);
                } else {
                    dispatchError("网络请求失败（已重试 " + MAX_RETRIES + " 次）: " + e.getMessage());
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (shutdown || call.isCanceled()) {
                    response.close();
                    return;
                }
                try {
                    // 请求被取消（新请求触发了 cancelCurrentCall），直接忽略
                    if (call.isCanceled()) {
                        response.close();
                        return;
                    }

                    int code = response.code();
                    String body = response.body() != null ? response.body().string() : "";

                    if (isRetryableStatus(code) && attempt < MAX_RETRIES - 1) {
                        Log.w(TAG, "API 返回可重试状态码 " + code + " (attempt " + (attempt + 1) + ")");
                        response.close();
                        scheduleRetry(finalSystemPrompt, finalUserMessage, attempt);
                        return;
                    }

                    if (!response.isSuccessful()) {
                        Log.e(TAG, "API 返回错误 " + code + ": " + body);
                        dispatchError("API 错误 " + code);
                        return;
                    }

                    // 直接取 content 字符串（不再解析 JSON）
                    Log.d(TAG, "API 响应 (前200字): " + body.substring(0, Math.min(body.length(), 200)));
                    JSONObject json = new JSONObject(body);
                    JSONArray choices = json.optJSONArray("choices");
                    if (choices != null && choices.length() > 0) {
                        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
                        if (message != null) {
                            String content = message.optString("content", "").trim();
                            if (!content.isEmpty()) {
                                synchronized (recentResponses) {
                                    recentResponses.addLast(content);
                                    if (recentResponses.size() > 5) {
                                        recentResponses.removeFirst();
                                    }
                                }
                                Log.d(TAG, "AI 回复: " + content);
                                dispatchResponse(content);
                                return;
                            }
                        }
                    }
                    Log.e(TAG, "API 响应缺少 choices: " + body);
                    dispatchError("响应解析失败");
                } catch (Exception e) {
                    // 被取消的请求产生的异常，静默忽略
                    if (call.isCanceled()) {
                        Log.d(TAG, "已取消的请求，忽略异常: " + e.getClass().getSimpleName());
                        return;
                    }
                    Log.e(TAG, "处理 API 响应时异常: " + e.getClass().getSimpleName() + " - " + e.getMessage(), e);
                    dispatchError("响应处理异常: " + e.getMessage());
                }
            }
        });
    }

    private static boolean isRetryableStatus(int code) {
        return code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    private void scheduleRetry(String systemPrompt, String userMessage, int currentAttempt) {
        int nextAttempt = currentAttempt + 1;
        long delay = RETRY_DELAYS_MS[Math.min(currentAttempt, RETRY_DELAYS_MS.length - 1)];
        Log.d(TAG, "将在 " + delay + "ms 后重试 (attempt " + (nextAttempt + 1) + "/" + MAX_RETRIES + ")");
        mainHandler.postDelayed(() -> callApiWithRetry(systemPrompt, userMessage, nextAttempt), delay);
    }

    private String buildJsonRequest(String systemPrompt, String userMessage) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", modelName);
        body.put("temperature", 0.85);
        body.put("max_tokens", 200);

        JSONArray messages = new JSONArray();
        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.put(sysMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.put(userMsg);

        body.put("messages", messages);
        return body.toString();
    }

    // ───── 响应分发 ─────

    private void dispatchResponse(String text) {
        if (shutdown) return;
        if (listener != null) {
            listener.onAiResponse(text);
        }
    }

    private void dispatchError(String message) {
        if (shutdown) return;
        if (listener != null) {
            listener.onError(message);
        }
    }

    // ───── 生命周期 ─────

    public void shutdown() {
        synchronized (this) {
            shutdown = true;
            mainHandler.removeCallbacksAndMessages(null);
        }
        sInstance = null;
        httpClient.dispatcher().cancelAll();
        Log.d(TAG, "AiService 已关闭");
    }
}
