package com.zuoyou.commentcollector;

/**
 * 评论数据类（Java 17 record）。
 *
 * @param user     用户名
 * @param text     评论内容（可能为 null，当评论仅包含点赞/回复时）
 * @param likeCount 点赞数
 * @param time     相对时间（如 "昨天19:09"、"3分钟前"）
 * @param location 位置信息（如 " · 广东"），可能为空
 */
public record Comment(
    String user,
    String text,
    int likeCount,
    String time,
    String location
) {
    /**
     * 返回点赞数更新后的新 Comment 实例（record 不可变，故返回新对象）。
     */
    public Comment withLikeCount(int likeCount) {
        return new Comment(user, text, likeCount, time, location);
    }

    @Override
    public String toString() {
        return "Comment{user='" + user + "', text='" + text
                + "', likes=" + likeCount + ", time='" + time
                + "', location='" + location + "'}";
    }
}
