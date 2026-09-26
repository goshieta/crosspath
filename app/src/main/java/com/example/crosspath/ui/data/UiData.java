package com.example.crosspath.ui.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.crosspath.data.AppDatabase;
import com.example.crosspath.data.KyushuMunicipalities;
import com.example.crosspath.data.SafetyRepository;
import com.example.crosspath.data.SessionStatus;
import com.example.crosspath.data.SessionStopHandler;
import com.example.crosspath.ui.ScreenPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    private static final AtomicBoolean activePeriod = new AtomicBoolean(false);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Object lock = new Object();
    private static final List<Consumer<SafetyRepository>> pendingCallbacks = new ArrayList<>();
    private static volatile SessionStatus lastSessionStatus;

    /** 停止要求IDの記録用。STOP_HANDLERより前に宣言する。 */
    private static final Set<String> stoppedSessionIds = Collections.synchronizedSet(new HashSet<>());

    /** ログとID記録のみのプレースホルダ。冪等・スレッド安全・非ブロッキング。 */
    private static final SessionStopHandler STOP_HANDLER = sessionId -> {
        Log.i(TAG, "session stop requested: " + sessionId);
        stoppedSessionIds.add(sessionId);
        // TODO(段階5: BLE/Service 実装時にこのIDの送信・広告・GATT・キューを停止する。DB削除では実通信は止まらない)
    };

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
                    SafetyRepository repo = new SafetyRepository(db, executor, KyushuMunicipalities.load(), STOP_HANDLER);
                    synchronized (lock) {
                        repository = repo;
                        initialized = true;
                        for (Consumer<SafetyRepository> cb : pendingCallbacks) {
                            executor.execute(() -> cb.accept(repository));
                        }
                        pendingCallbacks.clear();
                    }
                    // init完了時に自動で一度 checkSession（起動直後の状態を確定）
                    checkSession(status -> {}, error -> Log.e(TAG, "Initial checkSession failed", error));
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
     * 期限判定と終了処理を伴う状態取得。結果はメインスレッドで受け取る。
     * 準備未完了の場合は初期化完了後に自動的に実行する。
     */
    public static void checkSession(Consumer<SessionStatus> onStatus, Consumer<Throwable> onFailure) {
        synchronized (lock) {
            if (!initialized) {
                whenReady(repo -> checkSession(onStatus, onFailure));
                return;
            }
        }
        repository.checkAndEndExpiredSession().thenAccept(status -> {
            lastSessionStatus = status;
            activePeriod.set(ScreenPolicy.isActivePeriod(status.state));
            mainHandler.post(() -> {
                if (onStatus != null) onStatus.accept(status);
            });
        }).exceptionally(e -> {
            mainHandler.post(() -> {
                if (onFailure != null) onFailure.accept(e);
            });
            return null;
        });
    }

    /** 直近に取得した SessionStatus（未取得なら null）。 */
    public static SessionStatus lastSessionStatus() {
        return lastSessionStatus;
    }

    /**
     * refreshTimerState は checkSession の薄いラッパ（呼び出し側は変更不要）。
     */
    public static void refreshTimerState() {
        checkSession(status -> {}, error -> {});
    }

    /**
     * §11.1 の「タイマー作動中」＝未満了の期間がある（ACTIVE または CLOCK_UNCERTAIN）。
     * 通信可否（canCommunicate）ではない。
     */
    public static boolean isActivePeriod() {
        return activePeriod.get();
    }

    private UiData() {}
}