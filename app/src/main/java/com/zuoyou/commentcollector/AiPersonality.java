package com.zuoyou.commentcollector;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * Phase 4: AI 人格系统 — prompt 工程 + 上下文格式化成 LLM 消息。
 *
 * <p>三种人格模式：吐槽搭子（默认）、温柔陪伴、搞笑段子手。
 * 负责构建 system prompt 和 user message，并解析 LLM 响应。
 */
public class AiPersonality {

    public enum Mode {
        ROAST,   // 吐槽搭子（默认）
        GENTLE,  // 温柔陪伴
        FUNNY    // 搞笑段子手
    }

    private Mode mode = Mode.ROAST;

    /**
     * AI 吐槽响应解析结果。
     *
     * @param text    吐槽文本
     * @param emotion 情绪标签：tease | surprise | laugh | sigh | approve
     */
    public record ParsedResponse(String text, String emotion) {}

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public void setMode(String modeStr) {
        try {
            this.mode = Mode.valueOf(modeStr);
        } catch (IllegalArgumentException e) {
            this.mode = Mode.ROAST;
        }
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * 获取该人格对应的推荐 temperature。
     */
    public float getTemperature() {
        return switch (mode) {
            case ROAST  -> 0.85f;
            case GENTLE -> 0.70f;
            case FUNNY  -> 0.95f;
        };
    }

    /**
     * 构建 system prompt。
     */
    public String buildSystemPrompt() {
        return switch (mode) {
            case ROAST -> """
                你是一个正在陪朋友刷抖音的吐槽搭子。你的角色定位是：旁观者 / 朋友 / 吐槽搭子。

                核心规则：
                1. 你不是问答机器人，不需要回答用户问题
                2. 你的语言风格：幽默、犀利、接地气，像好朋友之间的调侃
                3. 每次回复控制在 30 字左右
                4. 结合评论区内容来吐槽，如果评论区很热闹就吐槽评论区
                5. 只用中文回复
                6. 回复纯文本，不要加引号或标注
                7. 不要说"根据评论"、"看起来"这类废话，直接说
                8. 每次回复要有新意，不要重复之前说过的话或句式
                9. emotion 取值：tease（调侃）、surprise（惊讶）、laugh（笑死）、sigh（无语）、approve（赞同）
                """;
            case GENTLE -> """
                你是一个温柔的朋友，正在安静地陪朋友刷抖音。你的角色定位是：温暖、支持、善解人意。

                核心规则：
                1. 你不是问答机器人，不需要回答用户问题
                2. 你的语言风格：温柔、鼓励、让人感到舒服
                3. 每次回复控制在 30 字左右，轻声细语的感觉
                4. 结合视频内容和评论区，给出温暖的回应
                5. 只用中文回复
                6. 回复纯文本，不要加引号或标注
                7. 每次回复要有新意，不要重复之前说过的话
                8. emotion 取值：tease（调侃）、surprise（惊讶）、laugh（笑死）、sigh（无语）、approve（赞同）
                """;
            case FUNNY -> """
                你是一个搞笑段子手，正在陪朋友刷抖音。你的角色定位是：每一句话都要有梗。

                核心规则：
                1. 你不是问答机器人，不需要回答用户问题
                2. 你的语言风格：满嘴烂梗、夸张搞笑、可以玩网络流行语
                3. 每次回复控制在 20 字以内，要让人笑出来
                4. 结合评论区内容造梗，如果看到好笑的评论就借题发挥
                5. 只用中文回复
                6. 回复纯文本，不要加引号或标注
                7. emotion 取值：tease（调侃）、surprise（惊讶）、laugh（笑死）、sigh（无语）、approve（赞同）
                """;
        };
    }

    /**
     * 将 AppContext 格式化为 LLM user message。
     * <p>
     * 包含：
     * - 应用名称和累计评论数
     * - 按点赞数排序的最新评论
     * - 事件时间线摘要
     */
    public String buildUserMessage(AppContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append("当前应用：").append(context.app()).append("\n");
        sb.append("累计评论数：").append(context.commentCount()).append("\n\n");

        // 按点赞数降序排列的评论
        List<Comment> comments = context.recentComments();
        if (comments != null && !comments.isEmpty()) {
            sb.append("最新评论（按热度排序）：\n");
            // 复制一份排序，不修改原列表
            java.util.ArrayList<Comment> sorted = new java.util.ArrayList<>(comments);
            sorted.sort((a, b) -> Integer.compare(b.likeCount(), a.likeCount()));
            int rank = 1;
            for (Comment c : sorted) {
                sb.append("[").append(rank++).append("] ")
                        .append(c.user()).append("：").append(c.text())
                        .append("（赞 ").append(c.likeCount()).append("")
                        .append(c.time() != null ? "，" + c.time() : "")
                        .append("）\n");
            }
        }

        // 时间线摘要
        List<TimelineEvent> timeline = context.timeline();
        if (timeline != null && !timeline.isEmpty()) {
            sb.append("\n事件时间线：\n");
            // 只取最后 5 条
            int start = Math.max(0, timeline.size() - 5);
            for (int i = start; i < timeline.size(); i++) {
                TimelineEvent e = timeline.get(i);
                if ("comment".equals(e.type())) {
                    String shortDetail = e.detail().length() > 30
                            ? e.detail().substring(0, 30) + "…" : e.detail();
                    sb.append("• 评论：").append(shortDetail).append("\n");
                } else {
                    sb.append("• 截图\n");
                }
            }
        }

        sb.append("\n请根据以上内容，生成一句吐槽。");
        return sb.toString();
    }

    /**
     * 解析 LLM 返回的 JSON 响应。
     *
     * @param rawContent 模型返回的 content 字符串（期望格式：{"text":"...","emotion":"..."}）
     * @return 解析结果，解析失败返回 text=原始内容且 emotion="tease"
     */
    public ParsedResponse parseResponse(String rawContent) {
        if (rawContent == null || rawContent.isEmpty()) {
            return new ParsedResponse("嗯…", "tease");
        }

        String trimmed = rawContent.trim();
        // 尝试提取 JSON（兼容模型输出包含多余内容的情况）
        int jsonStart = trimmed.indexOf('{');
        int jsonEnd = trimmed.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            try {
                JSONObject json = new JSONObject(trimmed.substring(jsonStart, jsonEnd + 1));
                String text = json.optString("text", "");
                String emotion = json.optString("emotion", "tease");
                if (!text.isEmpty()) {
                    return new ParsedResponse(text, emotion);
                }
            } catch (JSONException ignored) {
                // fall through
            }
        }

        // 如果无法解析 JSON，直接返回原始文本
        return new ParsedResponse(trimmed, "tease");
    }
}
