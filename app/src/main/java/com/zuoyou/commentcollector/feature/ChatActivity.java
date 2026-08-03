package com.zuoyou.commentcollector.feature;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.zuoyou.commentcollector.R;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天界面 — 全屏聊天 Activity。
 * <p>
 * 接收 {@code sessionId} intent extra（-1 = 新建会话）。
 * 布局：顶栏 → 角色头像 → 消息列表 → 输入框。
 */
public class ChatActivity extends AppCompatActivity {

    private static final String TAG = "ChatActivity";
    public static final String EXTRA_SESSION_ID = "session_id";

    private ChatSessionManager sessionManager;
    private ChatAiService aiService;

    private RecyclerView messageList;
    private ChatAdapter adapter;
    private EditText inputField;
    private TextView sendButton;
    private TextView titleView;

    private int sessionId = -1;
    private final List<ChatSessionManager.ChatMessage> messages = new ArrayList<>();
    private boolean waitingForReply = false;
    private boolean sessionLoaded = false;
    private boolean isSecretarySession = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        sessionManager = ChatSessionManager.getInstance(this);
        aiService = new ChatAiService(this);

        // 获取 sessionId
        sessionId = getIntent().getIntExtra(EXTRA_SESSION_ID, -1);
        if (sessionId == -1) {
            sessionId = sessionManager.createNewSession();
            Log.d(TAG, "新建会话 #" + sessionId);
        } else {
            Log.d(TAG, "加载会话 #" + sessionId);
        }

        // 检测是否为秘书会话
        checkSecretarySession();
        ScheduleSecretaryService.setSecretaryChatVisible(isSecretarySession);

        // 初始化视图
        titleView = findViewById(R.id.chatTitle);
        messageList = findViewById(R.id.chatMessageList);
        inputField = findViewById(R.id.chatInput);
        sendButton = findViewById(R.id.chatSendButton);
        ImageButton backButton = findViewById(R.id.chatBackButton);

        // 返回按钮
        backButton.setOnClickListener(v -> finish());

        // 标题
        titleView.setText(isSecretarySession ? "📅 日程秘书" : "可不 · 聊天 #" + sessionId);

        // 秘书会话：灰色背景
        if (isSecretarySession) {
            View root = findViewById(android.R.id.content);
            root.setBackgroundColor(ContextCompat.getColor(this, R.color.secretary_chat_bg));
        }

        // 消息列表
        adapter = new ChatAdapter(messages);
        adapter.setSecretaryMode(isSecretarySession);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        messageList.setLayoutManager(lm);
        messageList.setAdapter(adapter);

        // 异步加载历史消息（避免主线程 I/O 阻塞）
        sendButton.setEnabled(false);
        sessionManager.loadSessionAsync(sessionId, loaded -> {
            messages.addAll(loaded);
            adapter.notifyDataSetChanged();
            scrollToBottom();
            sessionLoaded = true;
            sendButton.setEnabled(true);
        });

        // 发送按钮
        sendButton.setOnClickListener(v -> sendMessage());

        // 键盘发送
        inputField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
    }

    private void checkSecretarySession() {
        List<ChatSessionManager.SessionInfo> sessions = sessionManager.getSessionList();
        for (ChatSessionManager.SessionInfo s : sessions) {
            if (s.id() == sessionId && "secretary".equals(s.type())) {
                isSecretarySession = true;
                break;
            }
        }
    }

    private void sendMessage() {
        String text = inputField.getText().toString().trim();
        if (TextUtils.isEmpty(text) || waitingForReply || !sessionLoaded) return;

        // 异步保存用户消息（不阻塞 UI）
        sessionManager.saveMessageAsync(sessionId, "user", text, success -> {
            if (!success) Log.e(TAG, "保存用户消息失败");
        });
        messages.add(new ChatSessionManager.ChatMessage("user", text, ""));
        adapter.notifyItemInserted(messages.size() - 1);
        scrollToBottom();

        inputField.setText("");
        setWaiting(true);

        // 添加打字指示器
        ChatSessionManager.ChatMessage typingIndicator = new ChatSessionManager.ChatMessage("typing", "", "");
        messages.add(typingIndicator);
        adapter.notifyItemInserted(messages.size() - 1);
        scrollToBottom();

        // 调用 AI
        aiService.chat(sessionId, text, new ChatAiService.ChatCallback() {
            @Override
            public void onResponse(String reply) {
                // 移除打字指示器
                removeTypingIndicator();

                // 异步保存 AI 回复
                sessionManager.saveMessageAsync(sessionId, "assistant", reply, success -> {
                    if (!success) Log.e(TAG, "保存 AI 回复失败");
                });
                messages.add(new ChatSessionManager.ChatMessage("assistant", reply, ""));
                adapter.notifyItemInserted(messages.size() - 1);
                scrollToBottom();
                setWaiting(false);
            }

            @Override
            public void onError(String error) {
                removeTypingIndicator();

                // 显示错误消息
                String errorText = "（出错了: " + error + "）";
                messages.add(new ChatSessionManager.ChatMessage("assistant", errorText, ""));
                adapter.notifyItemInserted(messages.size() - 1);
                scrollToBottom();
                setWaiting(false);
                Log.e(TAG, "AI 错误: " + error);
            }
        });
    }

    private void removeTypingIndicator() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("typing".equals(messages.get(i).role())) {
                messages.remove(i);
                adapter.notifyItemRemoved(i);
                break;
            }
        }
    }

    private void setWaiting(boolean waiting) {
        waitingForReply = waiting;
        sendButton.setAlpha(waiting ? 0.5f : 1f);
        inputField.setEnabled(!waiting);
    }

    private void scrollToBottom() {
        if (!messages.isEmpty()) {
            messageList.post(() -> messageList.smoothScrollToPosition(messages.size() - 1));
        }
    }

    @Override
    protected void onDestroy() {
        ScheduleSecretaryService.setSecretaryChatVisible(false);
        super.onDestroy();
        if (aiService != null) {
            aiService.cancel();
        }
    }

}
