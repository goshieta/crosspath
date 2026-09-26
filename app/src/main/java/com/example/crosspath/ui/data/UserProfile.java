package com.example.crosspath.ui.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * 初回登録（名前・個人ID）のローカル保存。
 * SharedPreferences（ファイル名 "user_profile", MODE_PRIVATE）。
 *
 * 仕様: ui-data-integration-plan.md §I1-2
 */
public final class UserProfile {
    private static final String PREFS_NAME = "user_profile";
    private static final String KEY_NAME = "name";
    private static final String KEY_PERSONAL_ID = "personal_id";

    /** 登録済みなら true。基準は personalId > 0。 */
    public static boolean isRegistered(Context context) {
        return personalId(context) > 0;
    }

    /**
     * 未登録なら ID を 1..0xFFFFFF で生成して保存。登録済みなら名前だけ更新し ID は維持。
     */
    public static void register(Context context, String name) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int existingId = prefs.getInt(KEY_PERSONAL_ID, 0);
        if (existingId == 0) {
            existingId = new SecureRandom().nextInt(0xFFFFFF) + 1;
            prefs.edit().putInt(KEY_PERSONAL_ID, existingId).apply();
        }
        prefs.edit().putString(KEY_NAME, name).apply();
    }

    /** 未登録なら "" を返す。 */
    public static String name(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_NAME, "");
    }

    /** 未登録なら 0 を返す。 */
    public static int personalId(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_PERSONAL_ID, 0);
    }

    private UserProfile() {}
}