package com.zuoyou.commentcollector.feature;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
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
    private MessageAdapter adapter;
    private EditText inputField;
    private TextView sendButton;
    private TextView titleView;

    private int sessionId = -1;
    private final List<ChatSessionManager.ChatMessage> messages = new ArrayList<>();
    private boolean waitingForReply = false;

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

        // 初始化视图
        titleView = findViewById(R.id.chatTitle);
        messageList = findViewById(R.id.chatMessageList);
        inputField = findViewById(R.id.chatInput);
        sendButton = findViewById(R.id.chatSendButton);
        ImageButton backButton = findViewById(R.id.chatBackButton);

        // 返回按钮
        backButton.setOnClickListener(v -> finish());

        // 标题
        titleView.setText("可不 · 聊天 #" + sessionId);

        // 消息列表
        adapter = new MessageAdapter();
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        messageList.setLayoutManager(lm);
        messageList.setAdapter(adapter);

        // 加载历史消息
        messages.addAll(sessionManager.loadSession(sessionId));
        adapter.notifyDataSetChanged();
        scrollToBottom();

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

    private void sendMessage() {
        String text = inputField.getText().toString().trim();
        if (TextUtils.isEmpty(text) || waitingForReply) return;

        // 保存用户消息
        sessionManager.saveMessage(sessionId, "user", text);
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

                // 保存 AI 回复
                sessionManager.saveMessage(sessionId, "assistant", reply);
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
        super.onDestroy();
        if (aiService != null) {
            aiService.cancel();
        }
    }

    // ─── 消息适配器 ───

    private static final int TYPE_USER = 0;
    private static final int TYPE_AI = 1;
    private static final int TYPE_TYPING = 2;

    private class MessageAdapter extends RecyclerView.Adapter<MessageViewHolder> {

        @NonNull
        @Override
        public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_USER) {
                View v = inflater.inflate(R.layout.item_chat_user, parent, false);
                return new MessageViewHolder(v);
            } else if (viewType == TYPE_TYPING) {
                View v = inflater.inflate(R.layout.item_chat_typing, parent, false);
                return new MessageViewHolder(v);
            } else {
                View v = inflater.inflate(R.layout.item_chat_ai, parent, false);
                return new MessageViewHolder(v);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
            ChatSessionManager.ChatMessage msg = messages.get(position);
            holder.textView.setText(msg.content());
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        @Override
        public int getItemViewType(int position) {
            String role = messages.get(position).role();
            if ("user".equals(role)) return TYPE_USER;
            if ("typing".equals(role)) return TYPE_TYPING;
            return TYPE_AI;
        }
    }

    private static class MessageViewHolder extends RecyclerView.ViewHolder {
        final TextView textView;

        MessageViewHolder(View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.messageText);
        }
    }
}
