package com.example.crosspath.ui.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 初回登録（名前・個人ID）のローカル保存。
 * SharedPreferences（ファイル名 "user_profile", MODE_PRIVATE）。
 *
 * personal_id は {@link com.example.crosspath.registration.PrefsRegistrationStore} が管理するため、
 * このクラスは personal_id の読み取りと name の保存のみを行う。
 * 乱数ID生成（旧実装）は廃止し、サーバー採番の user_id を PrefsRegistrationStore 経由で保存する。
 * 仕様: crosspath-registration-spec.md §4
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
     * 名前のみを保存する。
     * personal_id は変更しない（サーバーからの採番は {@link com.example.crosspath.registration.RegistrationRepository} が行う）。
     *
     * @param context Context
     * @param name 保存する名前
     */
    public static void storeName(Context context, String name) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_NAME, name).apply();
    }

    /**
     * 互換性のため残すメソッド。名前のみ保存する。
     * 旧実装では乱数IDを生成していたが、現在はサーバー採番方式に移行したため
     * このメソッドは storeName に委譲する。
     *
     * @deprecated 代わりに {@link #storeName(Context, String)} を使用してください。
     */
    @Deprecated
    public static void register(Context context, String name) {
        storeName(context, name);
    }

    /** 未登録なら "" を返す。 */
    public static String name(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_NAME, "");
    }

    /**
     * 保存された個人IDを返す。
     * 実際の保存は {@link com.example.crosspath.registration.PrefsRegistrationStore} が行うが、
     * 同じ SharedPreferences（user_profile）の personal_id キーを使うため互換性がある。
     * 未登録なら 0 を返す。
     */
    public static int personalId(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_PERSONAL_ID, 0);
    }

    private UserProfile() {}
}