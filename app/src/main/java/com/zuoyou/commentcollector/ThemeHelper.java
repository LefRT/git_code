package com.zuoyou.commentcollector;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.core.content.ContextCompat;

/**
 * 主题工具类 — 提供夜间模式颜色查询。
 *
 * XML 布局通过 values/colors.xml + values-night/colors.xml 自动切换。
 * Java 代码中硬编码的颜色通过此类获取当前主题下的正确颜色。
 */
public final class ThemeHelper {

    private static final String PREF_NAME = "theme_prefs";
    private static final String KEY_DARK_MODE = "dark_mode_enabled";

    private ThemeHelper() {}

    /** 是否处于夜间模式 */
    public static boolean isDarkMode(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_DARK_MODE, false); // 默认：灯亮 = 日间模式
    }

    /** 保存夜间模式状态 */
    public static void setDarkMode(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK_MODE, enabled).apply();
    }

    /** 获取当前主题下的颜色资源 */
    public static int color(Context ctx, int colorResId) {
        return ContextCompat.getColor(ctx, colorResId);
    }
}
