package com.example.crosspath.ui.data;

import android.content.Context;
import android.content.SharedPreferences;
import com.example.crosspath.data.WireRecord;
import com.example.crosspath.registration.RegistrationProvider;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Durable local identity; UI getters use only a worker-loaded snapshot. */
public final class UserProfile {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile int id;
    private static volatile String displayName = "";
    private static CompletableFuture<Void> loading;

    public static synchronized CompletableFuture<Void> load(Context context) {
        Context app = context.getApplicationContext();
        if (loading == null || loading.isCompletedExceptionally()) {
            loading = CompletableFuture.runAsync(() -> {
                SharedPreferences prefs = preferences(app);
                int stored = prefs.getInt("personal_id", 0);
                if (stored != 0) WireRecord.requireUserId(stored);
                if (stored != 0 && !RegistrationProvider.HAS_DEBUG_INPUT
                        && !"SERVER".equals(prefs.getString("identity_source", ""))) {
                    throw new IllegalStateException("旧版の未検証IDです。本人IDの移行方法を確認してください。");
                }
                displayName = prefs.getString("name", "");
                id = stored;
            }, IO);
        }
        return loading;
    }

    public static CompletableFuture<Integer> register(Context context, String name, String debugId) {
        Context app = context.getApplicationContext();
        return load(app).thenApplyAsync(ignored -> {
            if (id != 0) return id;
            if (name == null || name.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
                throw new IllegalArgumentException("名前を入力してください");
            }
            SharedPreferences prefs = preferences(app);
            String request = prefs.getString("request_id", null);
            String pendingName = prefs.getString("request_name", name);
            String pendingId = prefs.getString("request_debug_id", debugId);
            if (request == null) {
                request = UUID.randomUUID().toString();
            }
            {
                if (!prefs.edit().putString("request_id", request).putString("request_name", pendingName)
                        .putString("request_debug_id", pendingId).commit()) {
                    throw new IllegalStateException("登録要求を保存できません。空き容量を確認して再試行してください。");
                }
            }
            try {
                int assigned = RegistrationProvider.gateway().register(request, pendingName, pendingId);
                WireRecord.requireUserId(assigned);
                if (!prefs.edit().putInt("personal_id", assigned).putString("name", pendingName)
                        .putString("identity_source", RegistrationProvider.HAS_DEBUG_INPUT ? "DEBUG" : "SERVER").commit()) {
                    throw new IllegalStateException("本人IDを保存できません。同じ登録要求で再試行してください。");
                }
                displayName = pendingName;
                id = assigned;
                return assigned;
            } catch (IllegalArgumentException invalid) {
                prefs.edit().remove("request_id").remove("request_name").remove("request_debug_id").commit();
                throw invalid;
            } catch (Exception failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }, IO);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("user_profile", Context.MODE_PRIVATE);
    }
    public static boolean isRegistered(Context context) { return id > 0; }
    public static int personalId(Context context) { return id; }
    public static String name(Context context) { return displayName; }
    private UserProfile() {}
}
