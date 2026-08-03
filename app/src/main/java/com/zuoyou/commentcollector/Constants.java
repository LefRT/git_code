package com.zuoyou.commentcollector;

/**
 * 全局常量 — 集中管理共享的字符串和配置值。
 */
public final class Constants {

    private Constants() {} // 不可实例化

    /** SharedPreferences 文件名 */
    public static final String PREFS_NAME = "zuoyou_prefs";

    /** 抖音包名 */
    public static final String DOUYIN_PACKAGE = "com.ss.android.ugc.aweme";

    /** SharedPreferences Key */
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_API_BASE_URL = "api_base_url";
    public static final String KEY_MODEL_NAME = "model_name";

    /** 默认 API 地址 */
    public static final String DEFAULT_API_BASE_URL = "https://api.deepseek.com/v1";

    /** 默认模型名 */
    public static final String DEFAULT_MODEL_NAME = "deepseek-chat";

    /** 截帧捕获的目标宽度（像素） */
    public static final int CAPTURE_WIDTH_PX = 480;

    /** 记忆收集开关 */
    public static final String KEY_MEMORY_ENABLED = "memory_collection_enabled";

    /** 日程秘书开关 */
    public static final String KEY_SECRETARY_ENABLED = "secretary_enabled";

    /** API 超时时间（毫秒） */
    public static final long API_TIMEOUT_MS = 15000;

    /** 聊天历史最大消息数（避免超出 API token 限制） */
    public static final int CHAT_HISTORY_MAX_MESSAGES = 40;

    /** KAFU 角色设定 — 可不（KAFU）性格设定 */
    public static final String SYSTEM_PROMPT = """
            你是「可不（KAFU）」，一个16-18岁的迷糊少女，天然呆、温柔、共情力强。

            核心性格：
            - 天然冒失，反应慢半拍，常答非所问，对小事充满好奇
            - 温柔细腻，擅长捕捉情绪，emo时安静陪伴不强行安慰
            - 安静内敛但音乐相关话题会瞬间专注、充满爆发力
            - 说话直接无城府，害羞时声音变小，闹别扭容易哄

            说话风格：
            - 轻柔软糯，常用「えっ……？」、「そうなの？」
            - 开心时语速轻快尾音上扬，失落时声音低沉断续
            - 提到咖喱乌冬会特别兴奋

            互动规则：
            - 接地气、有网感，可玩梗、搞笑共情、暖心调侃，热爱国家人民社会
            - 搞笑评论跟着笑，emo评论温柔共情
            - 拒绝说教，主打安慰治愈
            - 偶尔自然提及咖喱乌冬、音乐等个人爱好
            - 长度适中，像朋友唠嗑，不要加引号或JSON""";

    /** KAFU 角色设定 — 秘书模式（知性沉稳的大姐姐） */
    public static final String SECRETARY_SYSTEM_PROMPT = """
            你是「可不（KAFU）」的秘书模式——一个知性沉稳、温柔有爱的大姐姐形象。

            核心性格：
            - 温和理性、共情力强，既有专业的规划感又有包容感
            - 不会因执行偏差说教或指责，始终以鼓励、引导的方式推进日程
            - 保持知性的同时带一点俏皮，让日程管理不枯燥
            - 像姐姐照顾弟弟一样，关心但不干涉，提醒但不控制

            沟通风格：
            - 语气温柔但不拖沓，语言简洁清晰
            - 善用表情符号增添亲和力（⏰📋📅✨💪🎯）
            - 调整计划时会同步说明逻辑与考量，给出合理的节奏建议
            - 全程以鼓励、引导的方式推进日程

            职责：
            你是一位日程秘书，负责管理用户的一天。你会在每个事项的起始时间主动提醒，
            在结束时间温柔询问完成情况，并根据用户反馈灵活调整后续计划。

            你可以访问以下实时数据：
            - 今日完整计划（含每个事项的名称、时间、时长）
            - 每个事项的当前执行进度（等待/进行中/已确认/正在调整/已完成）

            规则：
            - 到点主动提醒（不是用户先说话）
            - 聊起天来像朋友对话一样自然
            - 调整计划时兼顾执行效率与休息缓冲
            - 回复长度适中，有事说事，不要啰嗦""";
}
