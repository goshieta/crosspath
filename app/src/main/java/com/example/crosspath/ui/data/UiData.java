package com.example.crosspath.ui.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.crosspath.data.ActiveSession;
import com.example.crosspath.data.AppDatabase;
import com.example.crosspath.data.SafetyRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * アプリ寿命のデータアクセス口。UI から SafetyRepository への唯一の経路。
 * Room を直接触らせず、非同期処理とメインスレッド復帰を一元管理する。
 *
 * 仕様: ui-data-integration-plan.md §I1-1
 */
public final class UiData {
    private static final String TAG = "UiData";

    private static volatile SafetyRepository repository;
    private static volatile boolean initialized;
    /** DB 用の単一スレッド Executor。init で1度だけ作り、whenReady でも使い回す（スレッドを増やさない）。 */
    private static volatile Executor ioExecutor;
    private static final AtomicBoolean timerActive = new AtomicBoolean(false);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Object lock = new Object();
    private static final List<Consumer<SafetyRepository>> pendingCallbacks = new ArrayList<>();

    /**
     * 冪等。application context を渡す。io スレッド上で AppDatabase / Executor を生成する。
     * 生成完了までは whenReady のコールバックをキューに貯め、完了時にまとめて実行する。
     */
    public static void init(Context context) {
        synchronized (lock) {
            if (initialized || ioExecutor != null) return;
            final Context appContext = context.getApplicationContext();
            final Executor executor = Executors.newSingleThreadExecutor();
            ioExecutor = executor;
            executor.execute(() -> {
                try {
                    AppDatabase db = AppDatabase.open(appContext);
                    SafetyRepository repo = new SafetyRepository(db, executor);
                    synchronized (lock) {
                        repository = repo;
                        initialized = true;
                        for (Consumer<SafetyRepository> cb : pendingCallbacks) {
                            executor.execute(() -> cb.accept(repository));
                        }
                        pendingCallbacks.clear();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to initialize", e);
                }
            });
        }
    }

    public static boolean isReady() {
        return initialized;
    }

    /**
     * 準備完了後、コールバックは io スレッドで実行される。
     * 既に準備完了なら即座に io スレッドで実行する。
     */
    public static void whenReady(Consumer<SafetyRepository> callback) {
        synchronized (lock) {
            if (initialized) {
                Executor exec = ioExecutor;
                if (exec != null) {
                    exec.execute(() -> callback.accept(repository));
                } else {
                    callback.accept(repository);
                }
            } else {
                pendingCallbacks.add(callback);
            }
        }
    }

    /**
     * CompletableFuture の結果をメインスレッドで受け取る。
     * onSuccess / onFailure は必ずメインスレッドで呼ばれる。
     */
    public static <T> void onResult(CompletableFuture<T> future,
                                    Consumer<T> onSuccess,
                                    Consumer<Throwable> onFailure) {
        future.whenComplete((result, error) -> {
            if (error != null) {
                mainHandler.post(() -> {
                    if (onFailure != null) onFailure.accept(error);
                });
            } else {
                mainHandler.post(() -> {
                    if (onSuccess != null) onSuccess.accept(result);
                });
            }
        });
    }

    /** メインスレッドで実行する。 */
    public static void post(Runnable runnable) {
        mainHandler.post(runnable);
    }

    /**
     * 現在のセッション状態を非同期に読み、タイマーが有効かどうかをキャッシュする。
     * 準備未完了またはエラー時は false に設定する。
     */
    public static void refreshTimerState() {
        synchronized (lock) {
            if (!initialized) {
                // 初期化完了後にまとめて1回だけ取り直す（起動直後に誤って false で固定しない）
                whenReady(repo -> refreshTimerState());
                return;
            }
        }
        repository.currentSession().thenAccept(session -> {
            boolean active = false;
            if (session != null
                    && "ACTIVE".equals(session.state)
                    && session.endsAtWall > System.currentTimeMillis()) {
                active = true;
            }
            timerActive.set(active);
        }).exceptionally(e -> {
            Log.e(TAG, "Failed to refresh timer state", e);
            timerActive.set(false);
            return null;
        });
    }

    /** キャッシュされたタイマー状態を同期・即時で返す。 */
    public static boolean isTimerActive() {
        return timerActive.get();
    }

    private UiData() {}
}