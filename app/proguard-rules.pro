# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# =====================================================
# 左右 (ZuoYou) — ProGuard / R8 规则
# =====================================================

# JSON 反射 — Comment record 的字段会被 JSONObject 反射访问
-keepclassmembers class com.zuoyou.commentcollector.Comment {
    *;
}

# 无障碍服务 — 系统通过 Binder 调用，防止混淆
-keep class com.zuoyou.commentcollector.DouyinCommentService { *; }

# MediaProjection 回调 — 系统回调，防止混淆
-keepclassmembers class * extends android.media.projection.MediaProjection$Callback {
    *;
}

# ScreenCaptureService — 前台服务，通过 Intent Action 调用
-keep class com.zuoyou.commentcollector.ScreenCaptureService { *; }

# XML 资源引用的类（无障碍配置）
-keep class com.zuoyou.commentcollector.** { *; }
