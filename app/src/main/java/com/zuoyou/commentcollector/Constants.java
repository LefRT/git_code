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
}
