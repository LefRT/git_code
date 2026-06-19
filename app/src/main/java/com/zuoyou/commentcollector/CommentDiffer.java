package com.zuoyou.commentcollector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 评论对比器 — 对比两轮评论列表，输出新增评论 + 最佳评分。
 *
 * <p>三态判断：
 * <ul>
 *   <li>无更新：新列表与上轮完全相同</li>
 *   <li>部分更新：有重叠但也有新增</li>
 *   <li>完全更新：无任何重叠</li>
 * </ul>
 *
 * <p>最佳评论评分公式：
 * <pre>
 * score = 0.6 × log(likes+1)/log(maxLikes+2) + 0.4 × min(textLen,50)/50
 * </pre>
 */
public final class CommentDiffer {

    // 评分权重
    private static final double LIKE_WEIGHT = 0.6;
    private static final double LENGTH_WEIGHT = 0.4;
    private static final int LENGTH_CAP = 50;

    private CommentDiffer() {}

    /**
     * 对比新旧评论列表，返回对比结果。
     *
     * @param oldList 上一轮的评论列表
     * @param newList 当前轮提取的评论列表
     * @return 对比结果（包含新增列表 + 最佳评论）
     */
    public static DiffResult diff(List<Comment> oldList, List<Comment> newList) {
        if (oldList == null) oldList = List.of();
        if (newList == null) newList = List.of();

        // 构建旧评论的 key 集合
        Set<String> oldKeys = new HashSet<>();
        for (Comment c : oldList) {
            oldKeys.add(makeKey(c));
        }

        // 筛选新增评论
        List<Comment> newComments = new ArrayList<>();
        for (Comment c : newList) {
            if (!oldKeys.contains(makeKey(c))) {
                newComments.add(c);
            }
        }

        // 判断变化状态
        DiffStatus status;
        if (newComments.isEmpty()) {
            status = DiffStatus.NO_UPDATE;
        } else if (newComments.size() == newList.size()) {
            // 新列表中所有评论都不在旧列表中 → 完全更新
            status = DiffStatus.FULL_UPDATE;
        } else {
            status = DiffStatus.PARTIAL_UPDATE;
        }

        // 从新增评论中选出最佳
        Comment best = selectBest(newComments);

        return new DiffResult(status, newComments, best);
    }

    /**
     * 计算单条评论的综合评分。
     *
     * @param comment    待评分评论
     * @param maxLikes   本批新增评论中的最高点赞数（用于归一化）
     * @return 0~1 的评分
     */
    public static double score(Comment comment, int maxLikes) {
        // 点赞分：对数归一化
        double likeScore = Math.log(comment.likeCount() + 1)
                / Math.log(Math.max(maxLikes, 1) + 2);

        // 长度分：封顶 50 字
        String text = comment.text() != null ? comment.text() : "";
        double lengthScore = Math.min(text.length(), LENGTH_CAP) / (double) LENGTH_CAP;

        return LIKE_WEIGHT * likeScore + LENGTH_WEIGHT * lengthScore;
    }

    /**
     * 从评论列表中选出评分最高的一条。
     *
     * @param comments 候选评论列表
     * @return 最佳评论，列表为空时返回 null
     */
    public static Comment selectBest(List<Comment> comments) {
        if (comments == null || comments.isEmpty()) return null;
        if (comments.size() == 1) return comments.get(0);

        // 找到最高点赞数（用于归一化）
        int maxLikes = 0;
        for (Comment c : comments) {
            if (c.likeCount() > maxLikes) {
                maxLikes = c.likeCount();
            }
        }

        Comment best = null;
        double bestScore = -1;
        for (Comment c : comments) {
            double s = score(c, maxLikes);
            if (s > bestScore) {
                bestScore = s;
                best = c;
            }
        }
        return best;
    }

    private static String makeKey(Comment c) {
        return c.user() + "|" + (c.text() != null ? c.text() : "");
    }

    // ───── 结果类型 ─────

    public enum DiffStatus {
        /** 新列表与上轮完全相同 */
        NO_UPDATE,
        /** 有重叠但也有新增 */
        PARTIAL_UPDATE,
        /** 无任何重叠，全部是新评论 */
        FULL_UPDATE
    }

    public record DiffResult(
            DiffStatus status,
            List<Comment> newComments,
            Comment bestComment
    ) {}
}
