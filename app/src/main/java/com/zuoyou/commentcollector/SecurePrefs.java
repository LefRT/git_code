package com.zuoyou.commentcollector;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

/**
 * 加密偏好存储封装 — 专门用于安全存储 API Key。
 *
 * <p>使用 AndroidX Security Crypto 的 EncryptedSharedPreferences，
 * API Key 以 AES-256 加密后存储，root 设备也无法直接读取明文。
 *
 * <p>兼容迁移：首次使用时自动将旧明文 SharedPreferences 中的 API Key
 * 迁移到加密存储，并清除旧值。
 */
public class SecurePrefs {

    private static final String ENCRYPTED_PREFS_NAME = "zuoyou_secure_prefs";
    private static final String KEY_API_KEY = Constants.KEY_API_KEY;

    private final SharedPreferences encryptedPrefs;
    private final SharedPreferences plainPrefs;

    public SecurePrefs(Context context) {
        this.plainPrefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);

        SharedPreferences tempEncrypted;
        try {
            String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
            tempEncrypted = EncryptedSharedPreferences.create(
                    ENCRYPTED_PREFS_NAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            // 加密不可用时回退到明文（极端情况）
            android.util.Log.w("SecurePrefs", "EncryptedSharedPreferences 初始化失败，回退到明文存储", e);
            tempEncrypted = null;
        }
        this.encryptedPrefs = tempEncrypted;

        // 迁移：如果旧明文中有 API Key，转移到加密存储
        migrateIfNeeded();
    }

    /**
     * 获取 API Key（优先从加密存储读取）。
     */
    public String getApiKey() {
        if (encryptedPrefs != null) {
            return encryptedPrefs.getString(KEY_API_KEY, "");
        }
        return plainPrefs.getString(KEY_API_KEY, "");
    }

    /**
     * 保存 API Key（写入加密存储，清除旧明文）。
     */
    public void saveApiKey(String apiKey) {
        if (encryptedPrefs != null) {
            encryptedPrefs.edit().putString(KEY_API_KEY, apiKey).apply();
            // 清除旧明文中的 API Key
            plainPrefs.edit().remove(KEY_API_KEY).apply();
        } else {
            plainPrefs.edit().putString(KEY_API_KEY, apiKey).apply();
        }
    }

    /**
     * 将旧明文 SharedPreferences 中的 API Key 迁移到加密存储。
     */
    private void migrateIfNeeded() {
        if (encryptedPrefs == null) return;

        String plainKey = plainPrefs.getString(KEY_API_KEY, "");
        if (!plainKey.isEmpty()) {
            String encryptedKey = encryptedPrefs.getString(KEY_API_KEY, "");
            if (encryptedKey.isEmpty()) {
                // 迁移
                encryptedPrefs.edit().putString(KEY_API_KEY, plainKey).apply();
                plainPrefs.edit().remove(KEY_API_KEY).apply();
                android.util.Log.d("SecurePrefs", "API Key 已从明文迁移到加密存储");
            }
        }
    }
}
