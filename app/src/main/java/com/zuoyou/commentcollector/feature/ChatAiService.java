package com.zuoyou.commentcollector.feature;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.zuoyou.commentcollector.Constants;
import com.zuoyou.commentcollector.SecurePrefs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 聊天 AI 服务 — 基于 DeepSeek API 的完整对话调用。
 *
 * <p>与 {@link com.zuoyou.commentcollector.AiService} 的区别：
 * <ul>
 *   <li>支持完整消息历史（多轮对话）</li>
 *   <li>支持记忆上下文注入</li>
 *   <li>单次请求，不重试</li>
 * </ul>
 *
 * <p>复用相同 SharedPreferences 的 API 配置（api_key, api_base_url, model_name）。
 */
public class ChatAiService {

    private static final String TAG = "ChatAiService";
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");

    private final Context appContext;
    private final Handler mainHandler;
    private final OkHttpClient httpClient;
    private final SecurePrefs securePrefs;
    private volatile Call currentCall = null;
    private String customSystemPrompt = null;  // null = 使用默认 KAFU 人格

    /** 回调接口 */
    public interface ChatCallback {
        void onResponse(String reply);
        void onError(String error);
    }

    public ChatAiService(Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Constants.API_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(Constants.API_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(Constants.API_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
        this.securePrefs = new SecurePrefs(this.appContext);
    }

    /**
     * 设置自定义系统提示词。设为 null 恢复默认 KAFU 人格。
     */
    public void setSystemPrompt(String prompt) {
        this.customSystemPrompt = prompt;
    }

    /**
     * 发送聊天消息。
     *
     * @param sessionId   当前会话 ID（用于加载历史消息）
     * @param userMessage 用户输入的消息
     * @param callback    结果回调（在主线程）
     */
    public void chat(int sessionId, String userMessage, ChatCallback callback) {
        // 加载配置
        SharedPreferences prefs = appContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        String apiKey = securePrefs.getApiKey();
        String apiBaseUrl = prefs.getString(Constants.KEY_API_BASE_URL, Constants.DEFAULT_API_BASE_URL);
        String modelName = prefs.getString(Constants.KEY_MODEL_NAME, Constants.DEFAULT_MODEL_NAME);

        if (apiKey.isEmpty()) {
            mainHandler.post(() -> callback.onError("请先在设置中配置 API Key"));
            return;
        }

        // 构建消息数组
        JSONArray messages = new JSONArray();
        try {
            // 1. system: 角色设定（使用自定义或默认 KAFU 人格）
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", customSystemPrompt != null ? customSystemPrompt : Constants.SYSTEM_PROMPT);
            messages.put(sysMsg);

            // 2. system: 记忆上下文（如果开启）
            MemoryCollector mem = MemoryCollector.getInstance();
            if (mem != null && mem.isEnabled()) {
                String memoryCtx = mem.buildMemoryContext();
                if (!memoryCtx.isEmpty()) {
                    JSONObject memMsg = new JSONObject();
                    memMsg.put("role", "system");
                    memMsg.put("content", memoryCtx);
                    messages.put(memMsg);
                }
            }

            // 3. 历史消息（最近 N 条，避免超出 API token 限制）
            ChatSessionManager sessionMgr = ChatSessionManager.getInstance(appContext);
            List<ChatSessionManager.ChatMessage> history = sessionMgr.loadSession(sessionId);
            int start = Math.max(0, history.size() - Constants.CHAT_HISTORY_MAX_MESSAGES);
            for (int i = start; i < history.size(); i++) {
                ChatSessionManager.ChatMessage msg = history.get(i);
                JSONObject histMsg = new JSONObject();
                histMsg.put("role", msg.role());
                histMsg.put("content", msg.content());
                messages.put(histMsg);
            }

            // 4. user: 当前消息
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", userMessage);
            messages.put(userMsg);

        } catch (JSONException e) {
            Log.e(TAG, "构建消息数组失败", e);
            mainHandler.post(() -> callback.onError("请求构建失败"));
            return;
        }

        // 构建请求体
        JSONObject body = new JSONObject();
        try {
            body.put("model", modelName);
            body.put("temperature", 0.85);
            body.put("max_tokens", 500);
            body.put("messages", messages);
        } catch (JSONException e) {
            Log.e(TAG, "构建请求 JSON 失败", e);
            mainHandler.post(() -> callback.onError("请求构建失败"));
            return;
        }

        // 取消旧请求
        if (currentCall != null) {
            currentCall.cancel();
        }

        String requestBody = body.toString();
        Log.d(TAG, "发送聊天请求 (" + messages.length() + " 条消息)");

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
                if (call.isCanceled()) return;
                Log.e(TAG, "API 请求失败: " + e.getMessage());
                mainHandler.post(() -> callback.onError("网络请求失败: " + e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (call.isCanceled()) {
                    response.close();
                    return;
                }
                try {
                    int code = response.code();
                    String responseBody = response.body() != null ? response.body().string() : "";

                    if (!response.isSuccessful()) {
                        Log.e(TAG, "API 返回错误 " + code + ": " + responseBody);
                        // 尝试解析 DeepSeek/OpenAI 兼容的错误响应体
                        String errorMsg = "API 错误 " + code;
                        try {
                            JSONObject errObj = new JSONObject(responseBody).optJSONObject("error");
                            if (errObj != null) {
                                String msg = errObj.optString("message", "");
                                if (!msg.isEmpty()) {
                                    errorMsg = msg;
                                }
                            }
                        } catch (JSONException ignored) {}
                        String finalMsg = errorMsg;
                        mainHandler.post(() -> callback.onError(finalMsg));
                        return;
                    }

                    JSONObject json = new JSONObject(responseBody);
                    JSONArray choices = json.optJSONArray("choices");
                    if (choices != null && choices.length() > 0) {
                        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
                        if (message != null) {
                            String content = message.optString("content", "").trim();
                            if (!content.isEmpty()) {
                                Log.d(TAG, "AI 回复: " + (content.length() > 60 ? content.substring(0, 60) + "..." : content));
                                mainHandler.post(() -> callback.onResponse(content));
                                return;
                            }
                        }
                    }
                    Log.e(TAG, "API 响应缺少 choices: " + responseBody);
                    mainHandler.post(() -> callback.onError("响应解析失败"));
                } catch (Exception e) {
                    if (call.isCanceled()) return;
                    Log.e(TAG, "处理 API 响应异常", e);
                    mainHandler.post(() -> callback.onError("响应处理异常: " + e.getMessage()));
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * 取消当前请求。
     */
    public void cancel() {
        if (currentCall != null) {
            currentCall.cancel();
            currentCall = null;
        }
    }
}
