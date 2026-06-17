package com.zuoyou.commentcollector;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * CommentParser 单元测试 — 覆盖抖音评论区 contentDescription 的各种格式。
 */
public class CommentParserTest {

    // ── 正常格式 ──

    @Test
    public void parseFromDescription_normalFormat() {
        String desc = "MiSS,我有六万存款，月薪七千，能结婚不,昨天19:09, · 广东,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("MiSS", c.user());
        assertEquals("我有六万存款，月薪七千，能结婚不", c.text());
        assertEquals("昨天19:09", c.time());
        assertEquals("· 广东", c.location());
        assertEquals(0, c.likeCount());
    }

    @Test
    public void parseFromDescription_noCommentText() {
        String desc = "北疯之神,1天前, · 广西,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("北疯之神", c.user());
        assertNull(c.text());
        assertEquals("1天前", c.time());
        assertEquals("· 广西", c.location());
    }

    @Test
    public void parseFromDescription_noLocation() {
        String desc = "用户A,这是一条评论,3分钟前,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("用户A", c.user());
        assertEquals("这是一条评论", c.text());
        assertEquals("3分钟前", c.time());
        assertEquals("", c.location());
    }

    @Test
    public void parseFromDescription_noTextNoLocation() {
        String desc = "用户B,5小时前,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("用户B", c.user());
        assertNull(c.text());
        assertEquals("5小时前", c.time());
        assertEquals("", c.location());
    }

    @Test
    public void parseFromDescription_justNow() {
        String desc = "用户C,刚刚,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("用户C", c.user());
        assertNull(c.text());
        assertEquals("刚刚", c.time());
    }

    @Test
    public void parseFromDescription_secondsAgo() {
        String desc = "用户D,好有趣,30秒前,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("用户D", c.user());
        assertEquals("好有趣", c.text());
        assertEquals("30秒前", c.time());
    }

    @Test
    public void parseFromDescription_absoluteDate() {
        String desc = "用户E,哈哈哈,06-15 14:30, · 北京,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("用户E", c.user());
        assertEquals("哈哈哈", c.text());
        assertEquals("06-15 14:30", c.time());
        assertEquals("· 北京", c.location());
    }

    @Test
    public void parseFromDescription_fullDate() {
        String desc = "用户F,2025-01-01 08:00, · 上海,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("用户F", c.user());
        assertNull(c.text());
        assertEquals("2025-01-01 08:00", c.time());
        assertEquals("· 上海", c.location());
    }

    @Test
    public void parseFromDescription_todayTime() {
        String desc = "用户G,今天真开心,今天10:30,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("用户G", c.user());
        assertEquals("今天真开心", c.text());
        assertEquals("今天10:30", c.time());
    }

    @Test
    public void parseFromDescription_commaInText() {
        String desc = "评论达人,我有六万存款，月薪七千，能结婚不,昨天19:09, · 广东,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("评论达人", c.user());
        assertEquals("我有六万存款，月薪七千，能结婚不", c.text());
    }

    @Test
    public void parseFromDescription_longTime() {
        String desc = "用户H,999天前,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("用户H", c.user());
        assertEquals("999天前", c.time());
    }

    // ── 边界 / 异常 ──

    @Test
    public void parseFromDescription_null_returnsNull() {
        assertNull(CommentParser.parseFromDescription(null));
    }

    @Test
    public void parseFromDescription_empty_returnsNull() {
        assertNull(CommentParser.parseFromDescription(""));
    }

    @Test
    public void parseFromDescription_noSuffix_returnsNull() {
        assertNull(CommentParser.parseFromDescription("一些内容没有后缀"));
    }

    @Test
    public void parseFromDescription_onlySuffix() {
        // 只有 "回复 按钮," 没有实际内容
        Comment c = CommentParser.parseFromDescription("回复 按钮,");
        assertNull(c);
    }

    @Test
    public void parseFromDescription_userOnlyWithSuffix() {
        // 用户名 + 后缀，没有时间
        Comment c = CommentParser.parseFromDescription("用户X,回复 按钮,");
        assertNull(c);
    }

    @Test
    public void parseFromDescription_emptyUser() {
        // 空用户名
        Comment c = CommentParser.parseFromDescription(",评论内容,3分钟前,回复 按钮,");
        // 空用户名应返回 null（user.isEmpty() 检查）
        // 但实际解析逻辑：firstComma=0, user="", text="评论内容"
        // 然后 user.isEmpty() → return null
        assertNull(c);
    }

    @Test
    public void parseFromDescription_specialCharsInUser() {
        String desc = "@小明_123,不错不错,2小时前, · 浙江,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("@小明_123", c.user());
        assertEquals("不错不错", c.text());
    }

    @Test
    public void parseFromDescription_locationWithoutDot() {
        String desc = "用户Z,内容,昨天18:00,广东,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("广东", c.location());
    }

    @Test
    public void parseFromDescription_simpleTime() {
        // HH:mm 格式（今天的时间）
        String desc = "用户Q,14:30,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("用户Q", c.user());
        assertEquals("14:30", c.time());
    }

    // ── parseFromDescriptions 批量 ──

    @Test
    public void parseFromDescriptions_emptyList() {
        List<Comment> result = CommentParser.parseFromDescriptions(Collections.emptyList());
        assertTrue(result.isEmpty());
    }

    @Test
    public void parseFromDescriptions_mixed() {
        List<String> descs = Arrays.asList(
                "用户A,好评论,3分钟前,回复 按钮,",
                "无效内容",
                "用户B,昨天19:09, · 广东,回复 按钮,"
        );
        List<Comment> result = CommentParser.parseFromDescriptions(descs);
        assertEquals(2, result.size());
        assertEquals("用户A", result.get(0).user());
        assertEquals("用户B", result.get(1).user());
    }

    @Test
    public void parseFromDescriptions_allInvalid() {
        List<String> descs = Arrays.asList("无效1", "无效2", null);
        List<Comment> result = CommentParser.parseFromDescriptions(descs);
        assertTrue(result.isEmpty());
    }

    // ── 用户名中的数字不应被误识别为时间 ──

    @Test
    public void parseFromDescription_digitsInUsername() {
        String desc = "User2024,这是一条测试评论,3小时前,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("User2024", c.user());
        assertEquals("这是一条测试评论", c.text());
        assertEquals("3小时前", c.time());
    }

    @Test
    public void parseFromDescription_numericUsername() {
        String desc = "12345,纯数字用户名,5分钟前,回复 按钮,";
        Comment c = CommentParser.parseFromDescription(desc);
        assertNotNull(c);
        assertEquals("12345", c.user());
        assertEquals("纯数字用户名", c.text());
        assertEquals("5分钟前", c.time());
    }
}
