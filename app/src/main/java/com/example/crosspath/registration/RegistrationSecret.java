package com.example.crosspath.registration;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 登録シークレットの生成と検証。仕様: crosspath-registration-spec.md §3.1
 *
 * 32バイト乱数を Base64URL エンコード（パディングなし）して43文字の文字列にする。
 * android.* には依存しない（JVM でテスト可能）。
 */
public final class RegistrationSecret {
    private static final int SECRET_BYTES = 32;
    private static final int SECRET_STRING_LENGTH = 43;

    private RegistrationSecret() {}

    /**
     * 新しい秘密鍵を生成する。
     *
     * @return 43文字の base64url 文字列（パディングなし）
     */
    public static String generate() {
        SecureRandom random = new SecureRandom();
        byte[] raw = new byte[SECRET_BYTES];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * 文字列が有効なシークレット形式かを検証する。
     * 43文字・base64url 文字のみ・デコードして32バイト。
     *
     * @param s 検証する文字列
     * @return 有効なら true
     */
    public static boolean isValidFormat(String s) {
        if (s == null || s.length() != SECRET_STRING_LENGTH) {
            return false;
        }
        // base64url 文字のみかチェック（英数字 + '-' + '_'）
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_')) {
                return false;
            }
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(s);
            return decoded.length == SECRET_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}