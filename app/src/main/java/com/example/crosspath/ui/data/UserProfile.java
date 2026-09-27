package com.example.crosspath.ui.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.crosspath.data.WireRecord;
import com.example.crosspath.registration.RegistrationProvider;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 初回登録（名前・個人ID）のローカル保存と、UI 向けスナップショット。
 * SharedPreferences（ファイル名 "user_profile", MODE_PRIVATE）。
 *
 * personal_id は IDサーバーが採番した値（{@code identity_source = "SERVER"}）のみを保存する。
 * 端末内で乱数を生成する旧実装は廃止した。
 * 通信は {@link RegistrationProvider} の gateway（本番は IDサーバー接続）が行い、
 * 結果は {@link com.example.crosspath.registration.PrefsRegistrationStore} と同じ prefs に保存される。
 * 仕様: crosspath-registration-spec.md §4
 *
 * Durable local identity; UI getters use only a worker-loaded snapshot.
 */
public final class UserProfile {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile int id;
    private static volatile String displayName = "";
    private static CompletableFuture<Void> loading;

    /** 保存済みの名前・個人IDを worker で読み込み、UI 用スナップショットを作る。 */
    public static synchronized CompletableFuture<Void> load(Context context) {
        Context app = context.getApplicationContext();
        if (loading == null || loading.isCompletedExceptionally()) {
            loading = CompletableFuture.runAsync(() -> {
                SharedPreferences prefs = preferences(app);
                int stored = prefs.getInt("personal_id", 0);
                if (stored != 0) WireRecord.requireUserId(stored);
                // 移行ガード: IDサーバー採番（SERVER）以外の personal_id は受け付けない
                if (stored != 0 && !"SERVER".equals(prefs.getString("identity_source", ""))) {
                    throw new IllegalStateException("旧版の未検証IDです。本人IDの移行方法を確認してください。");
                }
                displayName = prefs.getString("name", "");
                id = stored;
            }, IO);
        }
        return loading;
    }

    /**
     * 初回登録を非同期で実行する（登録済みなら保存済みの個人IDを返す）。
     *
     * request_id（UUID v4）は**通信前に**保存し、再試行では同じ値を使い回す（冪等性）。
     * 失敗時は request_id を残したまま例外になるため、同じ要求で再試行できる。
     *
     * @param context Context
     * @param name    表示名
     * @return 採番された個人ID
     */
    public static CompletableFuture<Integer> register(Context context, String name) {
        Context app = context.getApplicationContext();
        return load(app).thenApplyAsync(ignored -> {
            if (id != 0) return id;
            if (name == null || name.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
                throw new IllegalArgumentException("名前を入力してください");
            }
            SharedPreferences prefs = preferences(app);
            String request = prefs.getString("request_id", null);
            String pendingName = prefs.getString("request_name", name);
            if (request == null) {
                request = UUID.randomUUID().toString();
            }
            if (!prefs.edit().putString("request_id", request).putString("request_name", pendingName).commit()) {
                throw new IllegalStateException("登録要求を保存できません。空き容量を確認して再試行してください。");
            }
            try {
                int assigned = RegistrationProvider.gateway(app).register(request, pendingName);
                WireRecord.requireUserId(assigned);
                if (!prefs.edit().putInt("personal_id", assigned).putString("name", pendingName)
                        .putString("identity_source", "SERVER").commit()) {
                    throw new IllegalStateException("本人IDを保存できません。同じ登録要求で再試行してください。");
                }
                displayName = pendingName;
                id = assigned;
                return assigned;
            } catch (IllegalArgumentException invalid) {
                prefs.edit().remove("request_id").remove("request_name").commit();
                throw invalid;
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, IO);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("user_profile", Context.MODE_PRIVATE);
    }

    /** 登録済みなら true。基準は worker が読み込んだ個人ID > 0。 */
    public static boolean isRegistered(Context context) { return id > 0; }

    /** 保存された個人IDを返す。未登録なら 0。 */
    public static int personalId(Context context) { return id; }

    /** 保存された名前を返す。未登録なら ""。 */
    public static String name(Context context) { return displayName; }

    private UserProfile() {}
}
