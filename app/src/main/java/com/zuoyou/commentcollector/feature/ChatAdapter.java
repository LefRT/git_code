package com.zuoyou.commentcollector.feature;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.zuoyou.commentcollector.R;

import java.util.List;

/**
 * 聊天消息适配器 — 3 种 viewType：用户消息、AI 消息、打字指示器。
 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.MessageViewHolder> {

    public static final int TYPE_USER = 0;
    public static final int TYPE_AI = 1;
    public static final int TYPE_TYPING = 2;

    private final List<ChatSessionManager.ChatMessage> messages;

    public ChatAdapter(List<ChatSessionManager.ChatMessage> messages) {
        this.messages = messages;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        int layoutRes;
        if (viewType == TYPE_USER) {
            layoutRes = R.layout.item_chat_user;
        } else if (viewType == TYPE_TYPING) {
            layoutRes = R.layout.item_chat_typing;
        } else {
            layoutRes = R.layout.item_chat_ai;
        }
        View v = inflater.inflate(layoutRes, parent, false);
        return new MessageViewHolder(v);
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

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        final TextView textView;

        MessageViewHolder(View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.messageText);
        }
    }
}
