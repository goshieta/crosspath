package com.example.crosspath.registration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 最小限のJSON生成・抽出ユーティリティ。仕様: crosspath-registration-spec.md §3.2
 *
 * org.json を使わず、文字列連結と正規表現で実装する。
 * android.* には依存しない（JVM でテスト可能）。
 */
public final class RegistrationJson {

    private RegistrationJson() {}

    /**
     * リクエストボディを生成する。"request_id" のみの簡潔なJSON。
     *
     * @param requestId UUID v4 文字列（エスケープ不要）
     * @return  {"request_id":"<requestId>"}
     */
    public static String requestBody(String requestId) {
        return "{\"request_id\":\"" + requestId + "\"}";
    }

    /**
     * JSON から指定キーの文字列値を抽出する。ネスト不可。
     *
     * @param json JSON 文字列
     * @param key  キー名
     * @return 値（見つからない/パース失敗時は null）
     */
    public static String string(String json, String key) {
        if (json == null || key == null) return null;
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * JSON から指定キーの整数値を抽出する。
     *
     * @param json JSON 文字列
     * @param key  キー名
     * @return 値（見つからない/パース失敗時は null）
     */
    public static Integer integer(String json, String key) {
        if (json == null || key == null) return null;
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * JSON から指定キーの真偽値を抽出する。
     *
     * @param json JSON 文字列
     * @param key  キー名
     * @return 値（見つからない時は null）
     */
    public static Boolean bool(String json, String key) {
        if (json == null || key == null) return null;
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)");
        Matcher m = p.matcher(json);
        if (m.find()) {
            return Boolean.parseBoolean(m.group(1));
        }
        return null;
    }

    /**
     * ネストしたオブジェクトから文字列値を抽出する。例: nestedString(json, "error", "code")
     * で {"error":{"code":"X"}} から "X" を取り出す。
     *
     * @param json  JSON 文字列
     * @param outer 外側のキー名
     * @param key   内側のキー名
     * @return 値（見つからない時は null）
     */
    public static String nestedString(String json, String outer, String key) {
        if (json == null || outer == null || key == null) return null;
        // 外側キーの値オブジェクトを大雑把に抜き出す
        Pattern outerP = Pattern.compile("\"" + Pattern.quote(outer) + "\"\\s*:\\s*\\{");
        Matcher outerM = outerP.matcher(json);
        if (!outerM.find()) return null;

        int braceStart = outerM.end() - 1; // position of '{'
        int depth = 1;
        int braceEnd = braceStart + 1;
        while (depth > 0 && braceEnd < json.length()) {
            char c = json.charAt(braceEnd);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            braceEnd++;
        }
        if (depth != 0) return null;
        String inner = json.substring(braceStart, braceEnd);
        return string(inner, key);
    }
}