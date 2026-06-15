package com.zuoyou.commentcollector;

public class Comment {
    public String user;
    public String text;
    public int likeCount;

    public Comment() {}

    public Comment(String user, String text) {
        this.user = user;
        this.text = text;
    }

    @Override
    public String toString() {
        return "Comment{user='" + user + "', text='" + text + "', likes=" + likeCount + "}";
    }
}
