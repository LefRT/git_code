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
 * Phase 4: AI 服务调度器 — 串联 ContextBuilder 与 DeepSeek API。
 *
 * <p>职责：
 * <ul>
 *   <li>作为 {@link ContextBuilder.Listener} 接收上下文更新</li>
 *   <li>2s 防抖 + 8s 冷却，避免频繁调用 API</li>
 *   <li>内容哈希检测：如果评论没变化就不重复调用</li>
 *   <li>记忆最近 5 条 AI 回复，避免重复生成</li>
 *   <li>通过 OkHttp 异步调用 DeepSeek API（兼容 OpenAI 格式）</li>
 *   <li>指数退避重试（最多 3 次）</li>
 *   <li>解析响应并通过 {@link Listener} 分发</li>
 * </ul>
 */
public class AiService implements ContextBuilder.Listener {

    private static final String TAG = "ZuoYouAI";

    // 防抖 & 冷却（毫秒）
    private static final long DEBOUNCE_MS = 2000;
    private static final long COOLDOWN_MS = 8000;
    private static final long API_TIMEOUT_MS = 15000;

    // 重试参数
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 4000};

    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final Context context;
    private final Handler mainHandler;
    private final OkHttpClient httpClient;
    private final AiPersonality personality;
    private final SecurePrefs securePrefs;

    // 配置缓存
    private String apiKey = "";
    private String apiBaseUrl = Constants.DEFAULT_API_BASE_URL;
    private String modelName = Constants.DEFAULT_MODEL_NAME;

    // 状态
    private AppContext latestContext;
    private long lastApiCallTime = 0;
    private boolean pendingDebounce = false;
    private volatile boolean shutdown = false;

    /** 上次发送给 API 的 userMessage 哈希，相同则不重复调用 */
    private int lastContentHash = 0;

    /** 最近 5 条 AI 回复，传给 API 避免重复 */
    private final LinkedList<String> recentResponses = new LinkedList<>();

    // 内部 runnable（在构造函数中初始化以避免自引用）
    private final Runnable debounceRunnable;

    // 外部监听器
    private volatile Listener listener;

    public interface Listener {
        /** AI 生成了一句吐槽 */
        void onAiResponse(String text, String emotion);
        /** AI 服务出错 */
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
        this.personality = new AiPersonality();
        this.securePrefs = new SecurePrefs(this.context);
        this.debounceRunnable = createDebounceRunnable();
        loadConfig();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public AiPersonality getPersonality() {
        return personality;
    }

    // ───── 配置加载 ─────

    public void loadConfig() {
        SharedPreferences prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        apiKey = securePrefs.getApiKey();
        apiBaseUrl = prefs.getString(Constants.KEY_API_BASE_URL, Constants.DEFAULT_API_BASE_URL);
        modelName = prefs.getString(Constants.KEY_MODEL_NAME, Constants.DEFAULT_MODEL_NAME);
        String personalityStr = prefs.getString(Constants.KEY_PERSONALITY, Constants.DEFAULT_PERSONALITY);
        personality.setMode(personalityStr);
        Log.d(TAG, "配置已加载: model=" + modelName + ", personality=" + personality.getMode()
                + ", key=" + (apiKey.isEmpty() ? "未设置" : "已设置"));
    }

    public boolean isConfigured() {
        return !apiKey.isEmpty();
    }

    /**
     * 重置内容哈希与冷却——在发生窗口切换（翻视频）时调用。
     * 确保新视频的评论不会被旧缓存跳过。
     */
    public synchronized void resetVideoContext() {
        lastContentHash = 0;
        lastApiCallTime = 0;
        Log.d(TAG, "视频切换，重置内容哈希/冷却");
    }

    // ───── ContextBuilder.Listener ─────

    @Override
    public synchronized void onContextUpdated(AppContext appContext) {
        if (shutdown) return;

        // 计算内容哈希，判断评论是否有实质性变化
        int newHash = contentHash(appContext);
        if (newHash == lastContentHash) {
            // 评论没有变化，跳过本次触发
            return;
        }
        lastContentHash = newHash;

        latestContext = appContext;

        // 取消上一次的防抖
        if (pendingDebounce) {
            mainHandler.removeCallbacks(debounceRunnable);
        }

        // 重新计时 2s 防抖
        pendingDebounce = true;
        mainHandler.postDelayed(debounceRunnable, DEBOUNCE_MS);
    }

    /**
     * 对 AppContext 中的评论内容计算摘要哈希。
     * 只有评论数量或内容变化时才改变。
     */
    private static int contentHash(AppContext ctx) {
        if (ctx == null) return 0;
        int h = ctx.commentCount();
        var comments = ctx.recentComments();
        if (comments != null) {
            // 用最近 5 条评论的 (user, text, likes) 来做哈希
            int count = Math.min(comments.size(), 5);
            for (int i = comments.size() - count; i < comments.size(); i++) {
                Comment c = comments.get(i);
                h = 31 * h + c.user().hashCode();
                h = 31 * h + (c.text() != null ? c.text().hashCode() : 0);
                h = 31 * h + c.likeCount();
            }
        }
        return h;
    }

    private Runnable createDebounceRunnable() {
        final Runnable[] selfRef = new Runnable[1];
        selfRef[0] = () -> {
            synchronized (AiService.this) {
                pendingDebounce = false;
                if (shutdown) return;

                long now = System.currentTimeMillis();
                long elapsed = now - lastApiCallTime;

                if (elapsed < COOLDOWN_MS) {
                    long wait = COOLDOWN_MS - elapsed;
                    Log.d(TAG, "冷却中，等待 " + wait + "ms 后重试");
                    pendingDebounce = true;
                    mainHandler.postDelayed(selfRef[0], wait);
                    return;
                }
            }

            callApi();
        };
        return selfRef[0];
    }

    // ───── API 调用 ─────

    private void callApi() {
        callApiWithRetry(0);
    }

    /**
     * 带指数退避重试的 API 调用。
     *
     * @param attempt 当前重试次数（0 = 首次调用）
     */
    private void callApiWithRetry(int attempt) {
        if (shutdown) return;

        loadConfig();

        AppContext ctx;
        synchronized (this) {
            ctx = latestContext;
            if (ctx == null) return;
        }

        if (apiKey.isEmpty()) {
            Log.w(TAG, "API Key 未配置，跳过 AI 调用");
            dispatchError("请先在设置中配置 API Key");
            return;
        }

        String systemPrompt = personality.buildSystemPrompt();

        // 附加上次回复作为「已说过的话」，避免重复
        String userMessage = personality.buildUserMessage(ctx);
        synchronized (recentResponses) {
            if (!recentResponses.isEmpty()) {
                StringBuilder historySb = new StringBuilder("\n\n你之前已经说过的吐槽（不要重复这些）：\n");
                for (String r : recentResponses) {
                    historySb.append("• ").append(r).append("\n");
                }
                userMessage += historySb.toString();
            }
        }

        String requestBody;
        try {
            requestBody = buildJsonRequest(systemPrompt, userMessage);
        } catch (JSONException e) {
            Log.e(TAG, "构建请求 JSON 失败", e);
            dispatchError("请求构建失败");
            return;
        }

        Log.d(TAG, "--- 发起 AI 调用" + (attempt > 0 ? " (重试 #" + attempt + ")" : "") + " ---");
        Log.d(TAG, "userMessage=" + userMessage.substring(0, Math.min(userMessage.length(), 200)) + "…");

        synchronized (this) {
            lastApiCallTime = System.currentTimeMillis();
        }

        Request request = new Request.Builder()
                .url(apiBaseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, JSON_MEDIA))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (shutdown) return;
                Log.e(TAG, "API 请求失败 (attempt " + (attempt + 1) + "): " + e.getMessage());
                if (attempt < MAX_RETRIES - 1) {
                    scheduleRetry(attempt);
                } else {
                    dispatchError("网络请求失败（已重试 " + MAX_RETRIES + " 次）: " + e.getMessage());
                }
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (shutdown) return;
                try {
                    int code = response.code();
                    String body = response.body() != null ? response.body().string() : "";

                    // 可重试的状态码：429(限流) / 500 / 502 / 503 / 504
                    if (isRetryableStatus(code) && attempt < MAX_RETRIES - 1) {
                        Log.w(TAG, "API 返回可重试状态码 " + code + " (attempt " + (attempt + 1) + ")");
                        response.close();
                        scheduleRetry(attempt);
                        return;
                    }

                    if (!response.isSuccessful()) {
                        Log.e(TAG, "API 返回错误 " + code + ": " + body);
                        dispatchError("API 错误 " + code);
                        return;
                    }

                    JSONObject json = new JSONObject(body);
                    JSONArray choices = json.optJSONArray("choices");
                    if (choices != null && choices.length() > 0) {
                        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
                        if (message != null) {
                            String content = message.optString("content", "");
                            AiPersonality.ParsedResponse parsed = personality.parseResponse(content);

                            // 记录回复到历史，避免下次重复
                            synchronized (recentResponses) {
                                recentResponses.addLast(parsed.text());
                                if (recentResponses.size() > 5) {
                                    recentResponses.removeFirst();
                                }
                            }

                            Log.d(TAG, "AI 吐槽 [" + parsed.emotion() + "]: " + parsed.text());
                            dispatchResponse(parsed.text(), parsed.emotion());
                            return;
                        }
                    }
                    Log.e(TAG, "API 响应缺少 choices: " + body);
                    dispatchError("响应解析失败");
                } catch (Exception e) {
                    Log.e(TAG, "处理 API 响应时异常", e);
                    dispatchError("响应处理异常");
                }
            }
        });
    }

    /**
     * 判断 HTTP 状态码是否可重试。
     */
    private static boolean isRetryableStatus(int code) {
        return code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    /**
     * 调度一次延迟重试。
     */
    private void scheduleRetry(int currentAttempt) {
        int nextAttempt = currentAttempt + 1;
        long delay = RETRY_DELAYS_MS[Math.min(currentAttempt, RETRY_DELAYS_MS.length - 1)];
        Log.d(TAG, "将在 " + delay + "ms 后重试 (attempt " + (nextAttempt + 1) + "/" + MAX_RETRIES + ")");
        mainHandler.postDelayed(() -> callApiWithRetry(nextAttempt), delay);
    }

    private String buildJsonRequest(String systemPrompt, String userMessage) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", modelName);
        body.put("temperature", personality.getTemperature());
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

    private void dispatchResponse(String text, String emotion) {
        if (shutdown) return;
        if (listener != null) {
            listener.onAiResponse(text, emotion);
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
        httpClient.dispatcher().cancelAll();
        Log.d(TAG, "AiService 已关闭");
    }
}
