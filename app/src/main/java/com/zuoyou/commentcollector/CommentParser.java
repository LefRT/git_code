package com.zuoyou.commentcollector;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯文本评论解析器，不依赖任何 Android 框架。
 *
 * 输入：页面上所有节点 contentDescription 的列表（FrameLayout 级别）
 * 输出：提取到的评论列表
 *
 * 抖音评论区每条评论是一个 FrameLayout，其 contentDescription 格式为：
 *   [用户名],[评论内容(可选)],[时间],[地点],回复 按钮,
 *
 * 例如：
 *   "MiSS,我有六万存款，月薪七千，能结婚不,昨天19:09, · 广东,回复 按钮,"
 *   "北疯之神,1天前, · 广西,回复 按钮,"  ← 没有评论内容
 */
public class CommentParser {

    // 时间格式：昨天HH:MM, 今天HH:MM, X分钟前, X小时前, X天前, 刚刚
    // 重要：昨天/今天必须和后面的 HH:MM 连在一起匹配，避免只匹配到"昨天"而把 :MM 留在 text 里
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(昨天\\d{2}:\\d{2}|今天\\d{2}:\\d{2}|刚刚|\\d+分钟前|\\d+小时前|\\d+天前|\\d+秒前|\\d{1,2}-\\d{2}\\s+\\d{2}:\\d{2}|\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}|\\d{2}:\\d{2})"
    );

    /**
     * 从单条 contentDescription 中提取评论。
     *
     * @param desc 包含 "回复 按钮," 的 contentDescription 字符串
     * @return 提取到的 Comment，格式异常时返回 null
     */
    public static Comment parseFromDescription(String desc) {
        if (desc == null || !desc.endsWith("回复 按钮,")) return null;

        try {
            String body = desc.substring(0, desc.length() - "回复 按钮,".length()).trim();
            if (body.endsWith(",")) body = body.substring(0, body.length() - 1).trim();

            // 在后 2/3 区域搜索时间戳（避免用户名中的数字干扰）
            int searchStart = body.length() / 3;
            String searchRegion = body.substring(searchStart);

            Matcher matcher = TIME_PATTERN.matcher(searchRegion);
            int relativeStart = -1;
            while (matcher.find()) {
                relativeStart = matcher.start();
            }

            if (relativeStart < 0) return null;

            int timeStart = searchStart + relativeStart;

            // 解析用户名 + 评论内容（时间戳之前的部分）
            String userAndText = body.substring(0, timeStart).trim();
            if (userAndText.endsWith(",")) userAndText = userAndText.substring(0, userAndText.length() - 1).trim();

            int firstComma = userAndText.indexOf(",");
            String user, text;
            if (firstComma < 0) {
                user = userAndText;
                text = null;
            } else {
                user = userAndText.substring(0, firstComma).trim();
                text = userAndText.substring(firstComma + 1).trim();
                if (text.isEmpty()) text = null;
            }

            if (user.isEmpty()) return null;

            // 解析时间 + 位置（时间戳之后的部分）
            String timeAndLocation = body.substring(timeStart).trim();
            if (timeAndLocation.endsWith(",")) {
                timeAndLocation = timeAndLocation.substring(0, timeAndLocation.length() - 1).trim();
            }
            String[] tlParts = timeAndLocation.split(",", 2);
            String time = tlParts[0].trim();
            String location = tlParts.length > 1 ? tlParts[1].trim() : "";

            return new Comment(user, text, 0, time, location);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 contentDescription 列表中提取评论。
     *
     * @param descriptions 所有包含 "回复 按钮," 的 contentDescription 列表
     * @return 提取到的评论列表
     */
    public static List<Comment> parseFromDescriptions(List<String> descriptions) {
        List<Comment> comments = new ArrayList<>();

        for (String desc : descriptions) {
            Comment c = parseFromDescription(desc);
            if (c != null) comments.add(c);
        }

        return comments;
    }
}
